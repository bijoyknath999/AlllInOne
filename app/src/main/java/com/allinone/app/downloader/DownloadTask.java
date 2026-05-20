package com.allinone.app.downloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Downloads a single file with pause/resume (HTTP Range) and speed/ETA reporting.
 * Partial data lives in getFilesDir()/dl_cache/{id} until complete.
 */
public class DownloadTask implements Runnable {

    public interface Callback {
        void onStarted(DownloadItem item);
        void onProgress(DownloadItem item);
        void onPaused(DownloadItem item);
        void onCompleted(DownloadItem item);
        void onFailed(DownloadItem item);
        void onCancelled(DownloadItem item);
    }

    private final Context     context;
    private final DownloadItem item;
    private final Callback    callback;

    private volatile boolean pauseRequested  = false;
    private volatile boolean cancelRequested = false;

    public DownloadTask(Context context, DownloadItem item, Callback callback) {
        this.context  = context.getApplicationContext();
        this.item     = item;
        this.callback = callback;
    }

    public void pause()  { pauseRequested  = true; }
    public void cancel() { cancelRequested = true; }

    @Override
    public void run() {
        item.status = DownloadItem.Status.DOWNLOADING;
        callback.onStarted(item);

        File cacheDir = new File(context.getFilesDir(), "dl_cache");
        cacheDir.mkdirs();
        File tempFile = new File(cacheDir, item.id);
        item.tempFilePath = tempFile.getAbsolutePath();

        HttpURLConnection conn = null;
        InputStream       in   = null;
        FileOutputStream  fos  = null;

        try {
            URL url = new URL(item.url);
            conn = openConnection(url);

            boolean isResume = item.downloadedBytes > 0
                    && tempFile.exists()
                    && tempFile.length() >= item.downloadedBytes;
            if (isResume) conn.setRequestProperty("Range", "bytes=" + item.downloadedBytes + "-");

            conn.connect();
            int code = conn.getResponseCode();

            if (code == HttpURLConnection.HTTP_PARTIAL) {
                long remaining = conn.getContentLengthLong();
                if (remaining > 0) item.totalBytes = item.downloadedBytes + remaining;
            } else if (code == HttpURLConnection.HTTP_OK) {
                item.downloadedBytes = 0;
                long len = conn.getContentLengthLong();
                if (len > 0) item.totalBytes = len;
            } else {
                throw new IOException("HTTP " + code);
            }

            // Refine filename from Content-Disposition
            String cd = conn.getHeaderField("Content-Disposition");
            if (cd != null) {
                String refined = parseFilename(cd);
                if (refined != null) item.fileName = refined;
            }

            in  = conn.getInputStream();
            fos = new FileOutputStream(tempFile, code == HttpURLConnection.HTTP_PARTIAL);

            byte[] buf            = new byte[8192];
            int    read;
            long   speedWindowStart = System.currentTimeMillis();
            long   speedWindowBytes = 0;
            long   lastProgressTime = 0;

            while ((read = in.read(buf)) != -1) {
                if (cancelRequested || pauseRequested) break;
                fos.write(buf, 0, read);
                item.downloadedBytes += read;
                speedWindowBytes     += read;

                long now     = System.currentTimeMillis();
                long elapsed = now - speedWindowStart;

                // Recalculate speed every 500 ms
                if (elapsed >= 500) {
                    item.speedBps      = speedWindowBytes * 1000L / elapsed;
                    speedWindowBytes   = 0;
                    speedWindowStart   = now;
                    if (item.speedBps > 0 && item.totalBytes > item.downloadedBytes) {
                        item.etaSeconds = (item.totalBytes - item.downloadedBytes) / item.speedBps;
                    }
                }

                // Fire progress callback every 300 ms
                if (now - lastProgressTime >= 300) {
                    lastProgressTime = now;
                    callback.onProgress(item);
                }
            }
            fos.flush();

        } catch (IOException e) {
            if (!cancelRequested && !pauseRequested) {
                item.status   = DownloadItem.Status.FAILED;
                item.errorMsg = e.getMessage();
                callback.onFailed(item);
                return;
            }
        } finally {
            closeQuietly(in);
            closeQuietly(fos);
            if (conn != null) conn.disconnect();
        }

        if (cancelRequested) {
            item.status = DownloadItem.Status.CANCELLED;
            tempFile.delete();
            callback.onCancelled(item);
            return;
        }
        if (pauseRequested) {
            item.speedBps   = 0;
            item.etaSeconds = 0;
            item.status     = DownloadItem.Status.PAUSED;
            callback.onPaused(item);
            return;
        }

        // Move to public Downloads
        try {
            String savedPath = moveToPublicDownloads(tempFile, item.fileName);
            item.savedPath        = savedPath;
            item.speedBps         = 0;
            item.etaSeconds       = 0;
            item.status           = DownloadItem.Status.COMPLETED;
            if (item.totalBytes > 0) item.downloadedBytes = item.totalBytes;
            tempFile.delete();
            callback.onCompleted(item);
        } catch (IOException e) {
            item.status   = DownloadItem.Status.FAILED;
            item.errorMsg = "Save failed: " + e.getMessage();
            callback.onFailed(item);
        }
    }

    private HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent",
                DownloadManagerSettings.getUserAgent(context));
        try {
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url.toString());
            if (cookie != null && !cookie.isEmpty())
                conn.setRequestProperty("Cookie", cookie);
        } catch (Exception ignored) {}
        return conn;
    }

    private String parseFilename(String header) {
        String key = "filename=";
        int idx = header.indexOf(key);
        if (idx < 0) return null;
        String name = header.substring(idx + key.length()).replace("\"", "").trim();
        int semi = name.indexOf(';');
        if (semi >= 0) name = name.substring(0, semi).trim();
        return name.isEmpty() ? null : name;
    }

    private String moveToPublicDownloads(File src, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            cv.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/AllInOne");
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("MediaStore insert failed");
            try (OutputStream os = resolver.openOutputStream(uri);
                 InputStream  is = new FileInputStream(src)) {
                copyStream(is, os);
            }
            return "Downloads/AllInOne/" + fileName;
        } else {
            File destDir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AllInOne");
            destDir.mkdirs();
            File dest = new File(destDir, fileName);
            try (InputStream  is = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(dest)) {
                copyStream(is, os);
            }
            return dest.getAbsolutePath();
        }
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
    }

    private void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }
}
