package com.allinone.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivityFbBrowserBinding;
import com.allinone.app.fb.FbCookieStore;
import com.allinone.app.fb.FbHtmlExtractor;
import com.allinone.app.fb.FbPageFetcher;
import com.allinone.app.fb.FbStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-app Facebook browser used to (a) log the user into their own account so private,
 * group and friends-only videos become reachable, and (b) let them navigate to the exact
 * post they want and hand its URL back to {@link FbDownloaderActivity}.
 *
 * <p>Two things are harvested here. The cookie jar is exported to a Netscape cookies.txt
 * for yt-dlp's {@code --cookies}, and — more importantly for private content — the actual
 * fbcdn media URLs are captured off the wire as the video plays. Facebook fingerprints
 * non-browser clients and serves yt-dlp a stripped page ("Cannot parse data"); a real
 * WebView with a real session is not subject to that, so whatever plays here can be
 * downloaded. See {@link FbStream}.
 */
public class FbBrowserActivity extends AppCompatActivity {

    public static final String EXTRA_URL        = "extra_fb_url";
    public static final String EXTRA_START_URL  = "extra_fb_start_url";
    /** Encoded {@link FbStream}s captured off fbcdn while the video played. */
    public static final String EXTRA_STREAMS    = "extra_fb_streams";

    private static final String HOME_URL = "https://m.facebook.com/";

    /** A real Chrome-mobile UA — Facebook degrades or blocks login on the default "wv" UA. */
    static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Mobile Safari/537.36";

    /**
     * Desktop UA used to isolate a single video. The mobile site is a single-page app whose
     * document holds the whole feed — several videos' worth of media JSON at once — which is
     * what makes picking "the one playing" guesswork. Loading the video's own permalink with
     * a desktop UA yields a document containing exactly one video, so there is nothing to
     * mix up and the extracted video and audio provably belong together.
     */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    private ActivityFbBrowserBinding binding;

    /**
     * Renditions seen on fbcdn, keyed by {@link FbStream#key()} so the player's repeated
     * ranged requests for the same track collapse to one entry. Written from the WebView's
     * background thread, read on the main thread — hence the concurrent map.
     */
    private final Map<String, FbStream> capturedStreams = new ConcurrentHashMap<>();

    /**
     * When each track was last requested, keyed like {@link #capturedStreams}.
     *
     * <p>The player only keeps fetching segments for the video it is currently playing, so
     * "requested recently" is a far better signal for which video the user means than
     * "present on this page". Without it, scrolling past three Reels leaves all three in the
     * capture list and the user is offered tracks from videos they already moved on from.
     */
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();

    /**
     * How far back from the newest request a track still counts as part of what is playing.
     * Wide enough to keep a track the player briefly stopped fetching (a full buffer, an
     * adaptive-bitrate switch), short enough to drop the previous video.
     */
    private static final long CAPTURE_WINDOW_MS = 8_000L;

    /** The page the current captures belong to; navigating away invalidates them. */
    private String capturePageUrl;

    /**
     * Progressive MP4s read out of the current page by {@link FbHtmlExtractor}. Preferred
     * over {@link #capturedStreams}: they belong to the video the page is actually showing,
     * and they already contain audio.
     */
    private final List<FbStream> htmlStreams = new ArrayList<>();

    /**
     * The tracks frozen by the Capture button, and what {@link #returnCurrentUrl()} sends
     * verbatim when non-empty.
     *
     * <p>Capture exists because deciding <em>when</em> to read the streams is the hard part.
     * Doing it automatically at hand-off means re-scanning the page at the worst possible
     * moment: Facebook rewrites its JSON as the player runs, so a scan that succeeded while
     * the video played can come back different — or partial — a few seconds later, and it
     * silently replaced the good result. Letting the user pick the moment removes the
     * guesswork entirely, and makes what will be downloaded inspectable before committing.
     */
    private final List<FbStream> snapshot = new ArrayList<>();

    /** Off-main-thread work for the served-HTML fetch. */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Video id we navigated to the desktop permalink for, or null. */
    private String isolatedId;
    /** True once that permalink has finished loading, so we don't isolate in a loop. */
    private boolean onIsolatedPage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityFbBrowserBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        int originalTop    = binding.toolbar.getPaddingTop();
        int originalBottom = binding.toolbar.getPaddingBottom();
        int actionsBottom  = binding.llBrowserActions.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(binding.fbBrowserRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.toolbar.setPadding(
                    bars.left, bars.top + originalTop, bars.right, originalBottom);
            binding.llBrowserActions.setPadding(
                    binding.llBrowserActions.getPaddingLeft(),
                    binding.llBrowserActions.getPaddingTop(),
                    binding.llBrowserActions.getPaddingRight(),
                    bars.bottom + actionsBottom);
            return insets;
        });

        setupWebView();
        setupUI();

        String start = getIntent().getStringExtra(EXTRA_START_URL);
        binding.webviewFb.loadUrl(TextUtils.isEmpty(start) ? HOME_URL : start);
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebView web = binding.webviewFb;
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(MOBILE_UA);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Keep every navigation inside this WebView so cookies stay in our jar.
                view.loadUrl(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                binding.tvBrowserUrl.setText(url);
                onNavigatedTo(url);
            }

            /**
             * Facebook is a single-page app: moving between reels or opening a video from
             * the feed is a {@code history.pushState}, which never fires
             * {@link #onPageStarted}. Without this hook the captures from the previous
             * video survived, and "Use This Video" downloaded that one instead.
             */
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                binding.tvBrowserUrl.setText(url);
                onNavigatedTo(url);
                refreshStatus(url);
            }

            /**
             * Runs on a background thread for every request the page makes, including the
             * media fetches Facebook's MSE player issues. We only observe and return null
             * so the WebView handles the request normally.
             */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                try {
                    String u = request.getUrl().toString();
                    if (FbStream.isMediaUrl(u)) {
                        FbStream s = FbStream.parse(u);
                        if (s != null) {
                            boolean isNew = capturedStreams.putIfAbsent(s.key(), s) == null;
                            // Refreshed on every re-request, not just the first, so the
                            // timestamp tracks what the player is still actively fetching.
                            lastSeen.put(s.key(), android.os.SystemClock.elapsedRealtime());
                            if (isNew) {
                                // Touching the WebView from this thread throws — hop to main.
                                runOnUiThread(() -> refreshStatus(binding.webviewFb.getUrl()));
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Never let capture break page loading.
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                binding.tvBrowserUrl.setText(url);
                if (!TextUtils.isEmpty(view.getTitle())) {
                    binding.tvBrowserTitle.setText(view.getTitle());
                }
                setPageProgress(0);

                // The isolated permalink is the one page whose extraction is unambiguous,
                // so it gets frozen straight away instead of just refreshing the status.
                if (isolatedId != null && !onIsolatedPage
                        && url != null && url.contains(isolatedId)) {
                    onIsolatedPage = true;
                    onIsolatedPageLoaded();
                    return;
                }

                scanPageForStreams(null);
                refreshStatus(url);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                setPageProgress(newProgress < 100 ? newProgress : 0);
            }
        });
    }

    private void setupUI() {
        binding.btnClose.setOnClickListener(v -> finishWithoutResult());
        binding.btnBrowserRefresh.setOnClickListener(v -> binding.webviewFb.reload());
        binding.btnBrowserHome.setOnClickListener(v -> {
            leaveIsolation();
            binding.webviewFb.loadUrl(HOME_URL);
        });
        binding.btnCapture.setOnClickListener(v -> captureNow());

        binding.btnUseVideo.setOnClickListener(v -> {
            // A snapshot is what the user explicitly froze — send it as-is.
            if (!snapshot.isEmpty()) {
                returnCurrentUrl();
                return;
            }
            // Otherwise open this video on its own desktop page first, so the extraction
            // runs against a document containing only this video.
            if (!onIsolatedPage && isolateCurrentVideo()) return;

            scanPageForStreams(this::returnCurrentUrl);
        });

        // Tapping the status line explains exactly what was found and what will be sent.
        binding.tvBrowserStatus.setOnClickListener(v -> showStreamDiagnostics());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.webviewFb.canGoBack()) {
                    binding.webviewFb.goBack();
                } else {
                    finishWithoutResult();
                }
            }
        });
    }

    // ── Capture scoping ───────────────────────────────────────────────────────

    /**
     * Drops everything gathered for the previous video. Called for both real page loads and
     * the SPA's pushState navigations, so what we hand back is always the video on screen.
     */
    private void onNavigatedTo(String url) {
        if (sameDocument(url, capturePageUrl)) return;

        // Browsing on from the isolated permalink puts us back on ordinary feed pages, so
        // the desktop UA and the isolation flags have to go with it.
        if (isolatedId != null && (url == null || !url.contains(isolatedId))) {
            leaveIsolation();
        }

        capturedStreams.clear();
        lastSeen.clear();
        htmlStreams.clear();
        // A snapshot belongs to the video it was taken on.
        snapshot.clear();
        capturePageUrl = url;
    }

    /**
     * Reads the progressive MP4 URLs out of the live DOM. Facebook fills the video JSON in
     * asynchronously, so this is run on page-finished <em>and</em> again when the user taps
     * Use This Video — the second read is the authoritative one.
     *
     * @param done run on the main thread once the scan finishes; may be null.
     */
    private void scanPageForStreams(Runnable done) {
        String pageUrl = binding.webviewFb.getUrl();
        binding.webviewFb.evaluateJavascript(FbHtmlExtractor.script(pageUrl), value -> {
            List<FbStream> found = FbHtmlExtractor.parse(value);
            if (!found.isEmpty()) {
                htmlStreams.clear();
                htmlStreams.addAll(found);
            }
            if (done != null) done.run();
        });
    }

    /**
     * Navigates to the current video's own desktop permalink so the page holds only it.
     *
     * @return true if isolation started; false when the URL carries no video id (short
     *         links such as {@code fb.watch/...}), leaving the caller to fall back.
     */
    private boolean isolateCurrentVideo() {
        String url = binding.webviewFb.getUrl();
        String id = FbHtmlExtractor.videoIdOf(url);
        if (TextUtils.isEmpty(id)) return false;

        // Everything gathered so far came from the feed document and may belong to another
        // video — none of it should survive into the isolated page's results.
        capturedStreams.clear();
        lastSeen.clear();
        htmlStreams.clear();
        snapshot.clear();

        isolatedId = id;
        onIsolatedPage = false;

        boolean reel = url != null && url.toLowerCase().contains("/reel/");
        String target = reel
                ? "https://www.facebook.com/reel/" + id
                : "https://www.facebook.com/watch/?v=" + id;

        binding.webviewFb.getSettings().setUserAgentString(DESKTOP_UA);
        binding.tvBrowserStatus.setText(R.string.fb_browser_isolating);
        capturePageUrl = target;
        binding.webviewFb.loadUrl(target);
        return true;
    }

    /** Returns the browser to normal mobile browsing. */
    private void leaveIsolation() {
        isolatedId = null;
        onIsolatedPage = false;
        binding.webviewFb.getSettings().setUserAgentString(MOBILE_UA);
    }

    /**
     * Runs once the isolated permalink has loaded: extract, and freeze the result. Anything
     * found here describes a single video, so it needs no disambiguation.
     */
    private void onIsolatedPageLoaded() {
        // Served HTML first — it still holds the inline JSON that hydration strips out of
        // the live DOM, including the pre-muxed URL that removes merging from the picture.
        fetchServedHtml(() -> {
            if (!htmlStreams.isEmpty()) {
                freezeSnapshot();
            } else {
                scanPageForStreams(this::freezeSnapshot);
            }
        });
    }

    /**
     * Re-requests the current page over HTTP with the WebView's cookies and scans the raw
     * response — the same text "View Page Source" shows, which is what the paste-your-source
     * download sites operate on.
     */
    private void fetchServedHtml(Runnable done) {
        final String url = binding.webviewFb.getUrl();
        if (TextUtils.isEmpty(url)) { done.run(); return; }

        final String cookie = CookieManager.getInstance().getCookie(url);
        io.execute(() -> {
            List<FbStream> found;
            try {
                String html = FbPageFetcher.fetch(url, cookie, DESKTOP_UA);
                found = FbHtmlExtractor.parseHtml(html, url);
            } catch (Exception e) {
                android.util.Log.w("FbBrowser", "served-HTML fetch failed", e);
                found = new ArrayList<>();
            }
            final List<FbStream> result = found;
            runOnUiThread(() -> {
                if (!result.isEmpty()) {
                    htmlStreams.clear();
                    htmlStreams.addAll(result);
                }
                done.run();
            });
        });
    }

    /** Freezes whatever the current sources agree on, and reports it. */
    private void freezeSnapshot() {
        snapshot.clear();
        List<FbStream> found = chooseStreams();
        if (found.isEmpty()) {
            binding.tvBrowserStatus.setText(R.string.fb_browser_isolate_failed);
            return;
        }
        snapshot.addAll(found);

        int v = 0, a = 0;
        for (FbStream s : snapshot) {
            if (s.isAudio) a++; else v++;
        }
        binding.tvBrowserStatus.setText(getString(R.string.fb_browser_isolated_ready, v, a));
    }

    /**
     * Freezes the tracks currently known for this page. Re-reads both sources first — served
     * HTML, then the live DOM — so a page that finished hydrating after load is still picked
     * up. Nothing re-reads the page afterwards.
     */
    private void captureNow() {
        fetchServedHtml(() -> scanPageForStreams(() -> {
            // Drop the previous snapshot first, or chooseStreams() would hand it straight
            // back and re-capturing would be a no-op.
            snapshot.clear();
            List<FbStream> found = chooseStreams();
            if (found.isEmpty()) {
                Toast.makeText(this, R.string.fb_browser_capture_none, Toast.LENGTH_LONG).show();
                return;
            }
            snapshot.addAll(found);

            int v = 0, a = 0;
            for (FbStream s : snapshot) {
                if (s.isAudio) a++; else v++;
            }
            Toast.makeText(this, getString(R.string.fb_browser_capture_ok, v, a),
                    Toast.LENGTH_SHORT).show();
            refreshStatus(binding.webviewFb.getUrl());
        }));
    }

    private List<FbStream> chooseStreams() {
        // A snapshot is a deliberate decision by the user — never second-guess it.
        if (!snapshot.isEmpty()) return new ArrayList<>(snapshot);

        // Take the best COMPLETE source rather than merging sources. Tracks are only safe
        // to mux together when they demonstrably describe the same video: a manifest lists
        // its own video and audio, and a single playback session fetches only its own. Mix
        // the two and you can pair one video's picture with another's sound — which is
        // exactly what merging them produced.

        // 1. A progressive file already holds both tracks. Nothing can mismatch.
        List<FbStream> progressive = new ArrayList<>();
        for (FbStream s : htmlStreams) {
            if (s.muxed) progressive.add(s);
        }
        if (!progressive.isEmpty()) return progressive;

        // 2. The manifest, when it names both — same MPD, so guaranteed to belong together.
        if (hasVideo(htmlStreams) && hasAudio(htmlStreams)) return new ArrayList<>(htmlStreams);

        // 3. One playback session's own requests.
        List<FbStream> sniffed = recentCaptures();
        if (hasVideo(sniffed) && hasAudio(sniffed)) return sniffed;

        // 4. Neither source is complete on its own. Prefer whichever has video and accept
        //    that the result may be silent — better than muxing a stranger's audio onto it.
        return hasVideo(htmlStreams) ? new ArrayList<>(htmlStreams) : sniffed;
    }

    private static boolean hasVideo(List<FbStream> streams) {
        for (FbStream s : streams) if (!s.isAudio) return true;
        return false;
    }

    private static boolean hasAudio(List<FbStream> streams) {
        for (FbStream s : streams) if (s.isAudio) return true;
        return false;
    }

    /**
     * The sniffed tracks belonging to whatever is playing now — those still being fetched
     * as of the most recent media request. Tracks from a video the user has scrolled past
     * stop being requested, so they age out and are not offered for download.
     */
    private List<FbStream> recentCaptures() {
        long newest = 0;
        for (Long t : lastSeen.values()) {
            if (t != null && t > newest) newest = t;
        }

        List<FbStream> recent = new ArrayList<>();
        if (newest == 0) return recent;

        for (Map.Entry<String, FbStream> e : capturedStreams.entrySet()) {
            Long t = lastSeen.get(e.getKey());
            if (t != null && newest - t <= CAPTURE_WINDOW_MS) recent.add(e.getValue());
        }
        return recent;
    }

    /**
     * Dumps both stream sources side by side. Which source a track came from, and how it
     * was classified, is the thing that actually explains a missing-audio download — and it
     * is invisible from the normal UI, which only shows a final quality list.
     */
    private void showStreamDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(binding.webviewFb.getUrl()).append("\n");
        sb.append("Video id: ")
                .append(com.allinone.app.fb.FbHtmlExtractor.videoIdOf(binding.webviewFb.getUrl()))
                .append("\n\n");

        sb.append("FROM PAGE (").append(htmlStreams.size()).append(")\n");
        for (FbStream s : htmlStreams) appendStream(sb, s);

        long now = android.os.SystemClock.elapsedRealtime();
        sb.append("\nSNIFFED (").append(capturedStreams.size())
                .append(", recent=").append(recentCaptures().size()).append(")\n");
        for (Map.Entry<String, FbStream> e : capturedStreams.entrySet()) {
            Long t = lastSeen.get(e.getKey());
            appendStream(sb, e.getValue());
            sb.append("    last seen ")
                    .append(t == null ? "?" : ((now - t) / 1000) + "s ago")
                    .append("\n");
        }

        sb.append("\nSNAPSHOT (").append(snapshot.size()).append(")\n");
        for (FbStream s : snapshot) appendStream(sb, s);

        List<FbStream> chosen = chooseStreams();
        sb.append("\nWILL SEND (").append(chosen.size()).append(")\n");
        for (FbStream s : chosen) appendStream(sb, s);

        final String report = sb.toString();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Stream diagnostics")
                .setMessage(report)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("fb streams", report));
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static void appendStream(StringBuilder sb, FbStream s) {
        sb.append(s.isAudio ? "  AUDIO " : "  VIDEO ")
                .append(s.label())
                .append(s.muxed ? " [muxed]" : "")
                .append("  tag=").append(TextUtils.isEmpty(s.tag) ? "-" : s.tag)
                .append("\n    ").append(s.key()).append("\n");
    }

    // ── Status / progress ─────────────────────────────────────────────────────

    private void setPageProgress(int percent) {
        View fill = binding.browserProgressFill;
        fill.post(() -> {
            int totalWidth = ((View) fill.getParent()).getWidth();
            ViewGroup.LayoutParams p = fill.getLayoutParams();
            p.width = (int) (totalWidth * percent / 100f);
            fill.setLayoutParams(p);
        });
    }

    /** Tells the user which step they're on: log in → open a video → play it → download. */
    private void refreshStatus(String url) {
        // Count what Use This Video would actually send, so the status can't promise audio
        // the downloader won't receive.
        List<FbStream> chosen = chooseStreams();
        int videos = 0, audios = 0;
        boolean muxed = false;
        for (FbStream s : chosen) {
            if (s.isAudio) audios++; else videos++;
            if (s.muxed) muxed = true;
        }

        if (!snapshot.isEmpty()) {
            binding.tvBrowserStatus.setText(getString(
                    R.string.fb_browser_captured_snapshot, videos, audios));
        } else if (muxed) {
            binding.tvBrowserStatus.setText(getString(
                    R.string.fb_browser_step_page_ready, videos));
        } else if (videos > 0) {
            binding.tvBrowserStatus.setText(getString(
                    R.string.fb_browser_step_captured, videos, audios));
        } else if (!FbCookieStore.hasUsableSession()) {
            binding.tvBrowserStatus.setText(R.string.fb_browser_step_login);
        } else if (looksLikeVideo(url)) {
            binding.tvBrowserStatus.setText(R.string.fb_browser_step_play);
        } else {
            binding.tvBrowserStatus.setText(R.string.fb_browser_step_open_video);
        }
    }

    /** Compares two URLs ignoring the fragment, so in-page anchors don't drop captures. */
    private static boolean sameDocument(String a, String b) {
        if (a == null || b == null) return false;
        return stripFragment(a).equals(stripFragment(b));
    }

    private static String stripFragment(String u) {
        int i = u.indexOf('#');
        return i < 0 ? u : u.substring(0, i);
    }

    /** Heuristic for "this page is a specific post/video", not a feed. */
    static boolean looksLikeVideo(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String u = url.toLowerCase();
        return u.contains("/videos/")
                || u.contains("/video.php")
                || u.contains("/watch/?v=")
                || u.contains("/watch?v=")
                || u.contains("/reel/")
                || u.contains("/share/v/")
                || u.contains("/share/r/")
                || u.contains("fb.watch/")
                || u.contains("/posts/")
                || u.contains("/permalink.php")
                || u.contains("story_fbid=");
    }

    // ── Exit paths ────────────────────────────────────────────────────────────

    /** Exports cookies and returns the currently-open URL to the downloader. */
    private void returnCurrentUrl() {
        String url = binding.webviewFb.getUrl();
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, R.string.fb_browser_no_url, Toast.LENGTH_SHORT).show();
            return;
        }
        saveCookies();

        ArrayList<String> encoded = new ArrayList<>();
        for (FbStream s : chooseStreams()) encoded.add(s.encode());

        if (encoded.isEmpty() && !looksLikeVideo(url)) {
            Toast.makeText(this, R.string.fb_browser_not_video, Toast.LENGTH_LONG).show();
        }

        Intent result = new Intent();
        result.putExtra(EXTRA_URL, url);
        result.putStringArrayListExtra(EXTRA_STREAMS, encoded);
        setResult(RESULT_OK, result);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    /** Still saves cookies — the user may have logged in without picking a video. */
    private void finishWithoutResult() {
        saveCookies();
        setResult(RESULT_CANCELED);
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void saveCookies() {
        CookieManager.getInstance().flush();
        try {
            FbCookieStore.export(this);
        } catch (Exception e) {
            android.util.Log.e("FbBrowser", "cookie export failed", e);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        binding.webviewFb.stopLoading();
        binding.webviewFb.destroy();
        super.onDestroy();
    }
}
