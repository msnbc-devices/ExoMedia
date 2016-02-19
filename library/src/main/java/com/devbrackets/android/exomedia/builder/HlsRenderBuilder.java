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
import com.google.android.exoplayer.MediaCodecUtil;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.SingleSampleSource;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.VideoFormatSelectorUtil;
import com.google.android.exoplayer.hls.DefaultHlsTrackSelector;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.PtsTimestampAdjusterProvider;
import com.google.android.exoplayer.metadata.Id3Parser;
import com.google.android.exoplayer.metadata.MetadataTrackRenderer;
import com.google.android.exoplayer.text.TextTrackRenderer;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.ManifestFetcher.ManifestCallback;

import java.io.IOException;
import java.util.Map;

/**
 * A RenderBuilder for parsing and creating the renderers for
 * Http Live Streams (HLS).
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class HlsRenderBuilder extends RenderBuilder {
    private final Context context;
    private final String userAgent;
    private final String url;
    private final int streamType;
    private final String captionsUrl;

    private AsyncRendererBuilder currentAsyncBuilder;

    public HlsRenderBuilder(Context context, String userAgent, String url, String captionsUrl) {
        this(context, userAgent, url, captionsUrl, AudioManager.STREAM_MUSIC);
    }

    public HlsRenderBuilder(Context context, String userAgent, String url, int streamType) {
        this(context, userAgent, url, null, streamType);
    }

    public HlsRenderBuilder(Context context, String userAgent, String url, String captionsUrl, int streamType) {
        super(context, userAgent, url, captionsUrl);
        this.context = context;
        this.userAgent = userAgent;
        this.url = url;
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

    private static final class AsyncRendererBuilder implements ManifestCallback<HlsPlaylist> {
        private final Context context;
        private final String userAgent;
        private final String url;
        private final int streamType;
        private final String captionsUrl;
        private final EMExoPlayer player;
        private final ManifestFetcher<HlsPlaylist> playlistFetcher;

        private boolean canceled;

        public AsyncRendererBuilder(Context context, String userAgent, String url, String captionsUrl, EMExoPlayer player, int streamType) {
            this.context = context;
            this.userAgent = userAgent;
            this.url = url;
            this.streamType = streamType;
            this.captionsUrl = captionsUrl;
            this.player = player;

            HlsPlaylistParser parser = new HlsPlaylistParser();
            playlistFetcher = new ManifestFetcher<>(url, new DefaultUriDataSource(context, null, userAgent, true), parser);
        }

        public void init() {
            playlistFetcher.singleLoad(player.getMainHandler().getLooper(), this);
        }

        public void cancel() {
            canceled = true;
        }

        @Override
        public void onSingleManifestError(IOException e) {
            if (canceled) {
                return;
            }

            player.onRenderersError(e);
        }

        @Override
        public void onSingleManifest(HlsPlaylist playlist) {
            if (canceled) {
                return;
            }

            buildRenderers(playlist);
        }

        private void buildRenderers(HlsPlaylist playlist) {
            Handler mainHandler = player.getMainHandler();
            LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
            DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter(mainHandler, player);
            PtsTimestampAdjusterProvider timestampAdjusterProvider = new PtsTimestampAdjusterProvider();

            //Calculates the Chunk variant indices
            int[] variantIndices;
            if (playlist instanceof HlsMasterPlaylist) {
                HlsMasterPlaylist masterPlaylist = (HlsMasterPlaylist) playlist;

                try {
                    variantIndices = VideoFormatSelectorUtil.selectVideoFormatsForDefaultDisplay(context, masterPlaylist.variants, null, false);
                } catch (MediaCodecUtil.DecoderQueryException e) {
                    player.onRenderersError(e);
                    return;
                }

                if (variantIndices.length == 0) {
                    player.onRenderersError(new IllegalStateException("No variants selected."));
                    return;
                }
            }

            //Create the Sample Source to be used by the renders
            DataSource dataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent, true);
            HlsChunkSource chunkSource = new HlsChunkSource(true, dataSource, url, playlist, DefaultHlsTrackSelector.newDefaultInstance(context),
                    bandwidthMeter, timestampAdjusterProvider, HlsChunkSource.ADAPTIVE_MODE_SPLICE);
            HlsSampleSource sampleSource = new HlsSampleSource(chunkSource, loadControl,
                    BUFFER_SEGMENTS_TOTAL * BUFFER_SEGMENT_SIZE, mainHandler, player, EMExoPlayer.RENDER_VIDEO);

            SingleSampleSource sampleSourceCC = null;
            if (!TextUtils.isEmpty(captionsUrl)) {
                MediaFormat mediaFormat = MediaFormat.createTextFormat("0", MediaMimeType.getMimeType(Uri.parse(captionsUrl)), MediaFormat.NO_VALUE, C.MATCH_LONGEST_US, null);
                sampleSourceCC = new SingleSampleSource(Uri.parse(captionsUrl), new DefaultUriDataSource(context, bandwidthMeter, userAgent, true), mediaFormat);
            }

            //Build the renderers
            MediaCodecVideoTrackRenderer videoRenderer = new MediaCodecVideoTrackRenderer(context, sampleSource, MediaCodecSelector.DEFAULT,
                    MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, MAX_JOIN_TIME, mainHandler, player, DROPPED_FRAME_NOTIFICATION_AMOUNT);
            EMMediaCodecAudioTrackRenderer audioRenderer = new EMMediaCodecAudioTrackRenderer(sampleSource, MediaCodecSelector.DEFAULT, null, true,
                    player.getMainHandler(), player, AudioCapabilities.getCapabilities(context), streamType);
            TextTrackRenderer captionsRenderer = new TextTrackRenderer(sampleSourceCC != null ? sampleSourceCC : sampleSource, player, mainHandler.getLooper());
            MetadataTrackRenderer<Map<String, Object>> id3Renderer = new MetadataTrackRenderer<>(sampleSource, new Id3Parser(),
                    player, mainHandler.getLooper());


            //Populate the Render list to pass back to the callback
            TrackRenderer[] renderers = new TrackRenderer[EMExoPlayer.RENDER_COUNT];
            renderers[EMExoPlayer.RENDER_VIDEO] = videoRenderer;
            renderers[EMExoPlayer.RENDER_AUDIO] = audioRenderer;
            renderers[EMExoPlayer.RENDER_CLOSED_CAPTION] = captionsRenderer;
            renderers[EMExoPlayer.RENDER_TIMED_METADATA] = id3Renderer;
            player.onRenderers(renderers, bandwidthMeter);
        }
    }
}