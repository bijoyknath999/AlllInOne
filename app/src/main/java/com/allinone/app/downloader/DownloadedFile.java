package com.allinone.app.downloader;

import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DownloadedFile {

    public enum FileType { VIDEO, AUDIO, PDF, IMAGE, ARCHIVE, DOCUMENT, APK, OTHER }

    public String name;
    public String path;       // file-system path (API 28) or null
    public long   size;
    public long   dateMs;
    public String mimeType;
    public Uri    contentUri; // MediaStore URI (API 29+)

    public DownloadedFile() {}

    // ── Derived helpers ───────────────────────────────────────────────────────

    public String getExtension() {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    public FileType getFileType() {
        if (mimeType != null) {
            if (mimeType.startsWith("video/"))                              return FileType.VIDEO;
            if (mimeType.startsWith("audio/"))                              return FileType.AUDIO;
            if (mimeType.equals("application/pdf"))                        return FileType.PDF;
            if (mimeType.startsWith("image/"))                             return FileType.IMAGE;
            if (mimeType.contains("zip") || mimeType.contains("rar")
                    || mimeType.contains("compress") || mimeType.equals("application/x-7z-compressed"))
                                                                            return FileType.ARCHIVE;
            if (mimeType.equals("application/vnd.android.package-archive")) return FileType.APK;
            if (mimeType.contains("word") || mimeType.contains("text")
                    || mimeType.contains("sheet") || mimeType.contains("presentation"))
                                                                            return FileType.DOCUMENT;
        }
        switch (getExtension()) {
            case "mp4": case "mkv": case "avi": case "mov": case "webm": return FileType.VIDEO;
            case "mp3": case "aac": case "flac": case "wav": case "ogg": return FileType.AUDIO;
            case "pdf":                                                   return FileType.PDF;
            case "jpg": case "jpeg": case "png": case "gif": case "webp":return FileType.IMAGE;
            case "zip": case "rar": case "7z": case "tar": case "gz":    return FileType.ARCHIVE;
            case "apk":                                                   return FileType.APK;
            case "doc": case "docx": case "xls": case "xlsx":
            case "pptx": case "txt":                                      return FileType.DOCUMENT;
            default:                                                      return FileType.OTHER;
        }
    }

    /** Icon drawable resource for this file type. */
    public int getIconRes() {
        switch (getFileType()) {
            case VIDEO:    return com.allinone.app.R.drawable.ic_video_play;
            case AUDIO:    return com.allinone.app.R.drawable.ic_audio_wave;
            case IMAGE:    return com.allinone.app.R.drawable.ic_file_image;
            case ARCHIVE:  return com.allinone.app.R.drawable.ic_file_archive;
            case APK:      return com.allinone.app.R.drawable.ic_download_arrow;
            case PDF:
            case DOCUMENT: return com.allinone.app.R.drawable.ic_file_doc;
            default:       return com.allinone.app.R.drawable.ic_download_arrow;
        }
    }

    /** Background tint color for the icon container. */
    public int getIconBgColor() {
        switch (getFileType()) {
            case VIDEO:    return 0x26E53935; // red tint
            case AUDIO:    return 0x269C27B0; // purple tint
            case IMAGE:    return 0x264CAF50; // green tint
            case ARCHIVE:  return 0x26FF9800; // orange tint
            case APK:      return 0x263F51B5; // indigo tint
            case PDF:      return 0x26FF5722; // deep-orange tint
            case DOCUMENT: return 0x262196F3; // blue tint
            default:       return 0x26607D8B; // grey tint
        }
    }

    public String getFormattedSize() {
        long b = size;
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    public String getFormattedDate() {
        return new SimpleDateFormat("dd MMM yyyy", Locale.US).format(new Date(dateMs));
    }
}
