package com.devbrackets.android.exomedia.util;

import android.content.Context;
import android.os.Build;

public class EMCompatUtil {
    /**
     * Checks if device supports Exoplayer, as per the following requirements:
     *
     * - Is JellyBean or greater
     *      -and-
     *      - Is a CTS-compliant device
     *          -or-
     *      - Is an Amazon device
     *          -and-
     *          - Is a Fire TV device
     *              -or-
     *          - Is Fire OS 5 and above (Android 5.0+)
     */
    public static boolean supportsExo(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN
                && (EMDeviceUtil.isDeviceCTSCompliant()
                    || (Build.MANUFACTURER.equalsIgnoreCase("Amazon")
                        && (EMDeviceUtil.isDeviceTV(context)
                            || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)));
    }
}
