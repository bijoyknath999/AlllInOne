package com.allinone.app.fb;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the DASH manifest Facebook embeds in its own page JSON (the {@code dash_manifest}
 * key) and turns its representations into {@link FbStream}s.
 *
 * <p>This is the authoritative source for what a video actually contains. Sniffing segment
 * requests off the wire only shows the tracks the player happened to fetch, and forces us
 * to guess which of them is audio from Facebook's rendition tag — a guess that fails
 * whenever Facebook changes its tag vocabulary, leaving a video with no audio to merge.
 * The manifest states it outright: audio representations carry an audio codec and, unlike
 * every video representation, no {@code height}.
 *
 * <p>Manifest entries are plain {@code <BaseURL>}s — complete, range-servable MP4s, the
 * same kind of URL {@link FbStreamDownloader} already handles.
 */
public final class FbDashManifest {

    private FbDashManifest() {}

    private static final Pattern REPRESENTATION =
            Pattern.compile("<Representation\\b([^>]*)>(.*?)</Representation>", Pattern.DOTALL);
    private static final Pattern BASE_URL =
            Pattern.compile("<BaseURL>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?\\s*</BaseURL>",
                    Pattern.DOTALL);
    private static final Pattern HEIGHT    = Pattern.compile("\\bheight=\"(\\d+)\"");
    private static final Pattern BANDWIDTH = Pattern.compile("\\bbandwidth=\"(\\d+)\"");
    private static final Pattern MIME      = Pattern.compile("\\bmimeType=\"([^\"]*)\"");
    private static final Pattern CODECS    = Pattern.compile("\\bcodecs=\"([^\"]*)\"");

    /**
     * Parses a manifest into streams: every distinct video height (highest bitrate of each)
     * plus the single best audio track. Returns empty on anything unparseable.
     */
    public static List<FbStream> parse(String xml) {
        List<FbStream> out = new ArrayList<>();
        if (TextUtils.isEmpty(xml) || !xml.contains("<Representation")) return out;

        List<Rep> videos = new ArrayList<>();
        List<Rep> audios = new ArrayList<>();

        Matcher rep = REPRESENTATION.matcher(xml);
        while (rep.find()) {
            String attrs = rep.group(1);
            String body  = rep.group(2);

            Matcher b = BASE_URL.matcher(body);
            if (!b.find()) continue;
            String url = unescapeXml(b.group(1).trim());
            if (!url.startsWith("http")) continue;

            int height = intAttr(HEIGHT, attrs);
            int bw     = intAttr(BANDWIDTH, attrs);
            String codec = attr(CODECS, attrs).toLowerCase();
            String kind  = (attr(MIME, attrs) + " " + codec).toLowerCase();

            // A video representation always declares a height; an audio one never does.
            boolean audio = height <= 0
                    || kind.contains("audio") || kind.contains("mp4a") || kind.contains("opus");

            (audio ? audios : videos).add(new Rep(url, height, bw, codec));
        }

        // Facebook publishes the same video twice: an H.264/AAC ladder and a VP9/Opus one.
        // Only the former can be written into an MP4 by the platform muxer, so offering the
        // latter means offering a download that cannot be merged. Prefer what will work,
        // and fall back to the rest only when there is no alternative.
        List<Rep> playableVideos = preferred(videos, MUXABLE_VIDEO);
        List<Rep> playableAudios = preferred(audios, MUXABLE_AUDIO);

        // Best bitrate per height, tallest first.
        Map<Integer, Rep> byHeight = new LinkedHashMap<>();
        for (Rep r : playableVideos) {
            Rep prev = byHeight.get(r.height);
            if (prev == null || r.bandwidth > prev.bandwidth) byHeight.put(r.height, r);
        }
        List<Rep> chosenVideos = new ArrayList<>(byHeight.values());
        java.util.Collections.sort(chosenVideos, (x, y) -> Integer.compare(y.height, x.height));

        for (Rep r : chosenVideos) {
            out.add(new FbStream(r.url, false, r.height,
                    "dash_" + shortCodec(r.codec) + "_" + r.height + "p"));
        }

        Rep bestAudio = null;
        for (Rep r : playableAudios) {
            if (bestAudio == null || r.bandwidth > bestAudio.bandwidth) bestAudio = r;
        }
        if (bestAudio != null) {
            out.add(new FbStream(bestAudio.url, true, -1,
                    "dash_audio_" + shortCodec(bestAudio.codec)));
        }
        return out;
    }

    // ── Codec selection ───────────────────────────────────────────────────────

    /** Video codecs {@code MediaMuxer} can write into an MP4. */
    private static final String[] MUXABLE_VIDEO = {"avc1", "avc3", "h264", "hev1", "hvc1"};
    /** Audio codecs {@code MediaMuxer} can write into an MP4. */
    private static final String[] MUXABLE_AUDIO = {"mp4a", "aac"};

    /** Those entries matching one of {@code wanted}, or all of them if none match. */
    private static List<Rep> preferred(List<Rep> all, String[] wanted) {
        List<Rep> match = new ArrayList<>();
        for (Rep r : all) {
            for (String w : wanted) {
                if (r.codec.contains(w)) { match.add(r); break; }
            }
        }
        return match.isEmpty() ? all : match;
    }

    private static String shortCodec(String codec) {
        if (TextUtils.isEmpty(codec)) return "unknown";
        int dot = codec.indexOf('.');
        return dot > 0 ? codec.substring(0, dot) : codec;
    }

    /** One parsed {@code <Representation>}. */
    private static final class Rep {
        final String url;
        final int    height;
        final int    bandwidth;
        final String codec;

        Rep(String url, int height, int bandwidth, String codec) {
            this.url = url;
            this.height = height;
            this.bandwidth = bandwidth;
            this.codec = codec == null ? "" : codec;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String attr(Pattern p, String attrs) {
        Matcher m = p.matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static int intAttr(Pattern p, String attrs) {
        String v = attr(p, attrs);
        if (TextUtils.isEmpty(v)) return -1;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * XML entity decode. This matters more than it looks: a signed fbcdn URL is mostly
     * query parameters, so its ampersands all arrive as {@code &amp;} and the URL is dead
     * until they're restored.
     */
    private static String unescapeXml(String s) {
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#039;", "'")
                .replace("&apos;", "'");
    }
}
