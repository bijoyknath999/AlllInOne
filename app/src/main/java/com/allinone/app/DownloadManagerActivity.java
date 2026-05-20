package com.allinone.app;

import android.Manifest;
import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityDownloadManagerBinding;
import com.allinone.app.databinding.DialogDmSettingsBinding;
import com.allinone.app.downloader.DownloadAdapter;
import com.allinone.app.downloader.DownloadItem;
import com.allinone.app.downloader.DownloadManagerSettings;
import com.allinone.app.downloader.DownloadService;
import com.allinone.app.downloader.DownloadTask;
import com.allinone.app.downloader.DownloadedFile;
import com.allinone.app.downloader.FilesAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadManagerActivity extends AppCompatActivity
        implements DownloadAdapter.ActionListener, DownloadService.UICallback {

    // ── Fields ────────────────────────────────────────────────────────────────

    private ActivityDownloadManagerBinding binding;

    // Download list is now shared with the service
    private List<DownloadItem> downloads = new ArrayList<>();

    private DownloadAdapter dlAdapter;
    private FilesAdapter    filesAdapter;

    private ExecutorService fileLoadExecutor;   // only for file listing
    private final Handler   mainHandler = new Handler(Looper.getMainLooper());

    private boolean pageLoading = false;
    private int     currentTab  = R.id.nav_browser;

    // Settings dialog state
    private boolean settingsDesktopUA;
    private int     settingsMaxDl;

    // ── Service binding ──────────────────────────────────────────────────────

    private DownloadService downloadService;
    private boolean         serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            DownloadService.LocalBinder localBinder = (DownloadService.LocalBinder) binder;
            downloadService = localBinder.getService();
            downloadService.setUICallback(DownloadManagerActivity.this);
            serviceBound = true;

            // Sync download list from service
            downloads.clear();
            downloads.addAll(downloadService.getDownloads());
            dlAdapter.notifyDataSetChanged();
            updateDownloadsEmpty();
            updateDownloadsBadge();
            updateDownloadsHeader();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            downloadService = null;
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityDownloadManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        applyWindowInsets();
        fileLoadExecutor = Executors.newSingleThreadExecutor();

        setupWebView();
        setupDownloadsTab();
        setupFilesTab();
        setupBottomNav();
        setupCommonListeners();

        requestNotificationPermission();

        binding.webView.loadUrl(DownloadManagerSettings.getHomepage(this));

        // Handle intent extras (e.g. from notification tap)
        handleOpenTabIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleOpenTabIntent(intent);
    }

    private void handleOpenTabIntent(Intent intent) {
        if (intent != null && "downloads".equals(intent.getStringExtra("open_tab"))) {
            switchToTab(R.id.nav_downloads);
            binding.bottomNav.setSelectedItemId(R.id.nav_downloads);
        } else if (intent != null && "files".equals(intent.getStringExtra("open_tab"))) {
            switchToTab(R.id.nav_files);
            binding.bottomNav.setSelectedItemId(R.id.nav_files);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to service if running
        Intent intent = new Intent(this, DownloadService.class);
        bindService(intent, serviceConnection, 0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            if (downloadService != null) downloadService.setUICallback(null);
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // ── Notification permission (API 33+) ────────────────────────────────────

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    // ── Insets ────────────────────────────────────────────────────────────────

    private void applyWindowInsets() {
        int origTop = binding.toolbar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(binding.dmRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.toolbar.setPadding(bars.left, bars.top + origTop, bars.right, 0);
            return insets;
        });
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = binding.webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(DownloadManagerSettings.getUserAgent(this));

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true);

        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageLoading = true;
                binding.etUrl.setText(url);
                updateSslIcon(url);
                updateNavButtons();
                binding.btnRefreshStop.setImageResource(R.drawable.ic_close_small);
                binding.btnRefreshStop.setColorFilter(getColor(R.color.color_primary));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageLoading = false;
                binding.etUrl.setText(url);
                updateSslIcon(url);
                updateNavButtons();
                binding.pageProgress.setVisibility(View.GONE);
                binding.btnRefreshStop.setImageResource(R.drawable.ic_refresh);
                binding.btnRefreshStop.clearColorFilter();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });

        binding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int pct) {
                if (pct < 100) {
                    binding.pageProgress.setVisibility(View.VISIBLE);
                    binding.pageProgress.post(() -> {
                        int total = binding.dmRoot.getWidth();
                        ViewGroup.LayoutParams lp = binding.pageProgress.getLayoutParams();
                        lp.width = (int) (total * pct / 100f);
                        binding.pageProgress.setLayoutParams(lp);
                    });
                } else {
                    binding.pageProgress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (currentTab == R.id.nav_browser && !TextUtils.isEmpty(title)) {
                    binding.tvToolbarTitle.setText(title);
                }
            }
        });

        // ── Download interception ─────────────────────────────────────────────
        binding.webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);

            // Start download via service
            startDownloadViaService(url, fileName, contentLength);

            // Switch to Downloads tab (browser state is preserved, user can tap back)
            binding.bottomNav.setSelectedItemId(R.id.nav_downloads);
        });
    }

    // ── Downloads tab ─────────────────────────────────────────────────────────

    private void setupDownloadsTab() {
        dlAdapter = new DownloadAdapter(downloads, this);
        binding.rvDownloads.setLayoutManager(new LinearLayoutManager(this));
        binding.rvDownloads.setItemAnimator(null); // Prevent blinking during progress updates
        binding.rvDownloads.setAdapter(dlAdapter);

        binding.btnDirectAdd.setOnClickListener(v -> addDirectUrl());
        binding.etDirectUrl.setOnEditorActionListener((v, id, event) -> {
            if (id == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                addDirectUrl();
                return true;
            }
            return false;
        });

        updateDownloadsEmpty();
    }

    // ── Files tab ─────────────────────────────────────────────────────────────

    private void setupFilesTab() {
        filesAdapter = new FilesAdapter(this);
        binding.rvFiles.setLayoutManager(new LinearLayoutManager(this));
        binding.rvFiles.setAdapter(filesAdapter);
    }

    // ── Bottom navigation ─────────────────────────────────────────────────────

    private void setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener(item -> {
            switchToTab(item.getItemId());
            return true;
        });
        // Active item color
        binding.bottomNav.setItemActiveIndicatorColor(
                android.content.res.ColorStateList.valueOf(getColor(R.color.chip_selected_bg)));
        binding.bottomNav.setItemIconTintList(buildNavTintList());
        binding.bottomNav.setItemTextColor(buildNavTintList());
    }

    private android.content.res.ColorStateList buildNavTintList() {
        int[][] states = {
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        int[] colors = { getColor(R.color.color_primary), getColor(R.color.text_secondary) };
        return new android.content.res.ColorStateList(states, colors);
    }

    // ── Common listeners ──────────────────────────────────────────────────────

    private void setupCommonListeners() {
        binding.btnBack.setOnClickListener(v -> {
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });

        binding.btnSettings.setOnClickListener(v -> showSettingsDialog());

        // Browser nav buttons
        binding.btnWebBack.setOnClickListener(v -> {
            if (binding.webView.canGoBack()) binding.webView.goBack();
        });
        binding.btnWebForward.setOnClickListener(v -> {
            if (binding.webView.canGoForward()) binding.webView.goForward();
        });
        binding.btnRefreshStop.setOnClickListener(v -> {
            if (pageLoading) {
                binding.webView.stopLoading();
                pageLoading = false;
                binding.btnRefreshStop.setImageResource(R.drawable.ic_refresh);
                binding.btnRefreshStop.clearColorFilter();
                binding.pageProgress.setVisibility(View.GONE);
            } else {
                binding.webView.reload();
            }
        });

        // URL bar navigation
        binding.etUrl.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateTo(binding.etUrl.getText().toString().trim());
                return true;
            }
            return false;
        });
        binding.etUrl.setOnFocusChangeListener((v, focus) -> {
            if (focus) binding.etUrl.selectAll();
            updateClearButtonVisibility();
        });

        // Clear (X) button in URL bar
        binding.btnClearUrl.setOnClickListener(v -> {
            binding.etUrl.setText("");
            binding.etUrl.requestFocus();
        });

        // Show/hide clear button based on text
        binding.etUrl.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                updateClearButtonVisibility();
            }
        });
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private void switchToTab(int tabId) {
        currentTab = tabId;

        binding.panelBrowser.setVisibility(tabId == R.id.nav_browser    ? View.VISIBLE : View.GONE);
        binding.panelDownloads.setVisibility(tabId == R.id.nav_downloads ? View.VISIBLE : View.GONE);
        binding.panelFiles.setVisibility(tabId == R.id.nav_files         ? View.VISIBLE : View.GONE);

        if (tabId == R.id.nav_browser) {
            binding.tvToolbarTitle.setText(R.string.dm_title);
        } else if (tabId == R.id.nav_downloads) {
            binding.tvToolbarTitle.setText("Downloads");
            updateDownloadsHeader();
        } else if (tabId == R.id.nav_files) {
            binding.tvToolbarTitle.setText("Downloaded Files");
            loadDownloadedFiles();
        }

        if (tabId != R.id.nav_browser) hideKeyboard();
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void navigateTo(String input) {
        if (TextUtils.isEmpty(input)) return;
        hideKeyboard();

        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            url = "https://" + input;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(input);
        }
        binding.webView.loadUrl(url);
        switchToTab(R.id.nav_browser);
        binding.bottomNav.setSelectedItemId(R.id.nav_browser);
    }

    // ── Starting downloads via service ────────────────────────────────────────

    private void startDownloadViaService(String url, String fileName, long totalBytes) {
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_ADD);
        intent.putExtra(DownloadService.EXTRA_URL, url);
        intent.putExtra(DownloadService.EXTRA_FILENAME, fileName);
        intent.putExtra(DownloadService.EXTRA_TOTAL_BYTES, totalBytes);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        // If service already bound, it will fire onDownloadAdded callback.
        // If not yet bound (first download), bind now.
        if (!serviceBound) {
            bindService(new Intent(this, DownloadService.class),
                    serviceConnection, BIND_AUTO_CREATE);
        }
    }

    private void addDirectUrl() {
        String url = binding.etDirectUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) { showError("Enter a URL first"); return; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showError("URL must start with http:// or https://"); return;
        }
        binding.etDirectUrl.setText("");
        hideKeyboard();
        startDownloadViaService(url, "", -1);
    }

    // ── DownloadAdapter.ActionListener ────────────────────────────────────────

    @Override
    public void onPauseResume(DownloadItem item) {
        if (!serviceBound || downloadService == null) return;
        if (item.status == DownloadItem.Status.DOWNLOADING) {
            downloadService.pauseDownload(item.id);
        } else if (item.status == DownloadItem.Status.PAUSED
                || item.status == DownloadItem.Status.QUEUED) {
            downloadService.resumeDownload(item.id);
            refreshDownloadItem(item);
        }
    }

    @Override
    public void onCancel(DownloadItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Cancel Download")
                .setMessage("Cancel \"" + item.fileName + "\"?\nThis will also delete the partial file.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    // Cancel download
                    if (serviceBound && downloadService != null) {
                        downloadService.cancelDownload(item.id);
                    }
                    
                    // Delete temp file if exists
                    if (item.tempFilePath != null) {
                        File tempFile = new File(item.tempFilePath);
                        if (tempFile.exists()) tempFile.delete();
                    }
                    
                    // Remove from list completely (like delete)
                    int idx = downloads.indexOf(item);
                    if (idx >= 0) {
                        downloads.remove(idx);
                        dlAdapter.notifyItemRemoved(idx);
                        updateDownloadsEmpty();
                        updateDownloadsBadge();
                        updateDownloadsHeader();
                    }

                    // Also remove from service list so it doesn't come back on app reopen
                    if (serviceBound && downloadService != null) {
                        downloadService.getDownloads().remove(item);
                    }

                    Toast.makeText(this, "Cancelled: " + item.fileName, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    @Override
    public void onOpen(DownloadItem item) {
        if (item.savedPath == null || item.savedPath.isEmpty()) {
            Toast.makeText(this, "File path not available", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String mime = guessMimeType(item.fileName);
            Uri fileUri = null;

            // Try MediaStore lookup (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] projection = { MediaStore.Downloads._ID };
                String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
                String[] selArgs = { item.fileName };
                try (Cursor c = getContentResolver().query(collection, projection, selection, selArgs, null)) {
                    if (c != null && c.moveToFirst()) {
                        long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID));
                        fileUri = ContentUris.withAppendedId(collection, id);
                    }
                }
            }

            // Fallback to file path (API 28)
            if (fileUri == null) {
                File file = new File(item.savedPath);
                if (!file.exists()) {
                    file = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "AllInOne/" + item.fileName);
                }
                if (file.exists()) {
                    fileUri = Uri.fromFile(file);
                }
            }

            if (fileUri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(fileUri, mime);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } else {
                Toast.makeText(this, "File not found: " + item.savedPath, Toast.LENGTH_SHORT).show();
            }
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDelete(DownloadItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete file")
                .setMessage("Delete \"" + item.fileName + "\"?\nThis will remove the file from storage.")
                .setPositiveButton("Yes", (dialog, which) -> {
                    boolean deleted = false;

                    // Try MediaStore delete (API 29+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                        String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
                        String[] selArgs = { item.fileName };
                        try {
                            int rows = getContentResolver().delete(collection, selection, selArgs);
                            deleted = rows > 0;
                        } catch (Exception ignored) {}
                    }

                    // Fallback to file delete
                    if (!deleted && item.savedPath != null) {
                        File file = new File(item.savedPath);
                        if (!file.exists()) {
                            file = new File(Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DOWNLOADS), "AllInOne/" + item.fileName);
                        }
                        deleted = file.delete();
                    }

                    // Remove from list
                    int idx = downloads.indexOf(item);
                    if (idx >= 0) {
                        downloads.remove(idx);
                        dlAdapter.notifyItemRemoved(idx);
                        updateDownloadsEmpty();
                        updateDownloadsBadge();
                        updateDownloadsHeader();
                    }

                    // Also remove from service list
                    if (serviceBound && downloadService != null) {
                        downloadService.getDownloads().remove(item);
                    }

                    Toast.makeText(this,
                            deleted ? "Deleted: " + item.fileName : "Removed from list",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null)
                .show();
    }

    private String guessMimeType(String fileName) {
        if (fileName == null) return "*/*";
        String name = fileName.toLowerCase(Locale.US);
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".rar")) return "application/x-rar-compressed";
        if (name.endsWith(".7z")) return "application/x-7z-compressed";
        if (name.endsWith(".doc")) return "application/msword";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".txt")) return "text/plain";
        return "*/*";
    }

    // ── DownloadService.UICallback ────────────────────────────────────────────

    @Override
    public void onDownloadAdded(DownloadItem item) {
        // Service added a new item; sync
        if (!downloads.contains(item)) {
            downloads.add(0, item);
            dlAdapter.notifyItemInserted(0);
        }
        updateDownloadsEmpty();
        updateDownloadsBadge();
        updateDownloadsHeader();
    }

    @Override
    public void onDownloadProgress(DownloadItem item) {
        refreshDownloadItem(item);
        updateDownloadsHeader();
    }

    @Override
    public void onDownloadStateChanged(DownloadItem item) {
        refreshDownloadItem(item);
        updateDownloadsBadge();
        updateDownloadsHeader();
    }

    // ── Files tab loading ─────────────────────────────────────────────────────

    private void loadDownloadedFiles() {
        binding.pbFilesLoading.setVisibility(View.VISIBLE);
        binding.tvFilesEmpty.setVisibility(View.GONE);
        binding.rvFiles.setVisibility(View.GONE);

        fileLoadExecutor.execute(() -> {
            List<DownloadedFile> result = new ArrayList<>();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Uri uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String[] projection = {
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.DATE_ADDED,
                        MediaStore.Downloads.MIME_TYPE,
                        MediaStore.Downloads.RELATIVE_PATH
                };
                String   sel     = MediaStore.Downloads.RELATIVE_PATH + " LIKE ?";
                String[] selArgs = { "%AllInOne%" };
                String   sort    = MediaStore.Downloads.DATE_ADDED + " DESC";

                try (Cursor c = getContentResolver().query(uri, projection, sel, selArgs, sort)) {
                    if (c != null) {
                        int iId   = c.getColumnIndexOrThrow(MediaStore.Downloads._ID);
                        int iName = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
                        int iSize = c.getColumnIndexOrThrow(MediaStore.Downloads.SIZE);
                        int iDate = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED);
                        int iMime = c.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE);

                        while (c.moveToNext()) {
                            DownloadedFile f = new DownloadedFile();
                            f.name       = c.getString(iName);
                            f.size       = c.getLong(iSize);
                            f.dateMs     = c.getLong(iDate) * 1000L;
                            f.mimeType   = c.getString(iMime);
                            f.contentUri = ContentUris.withAppendedId(uri, c.getLong(iId));
                            result.add(f);
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                // API 28: scan file system
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "AllInOne");
                if (dir.exists()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                        for (File f : files) {
                            if (!f.isFile()) continue;
                            DownloadedFile df = new DownloadedFile();
                            df.name   = f.getName();
                            df.path   = f.getAbsolutePath();
                            df.size   = f.length();
                            df.dateMs = f.lastModified();
                            df.mimeType = getMimeTypeFromName(f.getName());
                            result.add(df);
                        }
                    }
                }
            }

            mainHandler.post(() -> {
                binding.pbFilesLoading.setVisibility(View.GONE);
                filesAdapter.setFiles(result);
                binding.tvFilesCount.setText(result.size() + " file(s) in Downloads/AllInOne");
                if (result.isEmpty()) {
                    binding.tvFilesEmpty.setVisibility(View.VISIBLE);
                    binding.rvFiles.setVisibility(View.GONE);
                } else {
                    binding.tvFilesEmpty.setVisibility(View.GONE);
                    binding.rvFiles.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private String getMimeTypeFromName(String name) {
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.US) : "";
        switch (ext) {
            case "mp4": case "mkv": case "avi": return "video/" + ext;
            case "mp3": return "audio/mpeg";
            case "aac": return "audio/aac";
            case "pdf": return "application/pdf";
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "zip": return "application/zip";
            case "apk": return "application/vnd.android.package-archive";
            default:    return "*/*";
        }
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private void showSettingsDialog() {
        DialogDmSettingsBinding dv = DialogDmSettingsBinding.inflate(getLayoutInflater());

        // Populate current values
        dv.etHomepage.setText(DownloadManagerSettings.getHomepage(this));
        settingsDesktopUA = DownloadManagerSettings.isDesktopUA(this);
        settingsMaxDl     = DownloadManagerSettings.getMaxDownloads(this);

        applyUaChipStyle(dv, settingsDesktopUA);
        applyMaxChipStyle(dv, settingsMaxDl);

        dv.chipMobileUa.setOnClickListener(v -> { settingsDesktopUA = false; applyUaChipStyle(dv, false); });
        dv.chipDesktopUa.setOnClickListener(v -> { settingsDesktopUA = true;  applyUaChipStyle(dv, true);  });

        dv.chipMax1.setOnClickListener(v -> { settingsMaxDl = 1; applyMaxChipStyle(dv, 1); });
        dv.chipMax2.setOnClickListener(v -> { settingsMaxDl = 2; applyMaxChipStyle(dv, 2); });
        dv.chipMax3.setOnClickListener(v -> { settingsMaxDl = 3; applyMaxChipStyle(dv, 3); });

        dv.btnClearCache.setOnClickListener(v -> {
            binding.webView.clearCache(true);
            binding.webView.clearHistory();
            CookieManager.getInstance().removeAllCookies(null);
            Toast.makeText(this, "Browser cache cleared", Toast.LENGTH_SHORT).show();
        });

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(dv.getRoot())
                .setPositiveButton("Save", (d, w) -> {
                    String home = dv.etHomepage.getText().toString().trim();
                    if (!TextUtils.isEmpty(home)) DownloadManagerSettings.setHomepage(this, home);
                    DownloadManagerSettings.setDesktopUA(this, settingsDesktopUA);
                    DownloadManagerSettings.setMaxDownloads(this, settingsMaxDl);

                    // Apply UA to running WebView
                    binding.webView.getSettings().setUserAgentString(
                            DownloadManagerSettings.getUserAgent(this));
                    // Rebuild executor if thread count changed
                    if (serviceBound && downloadService != null) {
                        downloadService.rebuildExecutor();
                    }
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyUaChipStyle(DialogDmSettingsBinding dv, boolean desktop) {
        dv.chipMobileUa.setBackground(getDrawable(desktop
                ? R.drawable.bg_chip_unselected : R.drawable.bg_chip_selected));
        dv.chipMobileUa.setTextColor(getColor(desktop
                ? R.color.text_secondary : R.color.color_primary));
        dv.chipDesktopUa.setBackground(getDrawable(desktop
                ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected));
        dv.chipDesktopUa.setTextColor(getColor(desktop
                ? R.color.color_primary : R.color.text_secondary));
    }

    private void applyMaxChipStyle(DialogDmSettingsBinding dv, int max) {
        TextView[] chips = { dv.chipMax1, dv.chipMax2, dv.chipMax3 };
        int[]      vals  = { 1, 2, 3 };
        for (int i = 0; i < chips.length; i++) {
            boolean selected = vals[i] == max;
            chips[i].setBackground(getDrawable(selected
                    ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected));
            chips[i].setTextColor(getColor(selected
                    ? R.color.color_primary : R.color.text_secondary));
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void updateClearButtonVisibility() {
        boolean hasText = binding.etUrl.getText().length() > 0;
        boolean focused = binding.etUrl.hasFocus();
        binding.btnClearUrl.setVisibility(hasText && focused ? View.VISIBLE : View.GONE);
    }

    private void updateNavButtons() {
        binding.btnWebBack.setAlpha(binding.webView.canGoBack()    ? 1f : 0.35f);
        binding.btnWebForward.setAlpha(binding.webView.canGoForward() ? 1f : 0.35f);
    }

    private void updateSslIcon(String url) {
        boolean https = url != null && url.startsWith("https://");
        binding.ivSslIcon.setVisibility(https ? View.VISIBLE : View.GONE);
    }

    private void refreshDownloadItem(DownloadItem item) {
        int idx = downloads.indexOf(item);
        if (idx >= 0) dlAdapter.notifyItemChanged(idx);
    }

    private void updateDownloadsEmpty() {
        boolean empty = downloads.isEmpty();
        binding.tvDownloadsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.rvDownloads.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateDownloadsHeader() {
        long active = 0; long totalSpeed = 0;
        for (DownloadItem d : downloads) {
            if (d.status == DownloadItem.Status.DOWNLOADING) {
                active++;
                totalSpeed += d.speedBps;
            }
        }
        if (active == 0) {
            binding.tvActiveCount.setText(downloads.isEmpty()
                    ? "No downloads yet" : downloads.size() + " item(s) in queue");
            binding.tvTotalSpeed.setVisibility(View.GONE);
        } else {
            binding.tvActiveCount.setText(active + " active download" + (active > 1 ? "s" : ""));
            if (totalSpeed > 0) {
                binding.tvTotalSpeed.setVisibility(View.VISIBLE);
                binding.tvTotalSpeed.setText(formatSpeed(totalSpeed));
            } else {
                binding.tvTotalSpeed.setVisibility(View.GONE);
            }
        }
    }

    private void updateDownloadsBadge() {
        long active = 0;
        for (DownloadItem d : downloads) {
            if (d.status == DownloadItem.Status.DOWNLOADING
                    || d.status == DownloadItem.Status.PAUSED
                    || d.status == DownloadItem.Status.QUEUED) active++;
        }
        com.google.android.material.badge.BadgeDrawable badge =
                binding.bottomNav.getOrCreateBadge(R.id.nav_downloads);
        if (active > 0) {
            badge.setNumber((int) active);
            badge.setBackgroundColor(getColor(R.color.color_primary));
            badge.setVisible(true);
        } else {
            badge.setVisible(false);
            binding.bottomNav.removeBadge(R.id.nav_downloads);
        }
    }

    private String formatSpeed(long bps) {
        if (bps < 1024) return bps + " B/s";
        if (bps < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024));
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    private void showError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ── Back press ────────────────────────────────────────────────────────────

    @Override
    public void onBackPressed() {
        if (currentTab == R.id.nav_browser && binding.webView.canGoBack()) {
            binding.webView.goBack();
        } else if (currentTab != R.id.nav_browser) {
            binding.bottomNav.setSelectedItemId(R.id.nav_browser);
        } else {
            super.onBackPressed();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onPause()   { super.onPause();   binding.webView.onPause(); }
    @Override protected void onResume()  { super.onResume();  binding.webView.onResume(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding.webView.destroy();
        fileLoadExecutor.shutdownNow();
        // Downloads continue in the service — do NOT cancel them here
    }
}
