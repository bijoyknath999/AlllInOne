package com.allinone.app;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivityYtDownloaderBinding;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import kotlin.Unit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.widget.ScrollView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class YtDownloaderActivity extends AppCompatActivity {

    private static final String PROCESS_ID = "yt_download_task";
    private static final int REQ_STORAGE_PERM = 101;

    // Fixed MP3 quality labels
    private static final String[] MP3_QUALITIES = {"128 kbps", "192 kbps", "320 kbps"};

    private ActivityYtDownloaderBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private boolean isMp3Selected = true;
    private int selectedQuality = 2;
    private boolean isDownloading = false;
    private boolean isCancelled = false;
    private boolean isInitialized = false;

    // All available heights shown in chips. -1 = "Best Available" (no height cap).
    private List<Integer> displayedHeights = new ArrayList<>(Arrays.asList(720, 1080, -1));
    // Chip views built dynamically
    private final List<TextView> qualityChips = new ArrayList<>();

    // Download progress dialog
    private Dialog downloadDialog;
    private TextView dialogTvStatus;
    private TextView dialogTvPercent;
    private View     dialogProgressFill;
    private TextView dialogTvLog;
    private ScrollView dialogScrollLog;
    private final StringBuilder logBuffer = new StringBuilder();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityYtDownloaderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        int originalTop = binding.toolbar.getPaddingTop();
        int originalBottom = binding.toolbar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.ytRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.toolbar.setPadding(
                    bars.left, bars.top + originalTop, bars.right, originalBottom);
            return insets;
        });

        setupUI();
        initYtDlp();
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });

        binding.btnPaste.setOnClickListener(v -> pasteFromClipboard());

        // "Get Video" button — fetches info + shows format/quality/download
        binding.btnGetVideo.setOnClickListener(v -> fetchVideoInfo());

        // Format chips (only clickable after fetch)
        binding.chipMp3.setOnClickListener(v -> selectFormat(true));
        binding.chipMp4.setOnClickListener(v -> selectFormat(false));

        // Download button
        binding.btnDownload.setOnClickListener(v -> {
            if (!isDownloading) checkPermissionAndDownload();
        });

        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.ytRoot.startAnimation(slideUp);
    }

    private void initYtDlp() {
        setGetVideoButtonState(false, "Initialising…");
        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());

                runOnUiThread(() -> setGetVideoButtonState(false, "Updating yt-dlp…"));
                YoutubeDL.getInstance().updateYoutubeDL(
                        getApplication(), YoutubeDL.UpdateChannel._STABLE);

                isInitialized = true;
                runOnUiThread(() -> setGetVideoButtonState(true, "Get Video"));
            } catch (Exception e) {
                android.util.Log.e("YtDlp", "init/update failed", e);
                runOnUiThread(() -> {
                    isInitialized = true;
                    setGetVideoButtonState(true, "Get Video");
                    Toast.makeText(this, "yt-dlp update failed (using bundled)",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /** Enables/disables the Get Video button and updates its label. */
    private void setGetVideoButtonState(boolean enabled, String label) {
        binding.btnGetVideo.setAlpha(enabled ? 1f : 0.6f);
        binding.btnGetVideo.setClickable(enabled);
        binding.tvGetVideo.setText(label);
        binding.pbGetVideo.setVisibility(enabled ? View.GONE : View.VISIBLE);
        binding.ivGetVideoIcon.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void pasteFromClipboard() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                && cb.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cb.getPrimaryClip().getItemAt(0).getText();
            if (text != null) {
                binding.etUrl.setText(text.toString().trim());
                binding.etUrl.setSelection(binding.etUrl.getText().length());
            }
        }
    }

    // ── Format & Quality Selection ─────────────────────────────────────────────

    private void selectFormat(boolean mp3) {
        isMp3Selected = mp3;

        if (mp3) {
            binding.chipMp3.setBackground(getDrawable(R.drawable.bg_chip_selected));
            binding.tvChipMp3.setTextColor(getColor(R.color.color_primary));
            binding.chipMp4.setBackground(getDrawable(R.drawable.bg_chip_unselected));
            binding.tvChipMp4.setTextColor(getColor(R.color.text_secondary));
            selectedQuality = 2; // default to 320 kbps
        } else {
            binding.chipMp4.setBackground(getDrawable(R.drawable.bg_chip_selected));
            binding.tvChipMp4.setTextColor(getColor(R.color.color_primary));
            binding.chipMp3.setBackground(getDrawable(R.drawable.bg_chip_unselected));
            binding.tvChipMp3.setTextColor(getColor(R.color.text_secondary));
            selectedQuality = displayedHeights.size() - 1; // default to Best
        }
        updateQualityUI();
    }

    private void selectQuality(int q) {
        selectedQuality = q;
        updateQualityChipStyles();
    }

    /** Rebuilds and refreshes all quality chips based on current format. */
    private void updateQualityUI() {
        List<String> labels = new ArrayList<>();
        if (isMp3Selected) {
            binding.tvQualityLabel.setText("Audio Quality");
            for (String s : MP3_QUALITIES) labels.add(s);
        } else {
            binding.tvQualityLabel.setText("Video Quality");
            for (int h : displayedHeights) labels.add(labelForHeight(h));
        }
        buildQualityChips(labels);
        if (selectedQuality >= qualityChips.size()) selectedQuality = qualityChips.size() - 1;
        updateQualityChipStyles();
    }

    /**
     * Clears ll_quality_chips and creates new chip TextViews, 3 per row.
     * Each chip has the same style as the old static chips.
     */
    private void buildQualityChips(List<String> labels) {
        binding.llQualityChips.removeAllViews();
        qualityChips.clear();

        int count = labels.size();
        int chipsPerRow = 3;

        for (int start = 0; start < count; start += chipsPerRow) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (start > 0) rowParams.topMargin = dpToPx(8);
            row.setLayoutParams(rowParams);

            int end = Math.min(start + chipsPerRow, count);
            for (int i = start; i < end; i++) {
                final int idx = i;
                TextView chip = new TextView(this);
                chip.setText(labels.get(i));
                chip.setTextSize(13);
                chip.setGravity(android.view.Gravity.CENTER);

                int hPad = dpToPx(8);
                int vPad = dpToPx(12);
                chip.setPadding(hPad, vPad, hPad, vPad);
                chip.setClickable(true);
                chip.setFocusable(true);

                LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (i < end - 1) chipParams.rightMargin = dpToPx(8);
                chip.setLayoutParams(chipParams);

                chip.setOnClickListener(v -> selectQuality(idx));
                qualityChips.add(chip);
                row.addView(chip);
            }
            binding.llQualityChips.addView(row);
        }
    }

    private void updateQualityChipStyles() {
        for (int i = 0; i < qualityChips.size(); i++) {
            TextView chip = qualityChips.get(i);
            if (i == selectedQuality) {
                chip.setBackground(getDrawable(R.drawable.bg_chip_selected));
                chip.setTextColor(getColor(R.color.color_primary));
                chip.setTypeface(null, android.graphics.Typeface.BOLD);
            } else {
                chip.setBackground(getDrawable(R.drawable.bg_chip_unselected));
                chip.setTextColor(getColor(R.color.text_secondary));
                chip.setTypeface(null, android.graphics.Typeface.NORMAL);
            }
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Returns a display label for a height value. -1 means "Best". */
    private String labelForHeight(int height) {
        if (height < 0)    return "Best";
        if (height >= 2160) return "4K";
        if (height >= 1440) return "2K";
        return height + "p";
    }

    // ── Fetch Available Qualities ──────────────────────────────────────────────

    /**
     * Triggered by "Get Video" button.
     * Calls yt-dlp -J to fetch title, thumbnail, and all available resolutions,
     * then reveals the format / quality / download section.
     */
    private void fetchVideoInfo() {
        String url = binding.etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url) || !isValidYouTubeUrl(url)) {
            showError("Enter a valid YouTube URL first.");
            return;
        }
        if (!isInitialized) {
            showError(getString(R.string.yt_initializing));
            return;
        }

        // Hide previous results while re-fetching
        binding.llContent.setVisibility(View.GONE);
        binding.llVideoInfo.setVisibility(View.GONE);
        binding.llResult.setVisibility(View.GONE);
        setGetVideoButtonState(false, "Fetching…");

        executor.execute(() -> {
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("-J");
                request.addOption("--no-playlist");
                request.addOption("--no-warnings");

                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, null);
                String jsonStr = response.getOut();

                // Parse title + thumbnail
                String videoTitle = "";
                String thumbnailUrl = "";
                try {
                    JSONObject info = new JSONObject(jsonStr);
                    videoTitle   = info.optString("title", "");
                    thumbnailUrl = info.optString("thumbnail", "");
                } catch (JSONException ignored) {}

                // Parse MP4 heights
                List<Integer> heights = parseAvailableHeights(jsonStr);
                if (!heights.isEmpty()) heights.add(-1); // append "Best"
                if (heights.isEmpty())  heights = new ArrayList<>(Arrays.asList(720, 1080, -1));

                final List<Integer> finalHeights       = heights;
                final String        finalTitle         = videoTitle;
                final String        finalThumbnailUrl  = thumbnailUrl;

                runOnUiThread(() -> {
                    // Store heights for MP4 quality chips
                    displayedHeights = finalHeights;

                    // Show video info card
                    binding.tvVideoTitle.setText(
                            TextUtils.isEmpty(finalTitle) ? "Unknown title" : finalTitle);
                    binding.llVideoInfo.setVisibility(View.VISIBLE);
                    if (!TextUtils.isEmpty(finalThumbnailUrl)) loadThumbnail(finalThumbnailUrl);

                    // Default: MP3 selected, 320 kbps
                    isMp3Selected = true;
                    selectedQuality = 2;
                    binding.chipMp3.setBackground(getDrawable(R.drawable.bg_chip_selected));
                    binding.tvChipMp3.setTextColor(getColor(R.color.color_primary));
                    binding.chipMp4.setBackground(getDrawable(R.drawable.bg_chip_unselected));
                    binding.tvChipMp4.setTextColor(getColor(R.color.text_secondary));

                    // Build quality chips and show the full content section
                    updateQualityUI();
                    binding.llContent.setVisibility(View.VISIBLE);
                    Animation anim = AnimationUtils.loadAnimation(
                            YtDownloaderActivity.this, R.anim.slide_up_fade_in);
                    binding.llContent.startAnimation(anim);

                    setGetVideoButtonState(true, "Get Video");

                    int resCount = finalHeights.size() - 1;
                    Toast.makeText(this,
                            "Found " + resCount + " video resolution(s)",
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    setGetVideoButtonState(true, "Get Video");
                    showError("Could not get video info: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Parses yt-dlp JSON to extract all distinct heights of video-only formats.
     * Returns a sorted ascending list (e.g. [360, 480, 720, 1080]).
     */
    private List<Integer> parseAvailableHeights(String json) {
        TreeSet<Integer> heights = new TreeSet<>();
        try {
            JSONObject obj     = new JSONObject(json);
            JSONArray  formats = obj.getJSONArray("formats");
            for (int i = 0; i < formats.length(); i++) {
                JSONObject fmt = formats.getJSONObject(i);
                if (!fmt.has("height") || fmt.isNull("height")) continue;
                int h = fmt.getInt("height");
                if (h <= 0) continue;

                // Only video formats (vcodec present and not "none")
                String vcodec = fmt.optString("vcodec", "none");
                if (vcodec.equals("none") || TextUtils.isEmpty(vcodec)) continue;

                // Skip audio-only tracks that sometimes have a "height" of 0 or very small
                if (h < 144) continue;

                heights.add(h);
            }
        } catch (JSONException e) {
            // JSON parse error — return empty list, caller will use defaults
        }
        return new ArrayList<>(heights); // ascending
    }


    // ── Permission & Download Flow ─────────────────────────────────────────────

    private void checkPermissionAndDownload() {
        String url = binding.etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url))       { showError(getString(R.string.yt_error_empty_url)); return; }
        if (!isValidYouTubeUrl(url))      { showError(getString(R.string.yt_error_invalid_url)); return; }
        if (!isInitialized)               { showError(getString(R.string.yt_initializing)); return; }

        // API 29+ uses MediaStore — no WRITE permission needed
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_STORAGE_PERM);
                return;
            }
        }
        startDownload();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_STORAGE_PERM) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload();
            } else {
                showError("Storage permission is required to save files to the Downloads folder.");
            }
        }
    }

    private void startDownload() {
        String url = binding.etUrl.getText().toString().trim();
        isDownloading = true;
        isCancelled   = false;
        showDownloadDialog();
        hideResultUI();
        setDownloadButtonEnabled(false);

        // Download to app cache first, then move to public Downloads
        File tempDir = new File(getCacheDir(), "ytdl_temp");
        if (tempDir.exists()) {
            File[] old = tempDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        tempDir.mkdirs();

        executor.execute(() -> performDownload(url, tempDir));
    }

    private void performDownload(String url, File tempDir) {
        try {
            YoutubeDLRequest request = new YoutubeDLRequest(url);

            if (isMp3Selected) {
                // ── Audio only → MP3 ──────────────────────────────────────────
                // bestaudio format, then post-process to mp3 via ffmpeg
                request.addOption("-f", "bestaudio");
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                switch (selectedQuality) {
                    case 0: request.addOption("--audio-quality", "128K"); break;
                    case 1: request.addOption("--audio-quality", "192K"); break;
                    default: request.addOption("--audio-quality", "320K"); break;
                }
            } else {
                // ── Video + Audio → MP4 (merge via ffmpeg) ────────────────────
                int targetHeight = displayedHeights.get(selectedQuality);
                String fmt;
                if (targetHeight < 0) {
                    fmt = "bestvideo+bestaudio/best";
                } else {
                    fmt = "bestvideo[height<=" + targetHeight + "]+bestaudio"
                        + "/best[height<=" + targetHeight + "]";
                }
                request.addOption("-f", fmt);
                request.addOption("--merge-output-format", "mp4");
                request.addOption("--recode-video", "mp4");
            }

            request.addOption("-o", tempDir.getAbsolutePath() + "/%(title)s.%(ext)s");
            request.addOption("--no-playlist");
            request.addOption("--ffmpeg-location",
                    getApplicationInfo().nativeLibraryDir);

            runOnUiThread(() -> updateStatus(getString(R.string.yt_status_downloading), 0, ""));

            YoutubeDL.getInstance().execute(request, PROCESS_ID, (progress, eta, line) -> {
                if (!isCancelled) {
                    runOnUiThread(() -> updateStatus(
                            getString(R.string.yt_status_downloading),
                            progress.intValue(),
                            line != null ? line.trim() : ""));
                }
                return Unit.INSTANCE;
            });

            if (!isCancelled) {
                runOnUiThread(() -> updateStatus("Saving to Downloads…", 99, "Copying file…"));
                String savedPath = moveToPublicDownloads(tempDir);
                runOnUiThread(() -> {
                    dismissDownloadDialog();
                    showResultUI(savedPath);
                    setDownloadButtonEnabled(true);
                    isDownloading = false;
                });
            }

        } catch (YoutubeDL.CanceledException ignored) {
            // User cancelled — dialog already dismissed in cancelDownload()
        } catch (YoutubeDLException | InterruptedException | IOException e) {
            if (!isCancelled) {
                final String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runOnUiThread(() -> {
                    dismissDownloadDialog();
                    setDownloadButtonEnabled(true);
                    isDownloading = false;
                    showError(getString(R.string.yt_status_error) + msg);
                });
            }
        }
    }

    // ── Save to Public Downloads ───────────────────────────────────────────────

    /**
     * Moves all files from tempDir to public Downloads/AllInOne.
     * API 29+: MediaStore (no permission needed).
     * API 28:  direct file copy (WRITE_EXTERNAL_STORAGE already granted).
     */
    private String moveToPublicDownloads(File tempDir) throws IOException {
        File[] files = tempDir.listFiles();
        if (files == null || files.length == 0) return "Downloads/AllInOne";

        String savedPath = "Downloads/AllInOne";

        for (File file : files) {
            if (!file.isFile()) continue;
            String name     = file.getName();
            String mimeType = name.endsWith(".mp3") ? "audio/mpeg" : "video/mp4";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE,    mimeType);
                cv.put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/AllInOne");

                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream os = resolver.openOutputStream(uri);
                         InputStream  is = new FileInputStream(file)) {
                        copyStream(is, os);
                    }
                    savedPath = "Downloads/AllInOne/" + name;
                }
            } else {
                File destDir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        "AllInOne");
                if (!destDir.exists()) destDir.mkdirs();
                File dest = new File(destDir, name);
                try (InputStream  is = new FileInputStream(file);
                     OutputStream os = new FileOutputStream(dest)) {
                    copyStream(is, os);
                }
                savedPath = dest.getAbsolutePath();
            }
            file.delete();
        }
        return savedPath;
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
    }

    /** Loads a thumbnail URL in the background and sets it on iv_thumbnail. */
    private void loadThumbnail(String urlStr) {
        executor.execute(() -> {
            try {
                Bitmap bmp = BitmapFactory.decodeStream(new URL(urlStr).openStream());
                if (bmp != null) {
                    runOnUiThread(() -> binding.ivThumbnail.setImageBitmap(bmp));
                }
            } catch (Exception ignored) {}
        });
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    private void cancelDownload() {
        isCancelled   = true;
        isDownloading = false;
        try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID); } catch (Exception ignored) {}
        dismissDownloadDialog();
        setDownloadButtonEnabled(true);
        Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show();
    }

    private void showDownloadDialog() {
        logBuffer.setLength(0);

        android.view.View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_download_progress, null);

        dialogTvStatus     = dialogView.findViewById(R.id.dialog_tv_status);
        dialogTvPercent    = dialogView.findViewById(R.id.dialog_tv_percent);
        dialogProgressFill = dialogView.findViewById(R.id.dialog_progress_fill);
        dialogTvLog        = dialogView.findViewById(R.id.dialog_tv_log);
        dialogScrollLog    = dialogView.findViewById(R.id.dialog_scroll_log);

        dialogView.findViewById(R.id.dialog_btn_cancel)
                .setOnClickListener(v -> cancelDownload());

        downloadDialog = new Dialog(this, android.R.style.Theme_Material_Dialog);
        downloadDialog.setContentView(dialogView);
        downloadDialog.setCancelable(false);

        if (downloadDialog.getWindow() != null) {
            downloadDialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            downloadDialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        downloadDialog.show();
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null && downloadDialog.isShowing()) {
            downloadDialog.dismiss();
        }
        downloadDialog = null;
    }

    private void updateStatus(String status, int progress, String logLine) {
        if (downloadDialog == null || !downloadDialog.isShowing()) return;

        dialogTvStatus.setText(status);
        dialogTvPercent.setText(progress + "%");

        // Append non-empty log lines
        if (logLine != null && !logLine.isEmpty()) {
            if (logBuffer.length() > 0) logBuffer.append("\n");
            logBuffer.append(logLine);
            dialogTvLog.setText(logBuffer.toString());
            // Auto-scroll to bottom
            dialogScrollLog.post(() -> dialogScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        }

        // Update progress bar width after layout pass
        dialogProgressFill.post(() -> {
            int totalWidth = ((View) dialogProgressFill.getParent()).getWidth();
            ViewGroup.LayoutParams p = dialogProgressFill.getLayoutParams();
            p.width = (int) (totalWidth * progress / 100f);
            dialogProgressFill.setLayoutParams(p);
        });
    }

    private void showResultUI(String path) {
        binding.llResult.setVisibility(View.VISIBLE);
        binding.tvSavedPath.setText(getString(R.string.yt_saved_to) + path);
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.llResult.startAnimation(anim);
    }

    private void hideResultUI() {
        binding.llResult.setVisibility(View.GONE);
    }

    private void setDownloadButtonEnabled(boolean enabled) {
        binding.btnDownload.setAlpha(enabled ? 1.0f : 0.55f);
        binding.btnDownload.setClickable(enabled);
    }

    private void showError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private boolean isValidYouTubeUrl(String url) {
        return url.contains("youtube.com/") || url.contains("youtu.be/");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isDownloading) {
            isCancelled = true;
            try { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID); } catch (Exception ignored) {}
        }
        dismissDownloadDialog();
        executor.shutdownNow();
    }
}
