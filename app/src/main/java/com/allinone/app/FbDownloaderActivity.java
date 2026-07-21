package com.allinone.app;

import android.Manifest;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivityFbDownloaderBinding;
import com.allinone.app.fb.FbCookieStore;
import com.allinone.app.fb.FbStream;
import com.allinone.app.fb.FbStreamDownloader;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kotlin.Unit;

/**
 * Facebook video downloader — same flow and styling as {@link YtDownloaderActivity},
 * with one addition: an in-app Facebook login.
 *
 * <p>Two download paths. Public videos go through yt-dlp with a pasted URL. Private,
 * group and friends-only videos cannot: Facebook fingerprints non-browser clients and
 * serves yt-dlp a stripped page, which surfaces as "Cannot parse data" even with a valid
 * session (yt-dlp's own fix, {@code --impersonate}, needs curl_cffi, which the Android
 * runtime does not ship). For those, {@link FbBrowserActivity} plays the video in a real
 * WebView and captures the fbcdn stream URLs, which we then download and mux directly.
 */
public class FbDownloaderActivity extends AppCompatActivity {

    private static final String PROCESS_ID = "fb_download_task";
    private static final int REQ_STORAGE_PERM = 102;

    private static final String[] MP3_QUALITIES = {"128 kbps", "192 kbps", "320 kbps"};

    /** {@code /groups/<anything>/videos/<id>} — a form yt-dlp's Facebook regex misses. */
    private static final java.util.regex.Pattern GROUP_VIDEO = java.util.regex.Pattern.compile(
            "(?i)facebook\\.com/groups/[^/]+/videos/(\\d+)");

    private ActivityFbDownloaderBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Facebook is video-first, so MP4 is the default here (YT defaults to MP3).
    private boolean isMp3Selected = false;
    private int selectedQuality = 2;
    private boolean isDownloading = false;
    private boolean isCancelled = false;
    private boolean isInitialized = false;

    /** Heights shown in the chips. -1 = "Best Available" (no height cap). */
    private List<Integer> displayedHeights = new ArrayList<>(Arrays.asList(360, 720, -1));
    private final List<TextView> qualityChips = new ArrayList<>();

    /**
     * Renditions sniffed off fbcdn by the in-app browser. When non-empty we download these
     * directly and ignore yt-dlp entirely — see {@link #performStreamDownload}.
     */
    private final List<FbStream> capturedStreams = new ArrayList<>();
    /** Video renditions from {@link #capturedStreams}, best-first, one per height. */
    private final List<FbStream> streamVideoChoices = new ArrayList<>();

    // Download progress dialog
    private Dialog downloadDialog;
    private TextView dialogTvStatus;
    private TextView dialogTvPercent;
    private View     dialogProgressFill;
    private TextView dialogTvLog;
    private ScrollView dialogScrollLog;
    private final StringBuilder logBuffer = new StringBuilder();

    /** Set when a merge fails, shown once the download finishes so it cannot be missed. */
    private String lastMuxReport;

    /** Opens the in-app Facebook browser; may return the URL the user picked. */
    private final ActivityResultLauncher<Intent> browserLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                refreshAccountUI();
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;

                String url = result.getData().getStringExtra(FbBrowserActivity.EXTRA_URL);
                if (!TextUtils.isEmpty(url)) {
                    binding.etUrl.setText(url);
                    binding.etUrl.setSelection(binding.etUrl.getText().length());
                }

                // Streams sniffed straight off fbcdn beat anything yt-dlp can extract.
                List<String> encoded =
                        result.getData().getStringArrayListExtra(FbBrowserActivity.EXTRA_STREAMS);
                capturedStreams.clear();
                if (encoded != null) {
                    for (String e : encoded) {
                        FbStream s = FbStream.decode(e);
                        if (s != null) capturedStreams.add(s);
                    }
                }

                if (hasCapturedVideo()) {
                    showCapturedStreams();
                } else if (!TextUtils.isEmpty(url)) {
                    fetchVideoInfo();
                }
            });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityFbDownloaderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        int originalTop = binding.toolbar.getPaddingTop();
        int originalBottom = binding.toolbar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.fbRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.toolbar.setPadding(
                    bars.left, bars.top + originalTop, bars.right, originalBottom);
            return insets;
        });

        setupUI();
        refreshAccountUI();
        initYtDlp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAccountUI();
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private void setupUI() {
        binding.btnBack.setOnClickListener(v -> {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });

        binding.btnPaste.setOnClickListener(v -> pasteFromClipboard());
        // A hand-typed URL has no captures behind it — fall back to the yt-dlp path. Keyed
        // on an actual edit, not on focus: returning from the browser can auto-focus this
        // field, which would silently discard the streams we just came back with while the
        // quality chips stayed on screen.
        binding.etUrl.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                if (binding.etUrl.hasFocus() && !streamVideoChoices.isEmpty()) {
                    exitCapturedMode();
                }
            }
        });
        binding.btnGetVideo.setOnClickListener(v -> fetchVideoInfo());
        binding.btnBrowse.setOnClickListener(v -> openBrowser(null));
        binding.tvAccountAction.setOnClickListener(v -> {
            if (FbCookieStore.hasUsableSession()) confirmLogout(); else openBrowser(null);
        });

        binding.chipMp3.setOnClickListener(v -> selectFormat(true));
        binding.chipMp4.setOnClickListener(v -> selectFormat(false));

        binding.btnDownload.setOnClickListener(v -> {
            if (!isDownloading) checkPermissionAndDownload();
        });

        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.fbRoot.startAnimation(slideUp);
    }

    private void initYtDlp() {
        setGetVideoButtonState(false, "Initialising…");
        executor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());

                runOnUiThread(() -> setGetVideoButtonState(false, "Updating yt-dlp…"));
                // Nightly, not stable: the Facebook extractor is one of the fastest-churning
                // ones and stable lags its fixes by weeks.
                YoutubeDL.getInstance().updateYoutubeDL(
                        getApplication(), YoutubeDL.UpdateChannel._NIGHTLY);

                isInitialized = true;

                // Facebook's extractor breaks often, so the running yt-dlp version is the
                // first thing to check when extraction fails. Show it in the subtitle.
                String version;
                try {
                    version = YoutubeDL.getInstance().version(this);
                } catch (Exception ignored) {
                    version = null;
                }
                final String shown = TextUtils.isEmpty(version)
                        ? "yt-dlp powered" : "yt-dlp " + version;

                runOnUiThread(() -> {
                    binding.tvEngine.setText(shown);
                    setGetVideoButtonState(true, getString(R.string.fb_btn_get_video));
                });
            } catch (Exception e) {
                android.util.Log.e("FbDlp", "init/update failed", e);
                runOnUiThread(() -> {
                    isInitialized = true;
                    setGetVideoButtonState(true, getString(R.string.fb_btn_get_video));
                    Toast.makeText(this, "yt-dlp update failed (using bundled)",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

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

    // ── Facebook account ──────────────────────────────────────────────────────

    /** Opens the in-app Facebook browser, optionally at a specific URL. */
    private void openBrowser(String startUrl) {
        Intent i = new Intent(this, FbBrowserActivity.class);
        if (!TextUtils.isEmpty(startUrl)) {
            i.putExtra(FbBrowserActivity.EXTRA_START_URL, startUrl);
        }
        browserLauncher.launch(i);
        overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.fb_logout_title)
                .setMessage(R.string.fb_logout_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.fb_account_logout, (d, w) -> {
                    FbCookieStore.clear(this);
                    refreshAccountUI();
                    Toast.makeText(this, R.string.fb_logged_out, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** Syncs the account card with the current WebView cookie state. */
    private void refreshAccountUI() {
        boolean loggedIn = FbCookieStore.hasUsableSession();
        if (loggedIn) {
            binding.ivAccountIcon.setImageResource(R.drawable.ic_check_circle);
            binding.ivAccountIcon.setColorFilter(Color.parseColor("#4CAF50"));
            binding.tvAccountState.setText(R.string.fb_account_logged_in);
            binding.tvAccountHint.setText(R.string.fb_account_logged_in_hint);
            binding.tvAccountAction.setText(R.string.fb_account_logout);
        } else {
            binding.ivAccountIcon.setImageResource(R.drawable.ic_lock);
            binding.ivAccountIcon.setColorFilter(getColor(R.color.text_secondary));
            binding.tvAccountState.setText(R.string.fb_account_logged_out);
            binding.tvAccountHint.setText(R.string.fb_account_logged_out_hint);
            binding.tvAccountAction.setText(R.string.fb_account_login);
        }
    }

    /**
     * Attaches the exported Facebook session to a yt-dlp request.
     *
     * <p>Deliberately does NOT override the User-Agent. yt-dlp defaults to a desktop UA and
     * its Facebook extractor parses the desktop page markup; forcing the in-app browser's
     * mobile UA makes Facebook serve the mobile layout, which the extractor cannot read
     * ("Cannot parse data"). The session cookies work across both layouts.
     */
    private void applyCookies(YoutubeDLRequest request) {
        File cookies = FbCookieStore.existingCookieFile(this);
        if (cookies != null) {
            request.addOption("--cookies", cookies.getAbsolutePath());
        }
        request.addOption("--referer", "https://www.facebook.com/");
    }

    /**
     * Rewrites mobile/regional Facebook hosts to {@code www.facebook.com}.
     *
     * <p>yt-dlp's Facebook extractor parses the desktop page markup. A URL copied from the
     * in-app browser is an {@code m.facebook.com} one, and extraction against the mobile
     * page usually fails even with a perfectly valid session.
     */
    static String normalizeFbUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        String u = url.trim();
        u = u.replaceFirst("(?i)^https?://(m|mbasic|touch|web|free|d)\\.facebook\\.com/",
                "https://www.facebook.com/");
        u = u.replaceFirst("(?i)^https?://facebook\\.com/", "https://www.facebook.com/");

        // yt-dlp's _VALID_URL covers /groups/<x>/posts|permalink/<id> but NOT
        // /groups/<x>/videos/<id> — that form fails to match any extractor at all.
        // The canonical /watch/?v=<id> form always matches, so rewrite to it.
        Matcher gm = GROUP_VIDEO.matcher(u);
        if (gm.find()) {
            return "https://www.facebook.com/watch/?v=" + gm.group(1);
        }

        // Strip Facebook's tracking/pagination noise, which can confuse the extractor.
        u = u.replaceAll("(?i)[?&](__cft__(\\[\\d+])?|__tn__|comment_id|reply_comment_id|notif_id|notif_t|ref|refid|rdid|share_url)=[^&]*", "");
        u = u.replaceAll("\\?&", "?").replaceAll("[?&]$", "");
        return u;
    }

    // ── Captured-stream mode ───────────────────────────────────────────────────

    private boolean hasCapturedVideo() {
        for (FbStream s : capturedStreams) if (!s.isAudio) return true;
        return false;
    }

    /** Highest-bitrate audio track we saw, or null if the player never fetched one. */
    private FbStream bestAudioStream() {
        for (FbStream s : capturedStreams) if (s.isAudio) return s;
        return null;
    }

    /**
     * Switches the UI into captured-stream mode: quality chips come from the renditions we
     * actually saw on the wire, and the MP3/MP4 format chips are hidden — a sniffed stream
     * is whatever Facebook served, so there is no format choice to make.
     */
    private void showCapturedStreams() {
        // One entry per height, tallest first.
        Map<Integer, FbStream> byHeight = new LinkedHashMap<>();
        for (FbStream s : capturedStreams) {
            if (s.isAudio) continue;
            Integer key = s.height > 0 ? s.height : 0;
            if (!byHeight.containsKey(key)) byHeight.put(key, s);
        }
        streamVideoChoices.clear();
        streamVideoChoices.addAll(byHeight.values());
        Collections.sort(streamVideoChoices, (a, b) -> Integer.compare(b.height, a.height));

        binding.tvFormatLabel.setVisibility(View.GONE);
        binding.llFormatRow.setVisibility(View.GONE);
        binding.tvQualityLabel.setText("Video Quality");

        List<String> labels = new ArrayList<>();
        for (FbStream s : streamVideoChoices) labels.add(s.label());
        buildQualityChips(labels);
        selectedQuality = 0; // tallest
        updateQualityChipStyles();

        // Pre-muxed files carry their own audio, so a missing audio track is only a problem
        // for sniffed DASH renditions.
        boolean allMuxed = true;
        for (FbStream s : streamVideoChoices) if (!s.muxed) { allMuxed = false; break; }

        // Report what actually arrived from the browser, not just what is playable. When
        // the browser says it sent audio and this says it received none, the loss is in the
        // hand-off — and that is invisible if the UI only ever shows a quality list.
        int gotVideo = 0, gotAudio = 0;
        for (FbStream s : capturedStreams) {
            if (s.isAudio) gotAudio++; else gotVideo++;
        }
        android.util.Log.i("FbDl", "received " + gotVideo + " video + " + gotAudio
                + " audio track(s) from browser");

        binding.tvVideoTitle.setText(!allMuxed && bestAudioStream() == null
                ? getString(R.string.fb_captured_video_only)
                : getString(R.string.fb_captured_tracks, gotVideo, gotAudio));
        binding.llVideoInfo.setVisibility(View.VISIBLE);
        binding.ivThumbnail.setVisibility(View.GONE);

        binding.llContent.setVisibility(View.VISIBLE);
        binding.llContent.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in));
        binding.llResult.setVisibility(View.GONE);
    }

    /** Restores the normal yt-dlp-driven UI. */
    private void exitCapturedMode() {
        capturedStreams.clear();
        streamVideoChoices.clear();
        binding.tvFormatLabel.setVisibility(View.VISIBLE);
        binding.llFormatRow.setVisibility(View.VISIBLE);
        binding.ivThumbnail.setVisibility(View.VISIBLE);
    }

    // ── Format & Quality Selection ─────────────────────────────────────────────

    private void selectFormat(boolean mp3) {
        isMp3Selected = mp3;

        if (mp3) {
            binding.chipMp3.setBackground(getDrawable(R.drawable.bg_chip_selected));
            binding.tvChipMp3.setTextColor(getColor(R.color.color_primary));
            binding.tvChipMp3.setTypeface(null, android.graphics.Typeface.BOLD);
            binding.chipMp4.setBackground(getDrawable(R.drawable.bg_chip_unselected));
            binding.tvChipMp4.setTextColor(getColor(R.color.text_secondary));
            binding.tvChipMp4.setTypeface(null, android.graphics.Typeface.NORMAL);
            selectedQuality = 2; // default to 320 kbps
        } else {
            binding.chipMp4.setBackground(getDrawable(R.drawable.bg_chip_selected));
            binding.tvChipMp4.setTextColor(getColor(R.color.color_primary));
            binding.tvChipMp4.setTypeface(null, android.graphics.Typeface.BOLD);
            binding.chipMp3.setBackground(getDrawable(R.drawable.bg_chip_unselected));
            binding.tvChipMp3.setTextColor(getColor(R.color.text_secondary));
            binding.tvChipMp3.setTypeface(null, android.graphics.Typeface.NORMAL);
            selectedQuality = displayedHeights.size() - 1; // default to Best
        }
        updateQualityUI();
    }

    private void selectQuality(int q) {
        selectedQuality = q;
        updateQualityChipStyles();
    }

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

    /** Clears ll_quality_chips and creates new chip TextViews, 3 per row. */
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

    private String labelForHeight(int height) {
        if (height < 0)     return "Best";
        if (height >= 2160) return "4K";
        if (height >= 1440) return "2K";
        return height + "p";
    }

    // ── Fetch Available Qualities ──────────────────────────────────────────────

    /**
     * Runs {@code yt-dlp -J} (with cookies when available) to fetch title, thumbnail and
     * every available resolution, then reveals the format / quality / download section.
     */
    private void fetchVideoInfo() {
        String url = normalizeFbUrl(binding.etUrl.getText().toString().trim());
        if (TextUtils.isEmpty(url) || !isValidFacebookUrl(url)) {
            showError(getString(R.string.fb_error_invalid_url));
            return;
        }
        if (!isInitialized) {
            showError(getString(R.string.yt_initializing));
            return;
        }
        // Show the user the exact URL we hand to yt-dlp.
        binding.etUrl.setText(url);

        binding.llContent.setVisibility(View.GONE);
        binding.llVideoInfo.setVisibility(View.GONE);
        binding.llResult.setVisibility(View.GONE);
        setGetVideoButtonState(false, "Fetching…");

        executor.execute(() -> {
            try {
                YoutubeDLRequest request = new YoutubeDLRequest(url);
                request.addOption("-J");
                request.addOption("--no-playlist");
                applyCookies(request);

                YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, null);
                String jsonStr = response.getOut();

                String videoTitle = "";
                String thumbnailUrl = "";
                try {
                    JSONObject info = new JSONObject(jsonStr);
                    videoTitle   = info.optString("title", "");
                    thumbnailUrl = info.optString("thumbnail", "");
                } catch (JSONException ignored) {}

                List<Integer> heights = parseAvailableHeights(jsonStr);
                if (!heights.isEmpty()) heights.add(-1); // append "Best"
                if (heights.isEmpty())  heights = new ArrayList<>(Arrays.asList(360, 720, -1));

                final List<Integer> finalHeights      = heights;
                final String        finalTitle        = videoTitle;
                final String        finalThumbnailUrl = thumbnailUrl;

                runOnUiThread(() -> {
                    displayedHeights = finalHeights;

                    binding.tvVideoTitle.setText(
                            TextUtils.isEmpty(finalTitle) ? "Unknown title" : finalTitle);
                    binding.llVideoInfo.setVisibility(View.VISIBLE);
                    if (!TextUtils.isEmpty(finalThumbnailUrl)) loadThumbnail(finalThumbnailUrl);

                    // Default for Facebook: video + audio, best quality
                    selectFormat(false);

                    binding.llContent.setVisibility(View.VISIBLE);
                    Animation anim = AnimationUtils.loadAnimation(
                            FbDownloaderActivity.this, R.anim.slide_up_fade_in);
                    binding.llContent.startAnimation(anim);

                    setGetVideoButtonState(true, getString(R.string.fb_btn_get_video));

                    int resCount = finalHeights.size() - 1;
                    Toast.makeText(this,
                            "Found " + resCount + " video resolution(s)",
                            Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "";
                runOnUiThread(() -> {
                    setGetVideoButtonState(true, getString(R.string.fb_btn_get_video));
                    handleExtractionFailure(msg);
                });
            }
        });
    }

    /**
     * yt-dlp failing on a Facebook URL usually means the post is not public. Point the
     * user at the login flow instead of dumping a raw stack message on them.
     */
    private void handleExtractionFailure(String rawMessage) {
        boolean usable = FbCookieStore.hasUsableSession();

        String advice;
        if (!usable) {
            // Either never logged in, or the session token didn't survive — same fix.
            advice = getString(R.string.fb_private_message);
        } else {
            advice = getString(R.string.fb_private_message_logged_in);
        }

        String details = advice
                + "\n\n— Session —\n" + FbCookieStore.describe(this)
                + "\n\n— yt-dlp said —\n"
                + (TextUtils.isEmpty(rawMessage) ? "(no output)" : rawMessage);

        new AlertDialog.Builder(this)
                .setTitle(R.string.fb_private_title)
                .setMessage(details)
                .setNeutralButton(R.string.fb_copy_error, (d, w) -> {
                    ClipboardManager cb =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null) {
                        cb.setPrimaryClip(
                                android.content.ClipData.newPlainText("yt-dlp error", details));
                        Toast.makeText(this, R.string.fb_copied, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.fb_private_open_browser,
                        (d, w) -> openBrowser(binding.etUrl.getText().toString().trim()))
                .show();
    }

    /** Parses yt-dlp JSON for all distinct heights of video formats, ascending. */
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

                String vcodec = fmt.optString("vcodec", "none");
                if (vcodec.equals("none") || TextUtils.isEmpty(vcodec)) continue;
                if (h < 144) continue;

                heights.add(h);
            }
        } catch (JSONException e) {
            // Parse error — caller falls back to defaults
        }
        return new ArrayList<>(heights);
    }

    // ── Permission & Download Flow ─────────────────────────────────────────────

    private void checkPermissionAndDownload() {
        String url = binding.etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url))    { showError(getString(R.string.fb_error_empty_url)); return; }
        if (!isValidFacebookUrl(url))  { showError(getString(R.string.fb_error_invalid_url)); return; }
        if (!isInitialized)            { showError(getString(R.string.yt_initializing)); return; }

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
        String url = normalizeFbUrl(binding.etUrl.getText().toString().trim());
        isDownloading = true;
        isCancelled   = false;
        showDownloadDialog();
        hideResultUI();
        setDownloadButtonEnabled(false);

        File tempDir = new File(getCacheDir(), "fbdl_temp");
        if (tempDir.exists()) {
            File[] old = tempDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        tempDir.mkdirs();

        if (!streamVideoChoices.isEmpty()) {
            executor.execute(() -> performStreamDownload(tempDir));
            return;
        }

        executor.execute(() -> performDownload(url, tempDir));
    }

    /**
     * Downloads the renditions captured in the browser and muxes them.
     *
     * <p>This path never touches yt-dlp: the URLs came from a real, logged-in browser
     * session, so Facebook's bot detection — the thing that makes yt-dlp fail with
     * "Cannot parse data" on private content — is not in play.
     */
    private void performStreamDownload(File tempDir) {
        FbStream video = streamVideoChoices.get(
                Math.min(selectedQuality, streamVideoChoices.size() - 1));
        // A progressive file already carries its audio; pairing it with a sniffed audio
        // track would only produce a duplicate stream.
        FbStream audio = video.muxed ? null : bestAudioStream();

        FbStreamDownloader.Progress progress = new FbStreamDownloader.Progress() {
            @Override public void onProgress(int percent, String line) {
                runOnUiThread(() -> updateStatus(
                        getString(R.string.yt_status_downloading), percent, line));
            }
            @Override public boolean isCancelled() { return isCancelled; }
        };

        try {
            File videoFile = new File(tempDir, "video.mp4");
            runOnUiThread(() -> updateStatus("Downloading video…", 0,
                    "Video track: " + video.label()));
            FbStreamDownloader.download(video, videoFile, progress);
            if (isCancelled) return;

            File finalFile = new File(tempDir, "facebook_" + video.label() + ".mp4");

            if (audio == null) {
                videoFile.renameTo(finalFile);
                if (!video.muxed) {
                    // The player never fetched an audio track — keep the video, silent,
                    // rather than fail outright.
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.fb_captured_video_only, Toast.LENGTH_LONG).show());
                }
            } else {
                File audioFile = new File(tempDir, "audio.mp4");
                runOnUiThread(() -> updateStatus("Downloading audio…", 0, "Audio track"));
                FbStreamDownloader.download(audio, audioFile, progress);
                if (isCancelled) return;

                // Sizes make a bad track obvious at a glance: a few KB of audio means the
                // download, not the merge, is what went wrong.
                final String sizes = "Video: " + (videoFile.length() / 1024) + " KB"
                        + "   Audio: " + (audioFile.length() / 1024) + " KB";
                runOnUiThread(() -> updateStatus("Merging…", 99, sizes + "\nMuxing video + audio"));
                try {
                    FbStreamDownloader.mux(this, videoFile, audioFile, finalFile);
                    videoFile.delete();
                    audioFile.delete();
                } catch (IOException muxError) {
                    // Still better to hand over a silent video than nothing at all — but the
                    // reason has to survive to somewhere the user can actually read and send.
                    android.util.Log.e("FbDl", "mux failed", muxError);

                    String report = "MERGE FAILED — saved video without audio.\n\n"
                            + FbStreamDownloader.probe("video", videoFile) + "\n"
                            + FbStreamDownloader.probe("audio", audioFile) + "\n\n"
                            + "video track: " + video.label() + "  tag=" + video.tag + "\n"
                            + "audio track: " + audio.label() + "  tag=" + audio.tag + "\n\n"
                            + (muxError.getMessage() == null
                                    ? muxError.toString() : muxError.getMessage());
                    lastMuxReport = report;

                    videoFile.renameTo(finalFile);
                    audioFile.delete();
                    runOnUiThread(() -> {
                        updateStatus("Merge failed", 99, report);
                        Toast.makeText(this, R.string.fb_mux_failed, Toast.LENGTH_LONG).show();
                    });
                }
            }

            if (isCancelled) return;
            runOnUiThread(() -> updateStatus("Saving to Downloads…", 99, "Copying file…"));
            String savedPath = moveToPublicDownloads(tempDir);
            runOnUiThread(() -> {
                dismissDownloadDialog();
                showResultUI(savedPath);
                setDownloadButtonEnabled(true);
                isDownloading = false;
                showMuxReportIfAny();
            });

        } catch (IOException e) {
            if (isCancelled) return;
            final String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            runOnUiThread(() -> {
                dismissDownloadDialog();
                setDownloadButtonEnabled(true);
                isDownloading = false;
                showError(getString(R.string.yt_status_error) + msg);
            });
        }
    }

    private void performDownload(String url, File tempDir) {
        try {
            YoutubeDLRequest request = new YoutubeDLRequest(url);

            if (isMp3Selected) {
                // ── Audio only → MP3 ──────────────────────────────────────────
                request.addOption("-f", "bestaudio/best");
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                switch (selectedQuality) {
                    case 0: request.addOption("--audio-quality", "128K"); break;
                    case 1: request.addOption("--audio-quality", "192K"); break;
                    default: request.addOption("--audio-quality", "320K"); break;
                }
            } else {
                // ── Video + Audio → MP4 ───────────────────────────────────────
                // Facebook often exposes only pre-muxed formats, so fall through to
                // plain "best" rather than failing when bestvideo+bestaudio is absent.
                int targetHeight = displayedHeights.get(selectedQuality);
                String fmt;
                if (targetHeight < 0) {
                    fmt = "bestvideo+bestaudio/best";
                } else {
                    fmt = "bestvideo[height<=" + targetHeight + "]+bestaudio"
                        + "/best[height<=" + targetHeight + "]"
                        + "/best";
                }
                request.addOption("-f", fmt);
                request.addOption("--merge-output-format", "mp4");
            }

            request.addOption("-o", tempDir.getAbsolutePath() + "/%(title).100s.%(ext)s");
            request.addOption("--no-playlist");
            request.addOption("--restrict-filenames");
            request.addOption("--ffmpeg-location", getApplicationInfo().nativeLibraryDir);
            applyCookies(request);

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

        View dialogView = LayoutInflater.from(this)
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

    /**
     * Surfaces a failed merge in a dialog the user can copy from. The download log scrolls
     * and the dialog is dismissed on completion, so the one line that explains the failure
     * was reliably lost before it could be read.
     */
    private void showMuxReportIfAny() {
        if (lastMuxReport == null) return;
        final String report = lastMuxReport;
        lastMuxReport = null;

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Merge failed")
                .setMessage(report)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("fb merge error", report));
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void updateStatus(String status, int progress, String logLine) {
        if (downloadDialog == null || !downloadDialog.isShowing()) return;

        dialogTvStatus.setText(status);
        dialogTvPercent.setText(progress + "%");

        if (logLine != null && !logLine.isEmpty()) {
            if (logBuffer.length() > 0) logBuffer.append("\n");
            logBuffer.append(logLine);
            dialogTvLog.setText(logBuffer.toString());
            dialogScrollLog.post(() -> dialogScrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        }

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

    /** Accepts every Facebook host variant, including short fb.watch links. */
    private boolean isValidFacebookUrl(String url) {
        String u = url.toLowerCase();
        return u.contains("facebook.com/")
                || u.contains("fb.watch/")
                || u.contains("fb.com/")
                || u.contains("fb.gg/");
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
