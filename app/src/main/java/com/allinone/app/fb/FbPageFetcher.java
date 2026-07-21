package com.allinone.app.fb;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Re-requests a Facebook page and returns the raw HTML, exactly as "View Page Source"
 * (Ctrl+U) would show it.
 *
 * <p>This is deliberately <em>not</em> the same as reading {@code documentElement.innerHTML}
 * from the WebView. Facebook ships its video metadata as JSON inside inline {@code <script>}
 * blocks; once React hydrates, those blocks are consumed and the live DOM no longer contains
 * them. The paste-your-source download sites work off the served HTML for precisely this
 * reason, and it is where the pre-muxed {@code browser_native_hd_url} lives — the single
 * file that needs no audio track and no merging at all.
 *
 * <p>The request carries the WebView's cookies, so private, group and friends-only videos
 * resolve the same way they do in the browser. Facebook serves cookie-less scrapers a
 * stripped page, which is the wall yt-dlp hits.
 */
public final class FbPageFetcher {

    private FbPageFetcher() {}

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT    = 30000;

    /** Facebook video pages run to a few MB; well past that we are reading something else. */
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    /**
     * @param cookie value for the {@code Cookie} header, from
     *               {@code CookieManager.getInstance().getCookie(url)}
     * @throws IOException on a non-200 response or a network failure
     */
    public static String fetch(String url, String cookie, String userAgent) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            // Ask for the desktop document rather than a JSON fragment.
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            if (!TextUtils.isEmpty(cookie)) conn.setRequestProperty("Cookie", cookie);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("facebook returned HTTP " + code);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                    if (out.size() > MAX_BYTES) break;
                }
            }
            return out.toString("UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
