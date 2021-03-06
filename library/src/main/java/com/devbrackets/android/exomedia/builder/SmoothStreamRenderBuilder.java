/*
 * Copyright (C) 2016 Brian Wernick,
 * Copyright (C) 2015 Sébastiaan Versteeg,
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.devbrackets.android.exomedia.builder;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;

import com.devbrackets.android.exomedia.exoplayer.EMExoPlayer;
import com.devbrackets.android.exomedia.renderer.EMMediaCodecAudioTrackRenderer;
import com.devbrackets.android.exomedia.type.MediaMimeType;
import com.google.android.exoplayer.C;
import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecSelector;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SingleSampleSource;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.drm.DrmSessionManager;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.drm.UnsupportedDrmException;
import com.google.android.exoplayer.smoothstreaming.DefaultSmoothStreamingTrackSelector;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingChunkSource;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifestParser;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingTrackSelector;
import com.google.android.exoplayer.text.TextTrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.Util;

import java.io.IOException;

/**
 * A RenderBuilder for parsing and creating the renderers for
 * Smooth Streaming streams.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class SmoothStreamRenderBuilder extends RenderBuilder {
    private static final int LIVE_EDGE_LATENCY_MS = 30000;

    private final Context context;
    private final String userAgent;
    private final String url;
    private final String captionsUrl;
    private final int streamType;

    private AsyncRendererBuilder currentAsyncBuilder;

    public SmoothStreamRenderBuilder(Context context, String userAgent, String url, String captionsUrl) {
        this(context, userAgent, url, captionsUrl, AudioManager.STREAM_MUSIC);
    }

    public SmoothStreamRenderBuilder(Context context, String userAgent, String url, int streamType) {
        this(context, userAgent, url, null, streamType);
    }

    public SmoothStreamRenderBuilder(Context context, String userAgent, String url, String captionsUrl, int streamType) {
        super(context, userAgent, url, captionsUrl);
        this.context = context;
        this.userAgent = userAgent;
        this.url = Util.toLowerInvariant(url).endsWith("/manifest") ? url : url + "/Manifest";
        this.streamType = streamType;
        this.captionsUrl = captionsUrl;
    }

    @Override
    public void buildRenderers(EMExoPlayer player) {
        currentAsyncBuilder = new AsyncRendererBuilder(context, userAgent, url, captionsUrl, player, streamType);
        currentAsyncBuilder.init();
    }

    @Override
    public void cancel() {
        if (currentAsyncBuilder != null) {
            currentAsyncBuilder.cancel();
            currentAsyncBuilder = null;
        }
    }

    private static final class AsyncRendererBuilder implements ManifestFetcher.ManifestCallback<SmoothStreamingManifest> {
        private final Context context;
        private final String userAgent;
        private final int streamType;
        private final EMExoPlayer player;
        private final ManifestFetcher<SmoothStreamingManifest> manifestFetcher;
        private final String captionsUrl;

        private boolean canceled;

        public AsyncRendererBuilder(Context context, String userAgent, String url, String captionsUrl, EMExoPlayer player, int streamType) {
            this.context = context;
            this.userAgent = userAgent;
            this.streamType = streamType;
            this.player = player;
            this.captionsUrl = captionsUrl;
            SmoothStreamingManifestParser parser = new SmoothStreamingManifestParser();
            manifestFetcher = new ManifestFetcher<>(url, new DefaultHttpDataSource(userAgent, null), parser);
        }

        public void init() {
            manifestFetcher.singleLoad(player.getMainHandler().getLooper(), this);
        }

        public void cancel() {
            canceled = true;
        }

        @Override
        public void onSingleManifestError(IOException exception) {
            if (canceled) {
                return;
            }

            player.onRenderersError(exception);
        }

        @Override
        public void onSingleManifest(SmoothStreamingManifest manifest) {
            if (canceled) {
                return;
            }

            // Check drm support if necessary.
            DrmSessionManager drmSessionManager = null;
            if (manifest.protectionElement != null) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    player.onRenderersError(new UnsupportedDrmException(UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME));
                    return;
                }

                try {
                    drmSessionManager = StreamingDrmSessionManager.newFrameworkInstance(manifest.protectionElement.uuid, player.getPlaybackLooper(), null, null, player.getMainHandler(), player);
                } catch (UnsupportedDrmException e) {
                    player.onRenderersError(e);
                    return;
                }
            }

            buildRenderers(drmSessionManager);
        }

        private void buildRenderers(DrmSessionManager drmSessionManager) {
            Handler mainHandler = player.getMainHandler();
            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter(mainHandler, player);


            //Create the Sample Source to be used by the Video Renderer
            DataSource dataSourceVideo = new DefaultUriDataSource(context, bandwidthMeter, userAgent, true);
            SmoothStreamingTrackSelector trackSelectorVideo = DefaultSmoothStreamingTrackSelector.newVideoInstance(context, true, false);
            ChunkSource chunkSourceVideo = new SmoothStreamingChunkSource(manifestFetcher, trackSelectorVideo, dataSourceVideo,
                    new FormatEvaluator.AdaptiveEvaluator(bandwidthMeter), LIVE_EDGE_LATENCY_MS);
            ChunkSampleSource sampleSourceVideo = new ChunkSampleSource(chunkSourceVideo, loadControl, BUFFER_SEGMENTS_VIDEO * BUFFER_SEGMENT_SIZE,
                    mainHandler, player, EMExoPlayer.RENDER_VIDEO);


            //Create the Sample Source to be used by the Audio Renderer
            DataSource dataSourceAudio = new DefaultUriDataSource(context, bandwidthMeter, userAgent, true);
            SmoothStreamingTrackSelector trackSelectorAudio = DefaultSmoothStreamingTrackSelector.newAudioInstance();
            ChunkSource chunkSourceAudio = new SmoothStreamingChunkSource(manifestFetcher, trackSelectorAudio, dataSourceAudio, null, LIVE_EDGE_LATENCY_MS);
            ChunkSampleSource sampleSourceAudio = new ChunkSampleSource(chunkSourceAudio, loadControl, BUFFER_SEGMENTS_AUDIO * BUFFER_SEGMENT_SIZE,
                    mainHandler, player, EMExoPlayer.RENDER_AUDIO);


            //Create the Sample Source to be used by the Closed Captions Renderer
            DataSource dataSourceCC = new DefaultUriDataSource(context, bandwidthMeter, userAgent, true);

            SampleSource sampleSourceCC;
            if (!TextUtils.isEmpty(captionsUrl)) {
                MediaFormat mediaFormat = MediaFormat.createTextFormat("0", MediaMimeType.getMimeType(Uri.parse(captionsUrl)), MediaFormat.NO_VALUE, C.MATCH_LONGEST_US, null);
                sampleSourceCC = new SingleSampleSource(Uri.parse(captionsUrl), new DefaultUriDataSource(context, bandwidthMeter, userAgent, true), mediaFormat);
            } else {
                SmoothStreamingTrackSelector trackSelectorCC = DefaultSmoothStreamingTrackSelector.newTextInstance();
                ChunkSource chunkSourceCC = new SmoothStreamingChunkSource(manifestFetcher, trackSelectorCC, dataSourceCC, null, LIVE_EDGE_LATENCY_MS);
                sampleSourceCC = new ChunkSampleSource(chunkSourceCC, loadControl, BUFFER_SEGMENTS_TEXT * BUFFER_SEGMENT_SIZE,
                        mainHandler, player, EMExoPlayer.RENDER_CLOSED_CAPTION);
            }

            // Build the renderers
            MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context, sampleSourceVideo, MediaCodecSelector.DEFAULT,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, MAX_JOIN_TIME, drmSessionManager, true, mainHandler, player, DROPPED_FRAME_NOTIFICATION_AMOUNT);
            EMMediaCodecAudioTrackRenderer audioRenderer = new EMMediaCodecAudioTrackRenderer(sampleSourceAudio, MediaCodecSelector.DEFAULT, drmSessionManager,
                    true, mainHandler, player, AudioCapabilities.getCapabilities(context), streamType);
            TextTrackRenderer captionsRenderer = new TextTrackRenderer(sampleSourceCC, player, mainHandler.getLooper());


            // Invoke the callback
            TrackRenderer[] renderers = new TrackRenderer[EMExoPlayer.RENDER_COUNT];
            renderers[EMExoPlayer.RENDER_VIDEO] = videoRenderer;
            renderers[EMExoPlayer.RENDER_AUDIO] = audioRenderer;
            renderers[EMExoPlayer.RENDER_CLOSED_CAPTION] = captionsRenderer;
            player.onRenderers(renderers, bandwidthMeter);
        }
    }
}