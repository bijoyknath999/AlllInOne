package com.allinone.app.downloader;

import java.util.Locale;
import java.util.UUID;

public class DownloadItem {

    public enum Status {
        QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    public String id;
    public String url;
    public String fileName;
    public String tempFilePath;
    public String savedPath;
    public Status status;
    public long downloadedBytes;
    public long totalBytes;
    public String errorMsg;
    // Speed tracking
    public long speedBps;       // current speed bytes/sec
    public long etaSeconds;     // estimated seconds remaining

    public DownloadItem(String url, String customFileName) {
        this.id = UUID.randomUUID().toString();
        this.url = url;
        this.fileName = (customFileName != null && !customFileName.trim().isEmpty())
                ? customFileName.trim()
                : extractNameFromUrl(url);
        this.status = Status.QUEUED;
        this.downloadedBytes = 0;
        this.totalBytes = -1;
    }

    private static String extractNameFromUrl(String url) {
        try {
            String path = url;
            int q = path.indexOf('?');
            if (q != -1) path = path.substring(0, q);
            int hash = path.indexOf('#');
            if (hash != -1) path = path.substring(0, hash);
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            int slash = path.lastIndexOf('/');
            String name = (slash >= 0) ? path.substring(slash + 1) : path;
            return name.isEmpty() ? "file_" + System.currentTimeMillis() : name;
        } catch (Exception e) {
            return "file_" + System.currentTimeMillis();
        }
    }

    public int getProgress() {
        if (totalBytes <= 0) return 0;
        return (int) (downloadedBytes * 100L / totalBytes);
    }

    public String getFormattedProgress() {
        if (totalBytes <= 0) return downloadedBytes > 0 ? formatBytes(downloadedBytes) : "";
        return formatBytes(downloadedBytes) + " / " + formatBytes(totalBytes);
    }

    public String getFormattedSpeed() {
        if (speedBps <= 0 || status != Status.DOWNLOADING) return "";
        if (speedBps < 1024) return speedBps + " B/s";
        if (speedBps < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", speedBps / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", speedBps / (1024.0 * 1024));
    }

    public String getFormattedEta() {
        if (etaSeconds <= 0 || status != Status.DOWNLOADING) return "";
        if (etaSeconds < 60) return etaSeconds + "s";
        if (etaSeconds < 3600) return (etaSeconds / 60) + "m " + (etaSeconds % 60) + "s";
        return (etaSeconds / 3600) + "h " + ((etaSeconds % 3600) / 60) + "m";
    }

    public String getStatusLabel() {
        switch (status) {
            case QUEUED:      return "Queued";
            case DOWNLOADING: return "Downloading";
            case PAUSED:      return "Paused";
            case COMPLETED:   return "Completed";
            case FAILED:      return "Failed";
            case CANCELLED:   return "Cancelled";
            default:          return "";
        }
    }

    /** Drawable resource id for this file's type icon. */
    public int getFileTypeIcon() {
        String name = fileName.toLowerCase(Locale.US);
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                || name.endsWith(".mov") || name.endsWith(".webm"))
            return com.allinone.app.R.drawable.ic_video_play;
        if (name.endsWith(".mp3") || name.endsWith(".aac") || name.endsWith(".flac")
                || name.endsWith(".wav") || name.endsWith(".ogg"))
            return com.allinone.app.R.drawable.ic_audio_wave;
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".webp"))
            return com.allinone.app.R.drawable.ic_file_image;
        if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".7z")
                || name.endsWith(".tar") || name.endsWith(".gz"))
            return com.allinone.app.R.drawable.ic_file_archive;
        if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")
                || name.endsWith(".txt") || name.endsWith(".xls") || name.endsWith(".xlsx"))
            return com.allinone.app.R.drawable.ic_file_doc;
        return com.allinone.app.R.drawable.ic_download_arrow;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
