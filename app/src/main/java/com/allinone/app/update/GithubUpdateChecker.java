package com.allinone.app.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GithubUpdateChecker {

    // Set these to match your GitHub repository
    private static final String GITHUB_OWNER = "bijoyknath999";
    private static final String GITHUB_REPO  = "AlllInOne";

    private static final String API_URL =
        "https://api.github.com/repos/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";

    public static void check(Activity activity) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Handler main = new Handler(Looper.getMainLooper());

        exec.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (conn.getResponseCode() != 200) return;

                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                String tagName = json.optString("tag_name", "");
                String releaseName = json.optString("name", tagName);
                String changelog = json.optString("body", "");
                String apkUrl = findApkUrl(json);

                String currentVersion = currentVersion(activity);
                if (!isNewer(tagName, currentVersion)) return;

                main.post(() -> {
                    if (activity.isFinishing()) return;
                    showDialog(activity, releaseName, changelog, apkUrl);
                });

            } catch (Exception ignored) {}
        });
        exec.shutdown();
    }

    private static String findApkUrl(JSONObject json) {
        try {
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.endsWith(".apk")) {
                    return asset.getString("browser_download_url");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void showDialog(Activity activity, String version, String changelog, String apkUrl) {
        String msg = "Version " + version + " is available.\n\n" +
            (changelog.isEmpty() ? "" : changelog.substring(0, Math.min(changelog.length(), 400)));

        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle("Update Available");
        b.setMessage(msg);
        b.setNegativeButton("Later", null);

        if (apkUrl != null) {
            b.setPositiveButton("Download & Install", (d, w) -> downloadAndInstall(activity, apkUrl, version));
        } else {
            b.setPositiveButton("Open GitHub", (d, w) -> {
                String releasePage = "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest";
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(releasePage)));
            });
        }
        b.show();
    }

    private static void downloadAndInstall(Activity activity, String apkUrl, String version) {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
        req.setTitle("Downloading update " + version);
        req.setDescription("AllinOne update");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "allinone-update.apk");
        req.setMimeType("application/vnd.android.package-archive");

        DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        long downloadId = dm.enqueue(req);

        BroadcastReceiver onComplete = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                ctx.unregisterReceiver(this);

                Uri apkUri = dm.getUriForDownloadedFile(downloadId);
                if (apkUri == null) return;

                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                install.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(install);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(onComplete, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(onComplete, filter);
        }
    }

    private static String currentVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static boolean isNewer(String remote, String local) {
        int[] r = parseVersion(remote);
        int[] l = parseVersion(local);
        int len = Math.max(r.length, l.length);
        for (int i = 0; i < len; i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv > lv) return true;
            if (rv < lv) return false;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        v = v.replaceAll("[^0-9.]", "");
        String[] parts = v.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); } catch (Exception ignored) {}
        }
        return nums;
    }
}