package com.allinone.app.fb;

import android.content.Context;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * Downloads Facebook CDN renditions captured by {@link com.allinone.app.FbBrowserActivity}
 * and muxes the separate video/audio tracks into a single MP4.
 *
 * <p>Two non-obvious requirements, both taken from how yt-dlp itself fetches fbcdn media:
 * <ul>
 *   <li>Send {@code User-Agent: facebookexternalhit/1.1}. Browser user agents are heavily
 *       rate-limited on fbcdn.</li>
 *   <li>Request in chunks of at most 250 MB. Larger single requests get a 403.</li>
 * </ul>
 */
public final class FbStreamDownloader {

    /** Non-browser UA — browser UAs are rate limited by fbcdn. */
    private static final String CDN_UA = "facebookexternalhit/1.1";

    /** fbcdn returns 403 for single requests much beyond this. */
    private static final long CHUNK_SIZE = 250L * 1024 * 1024;

    private static final int CONNECT_TIMEOUT = 20000;
    private static final int READ_TIMEOUT    = 30000;

    public interface Progress {
        /** @param percent 0-100, or -1 when the total length is unknown. */
        void onProgress(int percent, String line);
        boolean isCancelled();
    }

    private FbStreamDownloader() {}

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads one rendition to {@code dest}, issuing sequential ranged requests so no
     * single request exceeds {@link #CHUNK_SIZE}.
     */
    public static void download(FbStream stream, File dest, Progress cb) throws IOException {
        long total = contentLength(stream.url);
        long written = 0;

        try (OutputStream out = new FileOutputStream(dest)) {
            do {
                if (cb != null && cb.isCancelled()) throw new IOException("Cancelled");

                long before = written;
                long end = (total > 0) ? Math.min(written + CHUNK_SIZE - 1, total - 1)
                                       : written + CHUNK_SIZE - 1;

                HttpURLConnection conn = open(stream.url);
                conn.setRequestProperty("Range", "bytes=" + written + "-" + end);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                    conn.disconnect();
                    throw new IOException("fbcdn returned HTTP " + code
                            + " for " + stream.label()
                            + (code == 403 ? " (signed URL likely expired — recapture it)" : ""));
                }

                // A 200 carrying an error or login page would otherwise be written out as if
                // it were media, and only surface much later as an unparseable track.
                String type = conn.getContentType();
                if (type != null && (type.startsWith("text/") || type.contains("json"))) {
                    conn.disconnect();
                    throw new IOException("fbcdn returned " + type + " instead of media for "
                            + stream.label() + " — the URL was rejected or has expired");
                }

                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        if (cb != null && cb.isCancelled()) throw new IOException("Cancelled");
                        out.write(buf, 0, len);
                        written += len;
                        if (cb != null && total > 0) {
                            cb.onProgress((int) (written * 100 / total), null);
                        }
                    }
                } finally {
                    conn.disconnect();
                }

                // HTTP_OK means the server ignored our Range and sent everything.
                if (code == HttpURLConnection.HTTP_OK) break;
                // A chunk that yields nothing would otherwise spin forever.
                if (written == before) break;
            } while (total > 0 && written < total);
        }

        if (written == 0) throw new IOException("Downloaded 0 bytes for " + stream.label());
        if (total > 0 && written < total) {
            throw new IOException("Truncated " + stream.label() + ": got " + written
                    + " of " + total + " bytes");
        }
    }

    /**
     * One-line description of a downloaded track: size plus what the file actually begins
     * with. A valid MP4 opens with an {@code ftyp} box, so this distinguishes "the merge is
     * broken" from "the thing we downloaded was never media in the first place" — the two
     * need opposite fixes and look identical from the outside.
     */
    public static String probe(String name, File f) {
        if (f == null || !f.exists()) return name + ": missing";

        byte[] head = new byte[12];
        int read = 0;
        try (InputStream in = new java.io.FileInputStream(f)) {
            int n;
            while (read < head.length && (n = in.read(head, read, head.length - read)) > 0) {
                read += n;
            }
        } catch (IOException e) {
            return name + ": " + f.length() + " bytes, unreadable (" + e.getMessage() + ")";
        }

        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < read; i++) {
            char c = (char) (head[i] & 0xFF);
            ascii.append(c >= 32 && c < 127 ? c : '.');
        }

        boolean isMp4 = read >= 8
                && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        return name + ": " + (f.length() / 1024) + " KB, starts \"" + ascii + "\""
                + (isMp4 ? " (valid MP4)" : " (NOT an MP4)");
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setRequestProperty("User-Agent", CDN_UA);
        conn.setRequestProperty("Accept", "*/*");
        return conn;
    }

    /**
     * Total size of the rendition, or -1 when unknown.
     *
     * <p>Tries HEAD first, then falls back to a one-byte ranged GET and reads the total out
     * of {@code Content-Range: bytes 0-0/12345678} — fbcdn rejects HEAD on some edges but
     * always answers a ranged GET.
     */
    private static long contentLength(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", CDN_UA);
            if (conn.getResponseCode() / 100 == 2) {
                long len = conn.getContentLengthLong();
                if (len > 0) return len;
            }
        } catch (Exception ignored) {
            // Fall through to the ranged GET.
        } finally {
            if (conn != null) conn.disconnect();
        }

        conn = null;
        try {
            conn = open(url);
            conn.setRequestProperty("Range", "bytes=0-0");
            if (conn.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;
            String cr = conn.getHeaderField("Content-Range");
            if (cr == null) return -1;
            int slash = cr.lastIndexOf('/');
            if (slash < 0) return -1;
            return Long.parseLong(cr.substring(slash + 1).trim());
        } catch (Exception e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Mux ───────────────────────────────────────────────────────────────────

    /**
     * Locates the ffmpeg executable shipped by the youtubedl-android ffmpeg artifact. It
     * lives in the app's native library dir — the same path the YT module hands to yt-dlp
     * via {@code --ffmpeg-location}.
     */
    public static File ffmpegBinary(Context ctx) {
        File dir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        for (String name : new String[]{"libffmpeg.so", "ffmpeg"}) {
            File f = new File(dir, name);
            if (f.exists() && f.canExecute()) return f;
        }
        return null;
    }

    /**
     * Where ffmpeg's shared libraries end up.
     *
     * <p>{@code libffmpeg.so} in the native library dir is a ~300 KB dynamically-linked
     * stub, not a static binary. Everything it actually needs — libavcodec, libavformat and
     * the rest — ships as a 35 MB zip that {@code FFmpeg.getInstance().init()} unpacks here.
     * Launching the stub without pointing {@code LD_LIBRARY_PATH} at this directory fails in
     * the dynamic linker before ffmpeg runs a single line, which looks exactly like a muxing
     * failure and silently degrades the download to video-only.
     */
    private static File ffmpegLibDir(Context ctx) {
        File base = new File(ctx.getNoBackupFilesDir(),
                "youtubedl-android/packages/ffmpeg/usr/lib");
        if (base.isDirectory()) return base;
        // Older library versions unpacked under filesDir instead.
        File legacy = new File(ctx.getFilesDir(),
                "youtubedl-android/packages/ffmpeg/usr/lib");
        return legacy.isDirectory() ? legacy : null;
    }

    /**
     * Remuxes separate video and audio files into one MP4 with a stream copy (no re-encode).
     *
     * @throws IOException if ffmpeg is missing or exits non-zero — the caller should then
     *                     fall back to keeping the video-only file.
     */
    public static void mux(Context ctx, File video, File audio, File dest) throws IOException {
        // Framework muxer first: no binary, no unpacked libraries, no child-process
        // environment to get wrong. ffmpeg only covers what it cannot.
        try {
            FbMuxer.mux(video, audio, dest);
            return;
        } catch (Exception frameworkError) {
            android.util.Log.w("FbMux", "MediaMuxer failed, trying ffmpeg", frameworkError);
            if (dest.exists()) dest.delete();
            try {
                muxWithFfmpeg(ctx, video, audio, dest);
                return;
            } catch (IOException ffmpegError) {
                throw new IOException("MediaMuxer: " + frameworkError.getMessage()
                        + "\nffmpeg: " + ffmpegError.getMessage());
            }
        }
    }

    private static void muxWithFfmpeg(Context ctx, File video, File audio, File dest)
            throws IOException {
        File ffmpeg = ffmpegBinary(ctx);
        if (ffmpeg == null) throw new IOException("ffmpeg binary not found");

        File libDir = ffmpegLibDir(ctx);
        if (libDir == null) {
            throw new IOException("ffmpeg libraries not unpacked — "
                    + "FFmpeg.getInstance().init() must run first");
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg.getAbsolutePath(),
                "-y",
                "-i", video.getAbsolutePath(),
                "-i", audio.getAbsolutePath(),
                "-c", "copy",
                "-movflags", "+faststart",
                dest.getAbsolutePath());

        // Without this the stub cannot resolve libavcodec and friends, and exits before
        // doing any work. This is the whole reason merged downloads came out silent.
        Map<String, String> env = pb.environment();
        String existing = env.get("LD_LIBRARY_PATH");
        env.put("LD_LIBRARY_PATH", TextUtils.isEmpty(existing)
                ? libDir.getAbsolutePath()
                : libDir.getAbsolutePath() + ":" + existing);

        pb.redirectErrorStream(true);

        Process proc = pb.start();
        StringBuilder log = new StringBuilder();
        try (InputStream in = proc.getInputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                if (log.length() < 4000) log.append(new String(buf, 0, len));
            }
        }

        int exit;
        try {
            exit = proc.waitFor();
        } catch (InterruptedException e) {
            proc.destroy();
            Thread.currentThread().interrupt();
            throw new IOException("Muxing interrupted");
        }

        if (exit != 0 || !dest.exists() || dest.length() == 0) {
            String tail = log.toString();
            if (tail.length() > 600) tail = tail.substring(tail.length() - 600);
            throw new IOException("ffmpeg exited " + exit
                    + (TextUtils.isEmpty(tail) ? "" : ":\n" + tail));
        }
    }
}
