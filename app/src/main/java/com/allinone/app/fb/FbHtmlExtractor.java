package com.allinone.app.fb;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls Facebook's progressive MP4 URLs straight out of the rendered page.
 *
 * <p>This is the same trick the "paste your view-source here" websites use. Facebook embeds
 * the CDN URLs for a video in JSON inside the page's own {@code <script>} tags, under keys
 * like {@code browser_native_hd_url} and {@code playable_url_quality_hd}. Those sites make
 * you paste the HTML because <em>their</em> server has no session — Facebook serves
 * anonymous fetchers a stripped page, which is the same wall yt-dlp hits with "Cannot parse
 * data". We are already inside a logged-in WebView, so we can read the full page ourselves
 * and skip the copy-paste entirely.
 *
 * <p>Two reasons this beats sniffing DASH segments off the wire (see {@link FbStream}):
 * the files are <b>pre-muxed</b>, so there is no separate audio track and no ffmpeg step;
 * and they can be read the moment the page loads, with no need to play the video first.
 *
 * <h3>Why the scan runs in JavaScript</h3>
 * A Facebook video page is several megabytes of HTML. Returning all of it through
 * {@code evaluateJavascript} means serialising it to a JSON string and pushing it across
 * the JS bridge, which is slow and unreliable at that size. Running the regex in the page
 * and returning only the handful of URLs it found keeps the payload in the low kilobytes.
 */
public final class FbHtmlExtractor {

    private FbHtmlExtractor() {}

    /**
     * Keys Facebook has used for the progressive files, best-quality first. Each maps to the
     * quality bucket we report, since Facebook never states these files' pixel height.
     */
    private static final String[][] KEYS = {
            {"browser_native_hd_url",     "hd"},
            {"playable_url_quality_hd",   "hd"},
            {"hd_src_no_ratelimit",       "hd"},
            {"hd_src",                    "hd"},
            {"browser_native_sd_url",     "sd"},
            {"playable_url",              "sd"},
            {"sd_src_no_ratelimit",       "sd"},
            {"sd_src",                    "sd"},
    };

    /** Sort keys only — these are not real measured heights. */
    private static final int HD_RANK = 1080;
    private static final int SD_RANK = 480;

    // ── Video id ──────────────────────────────────────────────────────────────

    private static final Pattern[] ID_PATTERNS = {
            Pattern.compile("/videos/[^/?#]+/(\\d{6,})"),
            Pattern.compile("/videos/(\\d{6,})"),
            Pattern.compile("/reel/(\\d{6,})"),
            Pattern.compile("[?&]v=(\\d{6,})"),
            Pattern.compile("story_fbid=(\\d{6,})"),
            Pattern.compile("/videos/.*?(\\d{9,})"),
    };

    /**
     * The numeric video id in a Facebook URL, or "" when there isn't one.
     *
     * <p>Used to tell apart several videos present in one document — a feed, or a reel the
     * user has scrolled past. Short-link forms ({@code fb.watch}, {@code /share/v/}) carry
     * no id, so those fall back to "whatever the page contains".
     */
    public static String videoIdOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        for (Pattern p : ID_PATTERNS) {
            Matcher m = p.matcher(url);
            if (m.find()) return m.group(1);
        }
        return "";
    }

    // ── Raw-HTML scan ─────────────────────────────────────────────────────────

    /** A JSON string body: any plain char, or an escape pair. */
    private static final String JSON_STR = "((?:[^\"\\\\]|\\\\.)*)";

    /** How far around a hit we look for the video id when a page holds several videos. */
    private static final int NEAR_BEFORE = 6000;
    private static final int NEAR_AFTER  = 2000;

    /**
     * Extracts streams from served HTML — the "View Page Source" text, not the live DOM.
     *
     * <p>Progressive URLs win when present: one self-contained file, nothing to merge. The
     * DASH manifest is the fallback, and it names its own audio track so the two always
     * belong together.
     */
    public static List<FbStream> parseHtml(String html, String pageUrl) {
        List<FbStream> streams = new ArrayList<>();
        if (TextUtils.isEmpty(html)) return streams;

        String id = videoIdOf(pageUrl);
        Set<String> seen = new HashSet<>();
        List<FbStream> near = new ArrayList<>();
        List<FbStream> other = new ArrayList<>();

        for (String[] key : KEYS) {
            Matcher m = Pattern.compile("\"" + key[0] + "\"\\s*:\\s*\"" + JSON_STR + "\"")
                    .matcher(html);
            while (m.find()) {
                String url = unescapeJson(m.group(1));
                if (!url.startsWith("http") || !seen.add(url)) continue;

                boolean hd = "hd".equals(key[1]);
                FbStream s = new FbStream(url, false, hd ? HD_RANK : SD_RANK, key[1], true);
                (isNear(html, m.start(), id) ? near : other).add(s);
            }
        }

        collectProgressive(html, id, seen, near, other);

        streams.addAll(near.isEmpty() ? other : near);
        if (!streams.isEmpty()) {
            Collections.sort(streams, (a, b) -> Integer.compare(b.height, a.height));
            return streams;
        }

        String manifest = findManifest(html, id);
        if (manifest != null) streams.addAll(FbDashManifest.parse(manifest));
        return streams;
    }

    /**
     * Collects {@code videoDeliveryResponseFragment}'s progressive files.
     *
     * <p>Current Facebook pages carry none of the {@link #KEYS} — those belong to an older
     * payload shape. What they do carry is
     * {@code videoDeliveryResponseResult.progressive_urls}, an array of complete MP4s with
     * audio already interleaved. Missing them is what pushed every download onto the DASH
     * ladder, where video and audio are separate files that then have to be merged — and on
     * a VP9 ladder that merge is one MediaMuxer cannot perform at all.
     *
     * <p>Quality lives in a sibling {@code metadata.quality} rather than in the key name, so
     * it is read from a short window after the URL.
     */
    private static void collectProgressive(String html, String id, Set<String> seen,
                                           List<FbStream> near, List<FbStream> other) {
        Matcher m = Pattern.compile("\"progressive_url\"\\s*:\\s*\"" + JSON_STR + "\"")
                .matcher(html);
        while (m.find()) {
            String url = unescapeJson(m.group(1));
            if (!url.startsWith("http") || !seen.add(url)) continue;

            // The quality tag follows the URL inside the same array element; stay close
            // enough that the next element's tag cannot be picked up by mistake.
            int to = Math.min(html.length(), m.end() + QUALITY_WINDOW);
            boolean hd = html.substring(m.end(), to).contains("\"quality\":\"HD\"");

            FbStream s = new FbStream(url, false, hd ? HD_RANK : SD_RANK,
                    hd ? "hd" : "sd", true);
            (isNear(html, m.start(), id) ? near : other).add(s);
        }
    }

    /** How far past a progressive URL its {@code metadata.quality} tag can sit. */
    private static final int QUALITY_WINDOW = 200;

    /** The manifest for the target video, preferring one sitting near its id. */
    private static String findManifest(String html, String id) {
        String fallback = null;
        for (String key : new String[]{"manifest_xml", "dash_manifest_xml_string", "dash_manifest"}) {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"" + JSON_STR + "\"")
                    .matcher(html);
            while (m.find()) {
                String xml = unescapeJson(m.group(1));
                if (!xml.contains("<Representation")) continue;
                if (isNear(html, m.start(), id)) return xml;
                if (fallback == null) fallback = xml;
            }
        }
        return fallback;
    }

    private static boolean isNear(String html, int at, String id) {
        if (TextUtils.isEmpty(id)) return false;
        int from = Math.max(0, at - NEAR_BEFORE);
        int to   = Math.min(html.length(), at + NEAR_AFTER);
        return html.substring(from, to).contains(id);
    }

    /**
     * Undoes JSON string escaping — the URLs arrive with escaped slashes and escaped
     * unicode code points.
     *
     * <p>(Do not write a backslash-u sequence in this file, even in a comment: javac
     * decodes unicode escapes before it lexes, so it fails to compile.)
     */
    private static String unescapeJson(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { out.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { out.append(n); }
                    } else out.append(n);
                    break;
                default: out.append(n); // covers \/ \" \\
            }
        }
        return out.toString();
    }

    // ── The in-page scan ──────────────────────────────────────────────────────

    /**
     * Builds the script to hand to {@code WebView.evaluateJavascript}. Feed its result to
     * {@link #parse(String)}.
     *
     * <p>Each hit is tagged {@code n} (near) when the target video id appears in the
     * surrounding JSON, which is how we keep a feed's other videos out of the results.
     */
    public static String script(String pageUrl) {
        // Digits only — this is interpolated into source, so it must not be able to escape.
        String id = videoIdOf(pageUrl).replaceAll("[^0-9]", "");

        StringBuilder keys = new StringBuilder("[");
        for (String[] k : KEYS) {
            if (keys.length() > 1) keys.append(',');
            keys.append("['").append(k[0]).append("','").append(k[1]).append("']");
        }
        keys.append(']');

        return "(function(){try{"
                + "var h=document.documentElement.innerHTML;"
                + "var id='" + id + "';"
                + "var K=" + keys + ";"
                + "var out=[],seen={},mf='',mfn=false;"
                // Values are JSON strings inside the page source, so let JSON.parse undo
                // the escaping — hand-rolled replaces miss \" and \\ inside the manifest.
                + "function un(s){try{return JSON.parse('\"'+s+'\"');}"
                + "catch(e){return s.replace(/\\\\\\//g,'/');}}"
                + "function near(i){if(!id)return false;var a=i-6000;if(a<0)a=0;"
                + "return h.slice(a,i+2000).indexOf(id)>=0;}"
                // A JSON string body: any non-quote/backslash char, or an escape pair.
                + "var STR='((?:[^\"\\\\\\\\]|\\\\\\\\.)*)';"
                + "for(var i=0;i<K.length;i++){"
                + "var re=new RegExp('\"'+K[i][0]+'\"\\\\s*:\\\\s*\"'+STR+'\"','g'),m;"
                + "while((m=re.exec(h))!==null){"
                + "var u=un(m[1]);"
                + "if(u.indexOf('http')!==0||seen[u])continue;seen[u]=1;"
                + "out.push({u:u,q:K[i][1],n:near(m.index)});"
                + "}}"
                // videoDeliveryResponseFragment's progressive files — the shape current
                // pages actually use. Complete MP4s with audio already in them, so finding
                // one here avoids the DASH ladder and its unmergeable VP9 tracks entirely.
                // Quality sits in a sibling metadata.quality, not in the key name.
                + "var pre=new RegExp('\"progressive_url\"\\\\s*:\\\\s*\"'+STR+'\"','g'),pm;"
                + "while((pm=pre.exec(h))!==null){"
                + "var pu=un(pm[1]);"
                + "if(pu.indexOf('http')!==0||seen[pu])continue;seen[pu]=1;"
                + "var hd=h.slice(pre.lastIndex,pre.lastIndex+200)"
                + ".indexOf('\"quality\":\"HD\"')>=0;"
                + "out.push({u:pu,q:hd?'hd':'sd',n:near(pm.index)});"
                + "}"
                // The DASH manifest lists every track, audio included — the fallback when
                // no progressive URL is present.
                + "var MK=['manifest_xml','dash_manifest_xml_string','dash_manifest'];"
                + "for(var j=0;j<MK.length;j++){"
                + "var mre=new RegExp('\"'+MK[j]+'\"\\\\s*:\\\\s*\"'+STR+'\"','g'),mm;"
                + "while((mm=mre.exec(h))!==null){"
                + "var x=un(mm[1]);"
                + "if(x.indexOf('<Representation')<0||x.length>600000)continue;"
                + "var n2=near(mm.index);"
                + "if(mfn&&!n2)continue;"
                + "mf=x;mfn=n2;if(n2)break;"
                + "}if(mfn)break;}"
                + "return JSON.stringify({href:location.href,items:out,dash:mf});"
                + "}catch(e){return JSON.stringify({error:String(e)});}})()";
    }

    /**
     * Turns the script's result into streams, HD first.
     *
     * <p>When any hit was tagged near the target id, only those are kept — otherwise every
     * video in a feed would show up as a quality option. Returns empty on anything
     * unexpected; the caller falls back to the sniffed DASH streams.
     */
    public static List<FbStream> parse(String evaluateJavascriptResult) {
        List<FbStream> streams = new ArrayList<>();
        if (TextUtils.isEmpty(evaluateJavascriptResult)
                || "null".equals(evaluateJavascriptResult)) {
            return streams;
        }

        try {
            // evaluateJavascript hands back the JS value already JSON-encoded, so our
            // stringified object arrives wrapped in another layer of quoting.
            Object outer = new JSONTokener(evaluateJavascriptResult).nextValue();
            String json = (outer instanceof String) ? (String) outer : evaluateJavascriptResult;

            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items == null) items = new JSONArray();

            boolean anyNear = false;
            for (int i = 0; i < items.length(); i++) {
                if (items.getJSONObject(i).optBoolean("n")) { anyNear = true; break; }
            }

            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                if (anyNear && !o.optBoolean("n")) continue;

                String url = o.optString("u", "");
                if (!url.startsWith("http")) continue;

                boolean hd = "hd".equals(o.optString("q"));
                streams.add(new FbStream(url, false, hd ? HD_RANK : SD_RANK,
                        hd ? "hd" : "sd", true));
            }

            // Progressive files are self-contained, so they need no manifest. Only when the
            // page exposes none do we fall back to the manifest's separate tracks — which
            // is also the only page source that names the audio track explicitly.
            if (streams.isEmpty()) {
                streams.addAll(FbDashManifest.parse(root.optString("dash", "")));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        // HD ahead of SD; the browser's insertion order already follows KEYS otherwise.
        java.util.Collections.sort(streams, (a, b) -> Integer.compare(b.height, a.height));
        return streams;
    }
}
