package com.allinone.app.fb;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One media rendition captured off Facebook's CDN while the video played in the in-app
 * browser.
 *
 * <p>Facebook streams via MSE, so the {@code <video>} element's src is a useless
 * {@code blob:} URL — but the underlying DASH representations it fetches are ordinary,
 * complete, range-servable {@code .mp4} files on {@code *.fbcdn.net}. Capturing those
 * request URLs gives us something we can download directly, with no page parsing to break.
 *
 * <p>Video and audio arrive as <em>separate</em> files and must be muxed afterwards.
 */
public class FbStream {

    /** Facebook tags each rendition in a base64 JSON {@code efg} query param. */
    private static final Pattern HEIGHT_IN_TAG = Pattern.compile("_(\\d{3,4})p");

    public final String  url;
    public final boolean isAudio;
    /** Video height in px, or -1 when unknown / audio. */
    public final int     height;
    /** Facebook's own rendition tag, e.g. {@code dash_vp9-basic-gen2_1080p}. */
    public final String  tag;
    /**
     * True for a progressive MP4 that already contains both tracks — the
     * {@code browser_native_*_url} / {@code playable_url*} files {@link FbHtmlExtractor}
     * pulls out of the page. These need no companion audio track and no muxing.
     */
    public final boolean muxed;

    public FbStream(String url, boolean isAudio, int height, String tag) {
        this(url, isAudio, height, tag, false);
    }

    public FbStream(String url, boolean isAudio, int height, String tag, boolean muxed) {
        this.url     = url;
        this.isAudio = isAudio;
        this.height  = height;
        this.tag     = tag;
        this.muxed   = muxed;
    }

    // ── Recognition ───────────────────────────────────────────────────────────

    /** True for URLs that look like a Facebook CDN media rendition. */
    public static boolean isMediaUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null) return false;
        return host.endsWith(".fbcdn.net") && path.contains(".mp4");
    }

    /**
     * Builds a stream from a captured fbcdn URL, classifying it via the {@code efg} param.
     * Returns null if the URL isn't media.
     */
    public static FbStream parse(String url) {
        if (!isMediaUrl(url)) return null;

        url = stripByteRange(url);
        String tag = decodeTag(url);
        // Facebook's tag vocabulary shifts, and a track misread as video is a track that
        // never gets merged in — so accept any of the names it has used for audio.
        String t = tag == null ? "" : tag.toLowerCase();
        boolean audio = t.contains("audio") || t.contains("mp4a")
                || t.contains("opus") || t.contains("aac");

        int height = -1;
        if (tag != null && !audio) {
            Matcher m = HEIGHT_IN_TAG.matcher(tag);
            if (m.find()) {
                try { height = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return new FbStream(url, audio, height, tag == null ? "" : tag);
    }

    /**
     * Drops the DASH segment bounds from a captured URL.
     *
     * <p>Facebook's MSE player never asks for a whole track — it asks for slices, via
     * {@code bytestart}/{@code byteend} <em>query params</em>. The first slice we see is the
     * init segment, ~17 KB. fbcdn honours those params and ignores a conflicting
     * {@code Range:} header, so downloading the URL verbatim yields that 17 KB fragment,
     * which is an MP4 header with no media in it — unplayable, and unmuxable by ffmpeg.
     * Without the params the same URL serves the complete, range-servable file.
     */
    private static String stripByteRange(String url) {
        if (!url.contains("bytestart") && !url.contains("byteend")) return url;
        try {
            Uri uri = Uri.parse(url);
            Uri.Builder b = uri.buildUpon().clearQuery();
            for (String name : uri.getQueryParameterNames()) {
                if ("bytestart".equals(name) || "byteend".equals(name)) continue;
                for (String value : uri.getQueryParameters(name)) {
                    b.appendQueryParameter(name, value);
                }
            }
            return b.build().toString();
        } catch (Exception e) {
            return url;
        }
    }

    /** Decodes the base64 JSON {@code efg} param and pulls out {@code vencode_tag}. */
    private static String decodeTag(String url) {
        try {
            String efg = Uri.parse(url).getQueryParameter("efg");
            if (TextUtils.isEmpty(efg)) return null;
            byte[] raw = Base64.decode(efg, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject json = new JSONObject(new String(raw, "UTF-8"));
            return json.optString("vencode_tag", null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    /**
     * Key used to dedupe captures. The player re-requests the same rendition many times
     * with different byte ranges and refreshed signatures, so identity is the path only.
     */
    public String key() {
        Uri uri = Uri.parse(url);
        return uri.getHost() + uri.getPath();
    }

    public String label() {
        if (isAudio) return "Audio";
        // Facebook names the progressive files only "hd"/"sd" — it never states their height.
        if (muxed) return "hd".equalsIgnoreCase(tag) ? "HD" : "SD";
        if (height > 0) return height + "p";
        return "Video";
    }

    // ── Intent transport ──────────────────────────────────────────────────────

    /** Control char — cannot occur in a URL or in Facebook's rendition tags. */
    private static final String SEP = "\u0001";

    public String encode() {
        String type = isAudio ? "a" : (muxed ? "m" : "v");
        return type + SEP + height + SEP + tag + SEP + url;
    }

    public static FbStream decode(String s) {
        if (TextUtils.isEmpty(s)) return null;
        String[] parts = s.split(SEP, 4);
        if (parts.length < 4) return null;
        int h;
        try { h = Integer.parseInt(parts[1]); } catch (NumberFormatException e) { h = -1; }
        return new FbStream(parts[3], "a".equals(parts[0]), h, parts[2], "m".equals(parts[0]));
    }
}
