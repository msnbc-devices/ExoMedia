package com.devbrackets.android.exomedia.type;

import android.net.Uri;
import android.support.annotation.Nullable;

import com.google.android.exoplayer.util.MimeTypes;

/**
 * An enum for determining the type of media a particular
 * url is.
 */
public enum MediaMimeType {
    SUBRIP(".*.srt.*"),
    VTT(".*.vtt.*"),
    TTML(".*.tt.*"),
    DEFAULT(null);

    @Nullable
    private String regex;

    MediaMimeType(@Nullable String regex) {
        this.regex = regex;
    }

    @Nullable
    public String getRegex() {
        return regex;
    }

    public static MediaMimeType get(Uri uri) {
        for (int ordinal = 0; ordinal < values().length; ordinal++) {
            String regex = values()[ordinal].getRegex();
            if (regex != null && uri.toString().matches(regex)) {
                return values()[ordinal];
            }
        }

        return MediaMimeType.DEFAULT;
    }

    public static String getMimeType(Uri uri) {
        switch (get(uri)) {
            case VTT:
                return MimeTypes.TEXT_VTT;
            case TTML:
                return MimeTypes.APPLICATION_TTML;
            case SUBRIP:
                return MimeTypes.APPLICATION_SUBRIP;
            default:
                return "unknown";
        }
    }
}
