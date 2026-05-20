package com.allinone.app.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.allinone.app.DownloadManagerActivity;
import com.allinone.app.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that manages file downloads in the background.
 * Shows persistent notifications with speed, progress, and ETA.
 * Downloads survive activity close.
 */
public class DownloadService extends Service implements DownloadTask.Callback {

    // ── Constants ────────────────────────────────────────────────────────────

    private static final String CHANNEL_ID        = "download_channel";
    private static final String CHANNEL_DONE_ID   = "download_done_channel";
    private static final int    NOTIF_ID          = 9001;
    private static final int    NOTIF_DONE_BASE   = 10000;

    public  static final String ACTION_ADD        = "com.allinone.app.ADD_DOWNLOAD";
    public  static final String ACTION_PAUSE      = "com.allinone.app.PAUSE_DOWNLOAD";
    public  static final String ACTION_RESUME     = "com.allinone.app.RESUME_DOWNLOAD";
    public  static final String ACTION_CANCEL     = "com.allinone.app.CANCEL_DOWNLOAD";

    public  static final String EXTRA_URL         = "extra_url";
    public  static final String EXTRA_FILENAME    = "extra_filename";
    public  static final String EXTRA_TOTAL_BYTES = "extra_total_bytes";
    public  static final String EXTRA_ITEM_ID     = "extra_item_id";

    // ── Binder ───────────────────────────────────────────────────────────────

    public class LocalBinder extends Binder {
        public DownloadService getService() { return DownloadService.this; }
    }

    private final IBinder binder = new LocalBinder();

    // ── State ────────────────────────────────────────────────────────────────

    private final List<DownloadItem>        downloads = new ArrayList<>();
    private final Map<String, DownloadTask> tasks     = new HashMap<>();

    private ExecutorService   executor;
    private NotificationManager notifManager;
    private final Handler     mainHandler = new Handler(Looper.getMainLooper());

    private int doneCounter = 0;

    /** UI listener attached when activity binds. */
    private UICallback uiCallback;

    // ── Callback interface for activity ──────────────────────────────────────

    public interface UICallback {
        void onDownloadAdded(DownloadItem item);
        void onDownloadProgress(DownloadItem item);
        void onDownloadStateChanged(DownloadItem item);
    }

    public void setUICallback(UICallback cb) { this.uiCallback = cb; }

    public List<DownloadItem> getDownloads() { return downloads; }

    public Map<String, DownloadTask> getTasks() { return tasks; }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = getSystemService(NotificationManager.class);
        createNotificationChannels();
        rebuildExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) action = ACTION_ADD;

        switch (action) {
            case ACTION_ADD: {
                String url      = intent.getStringExtra(EXTRA_URL);
                String fileName = intent.getStringExtra(EXTRA_FILENAME);
                long totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, -1);
                if (url != null) {
                    DownloadItem item = new DownloadItem(url, fileName);
                    if (totalBytes > 0) item.totalBytes = totalBytes;
                    addAndStartDownload(item);
                }
                break;
            }
            case ACTION_PAUSE: {
                String id = intent.getStringExtra(EXTRA_ITEM_ID);
                if (id != null) pauseDownload(id);
                break;
            }
            case ACTION_RESUME: {
                String id = intent.getStringExtra(EXTRA_ITEM_ID);
                if (id != null) resumeDownload(id);
                break;
            }
            case ACTION_CANCEL: {
                String id = intent.getStringExtra(EXTRA_ITEM_ID);
                if (id != null) cancelDownload(id);
                break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        for (DownloadTask t : tasks.values()) t.cancel();
        tasks.clear();
        executor.shutdownNow();
    }

    // ── Executor ─────────────────────────────────────────────────────────────

    public void rebuildExecutor() {
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
        int max = DownloadManagerSettings.getMaxDownloads(this);
        executor = Executors.newFixedThreadPool(max);
    }

    // ── Download management ──────────────────────────────────────────────────

    private void addAndStartDownload(DownloadItem item) {
        downloads.add(0, item);
        startDownload(item);
        startForeground(NOTIF_ID, buildProgressNotification());
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onDownloadAdded(item));
        }
    }

    /** Public method for activity to add a download directly. */
    public void enqueueDownload(String url, String fileName, long totalBytes) {
        DownloadItem item = new DownloadItem(url, fileName);
        if (totalBytes > 0) item.totalBytes = totalBytes;
        addAndStartDownload(item);
    }

    private void startDownload(DownloadItem item) {
        DownloadTask task = new DownloadTask(this, item, this);
        tasks.put(item.id, task);
        executor.execute(task);
    }

    public void pauseDownload(String id) {
        DownloadTask t = tasks.get(id);
        if (t != null) t.pause();
    }

    public void resumeDownload(String id) {
        DownloadItem item = findById(id);
        if (item != null && (item.status == DownloadItem.Status.PAUSED
                || item.status == DownloadItem.Status.QUEUED)) {
            item.status = DownloadItem.Status.QUEUED;
            startDownload(item);
            updateNotification();
        }
    }

    public void cancelDownload(String id) {
        DownloadTask t = tasks.remove(id);
        if (t != null) {
            t.cancel();
        } else {
            DownloadItem item = findById(id);
            if (item != null) {
                item.status = DownloadItem.Status.CANCELLED;
                mainHandler.post(() -> {
                    if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
                    checkStopSelf();
                });
            }
        }
    }

    private DownloadItem findById(String id) {
        for (DownloadItem d : downloads) {
            if (d.id.equals(id)) return d;
        }
        return null;
    }

    // ── DownloadTask.Callback ────────────────────────────────────────────────

    @Override
    public void onStarted(DownloadItem item) {
        mainHandler.post(() -> {
            updateNotification();
            if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
        });
    }

    @Override
    public void onProgress(DownloadItem item) {
        mainHandler.post(() -> {
            updateNotification();
            if (uiCallback != null) uiCallback.onDownloadProgress(item);
        });
    }

    @Override
    public void onPaused(DownloadItem item) {
        mainHandler.post(() -> {
            tasks.remove(item.id);
            updateNotification();
            if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
            checkStopSelf();
        });
    }

    @Override
    public void onCompleted(DownloadItem item) {
        mainHandler.post(() -> {
            tasks.remove(item.id);
            updateNotification();
            showDoneNotification(item);
            if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
            checkStopSelf();
        });
    }

    @Override
    public void onFailed(DownloadItem item) {
        mainHandler.post(() -> {
            tasks.remove(item.id);
            updateNotification();
            if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
            checkStopSelf();
        });
    }

    @Override
    public void onCancelled(DownloadItem item) {
        mainHandler.post(() -> {
            tasks.remove(item.id);
            updateNotification();
            if (uiCallback != null) uiCallback.onDownloadStateChanged(item);
            checkStopSelf();
        });
    }

    // ── Stop when idle ───────────────────────────────────────────────────────

    private void checkStopSelf() {
        if (tasks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ── Notification channels ────────────────────────────────────────────────

    private void createNotificationChannels() {
        // Progress channel
        NotificationChannel progress = new NotificationChannel(
                CHANNEL_ID, "Download Progress",
                NotificationManager.IMPORTANCE_LOW);
        progress.setDescription("Shows download progress and speed");
        progress.setSound(null, null);
        progress.enableVibration(false);
        notifManager.createNotificationChannel(progress);

        // Completed channel
        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE_ID, "Download Complete",
                NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription("Notifies when a download finishes");
        notifManager.createNotificationChannel(done);
    }

    // ── Build notifications ──────────────────────────────────────────────────

    private void updateNotification() {
        if (tasks.isEmpty()) return;
        notifManager.notify(NOTIF_ID, buildProgressNotification());
    }

    private Notification buildProgressNotification() {
        // Aggregate stats
        int activeCount = 0;
        long totalSpeed = 0;
        String firstName = "Downloading…";
        int firstProgress = 0;
        long firstDownloaded = 0, firstTotal = -1;
        String firstEta = "";

        for (DownloadItem d : downloads) {
            if (d.status == DownloadItem.Status.DOWNLOADING) {
                activeCount++;
                totalSpeed += d.speedBps;
                if (activeCount == 1) {
                    firstName = d.fileName;
                    firstProgress = d.getProgress();
                    firstDownloaded = d.downloadedBytes;
                    firstTotal = d.totalBytes;
                    firstEta = d.getFormattedEta();
                }
            }
        }

        // If nothing actively downloading, show queued info
        if (activeCount == 0) {
            int queuedCount = 0;
            for (DownloadItem d : downloads) {
                if (d.status == DownloadItem.Status.QUEUED || d.status == DownloadItem.Status.PAUSED)
                    queuedCount++;
            }
            firstName = queuedCount > 0 ? queuedCount + " download(s) queued" : "Download Manager";
        }

        // Content text: [downloaded / total]   [speed]   [ETA]
        StringBuilder content = new StringBuilder();
        if (activeCount > 0) {
            if (activeCount == 1) {
                // Formatting for a single active download: "1.2 MB / 10 MB  •  2.5 MB/s  •  ~5s left"
                String progressStr = "";
                if (firstTotal > 0) {
                    progressStr = DownloadItem.formatBytes(firstDownloaded) + " / " + DownloadItem.formatBytes(firstTotal);
                } else if (firstDownloaded > 0) {
                    progressStr = DownloadItem.formatBytes(firstDownloaded);
                }

                if (!progressStr.isEmpty()) content.append(progressStr);

                if (totalSpeed > 0) {
                    if (content.length() > 0) content.append("  •  ");
                    content.append(formatSpeed(totalSpeed));
                }

                if (!firstEta.isEmpty()) {
                    if (content.length() > 0) content.append("  •  ");
                    content.append("~").append(firstEta).append(" left");
                }
            } else {
                // Multiple downloads formatting
                if (totalSpeed > 0) content.append(formatSpeed(totalSpeed));
                if (content.length() > 0) content.append("  •  ");
                content.append(activeCount).append(" files");
                
                if (!firstEta.isEmpty()) {
                    if (content.length() > 0) content.append("  •  ");
                    content.append("~").append(firstEta).append(" left");
                }
            }
        }

        // Open app intent
        Intent openIntent = new Intent(this, DownloadManagerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("open_tab", "downloads");
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_arrow)
                .setContentTitle(activeCount > 1
                        ? "Downloading " + activeCount + " files"
                        : "📥 " + firstName)
                .setContentText(content.toString())
                .setContentIntent(openPi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);

        if (activeCount > 0 && firstTotal > 0) {
            builder.setProgress(100, firstProgress, false);
            builder.setSubText(firstProgress + "%");
        } else if (activeCount > 0) {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void showDoneNotification(DownloadItem item) {
        Intent openIntent = new Intent(this, DownloadManagerActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        openIntent.putExtra("open_tab", "files");
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_DONE_ID)
                .setSmallIcon(R.drawable.ic_check_circle)
                .setContentTitle("✓ Download complete")
                .setContentText(item.fileName)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .build();

        notifManager.notify(NOTIF_DONE_BASE + (doneCounter++), notif);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String formatSpeed(long bps) {
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024));
    }
}
