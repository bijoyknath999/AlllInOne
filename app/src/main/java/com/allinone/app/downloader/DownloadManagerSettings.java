package com.allinone.app.downloader;

import android.content.Context;
import android.content.SharedPreferences;

public class DownloadManagerSettings {

    private static final String PREFS         = "dm_settings";
    private static final String KEY_HOME      = "homepage";
    private static final String KEY_MAX_DL    = "max_downloads";
    private static final String KEY_DESKTOP   = "desktop_ua";

    public static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ── Homepage ──────────────────────────────────────────────────────────────

    public static String getHomepage(Context ctx) {
        return prefs(ctx).getString(KEY_HOME, "https://www.google.com");
    }

    public static void setHomepage(Context ctx, String url) {
        prefs(ctx).edit().putString(KEY_HOME, url.trim()).apply();
    }

    // ── Max concurrent downloads ──────────────────────────────────────────────

    public static int getMaxDownloads(Context ctx) {
        return prefs(ctx).getInt(KEY_MAX_DL, 3);
    }

    public static void setMaxDownloads(Context ctx, int max) {
        prefs(ctx).edit().putInt(KEY_MAX_DL, max).apply();
    }

    // ── User-Agent ────────────────────────────────────────────────────────────

    public static boolean isDesktopUA(Context ctx) {
        return prefs(ctx).getBoolean(KEY_DESKTOP, false);
    }

    public static void setDesktopUA(Context ctx, boolean desktop) {
        prefs(ctx).edit().putBoolean(KEY_DESKTOP, desktop).apply();
    }

    public static String getUserAgent(Context ctx) {
        return isDesktopUA(ctx) ? DESKTOP_UA : MOBILE_UA;
    }
}
