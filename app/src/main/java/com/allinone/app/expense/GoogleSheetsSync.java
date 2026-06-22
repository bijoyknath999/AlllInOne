package com.allinone.app.expense;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syncs all transactions to a user-owned Google Sheet via a Google Apps Script
 * Web App endpoint. No Google SDK / OAuth client needed — the user deploys a tiny
 * script (see SettingsActivity setup guide) and pastes its URL.
 */
public class GoogleSheetsSync {

    public interface Callback { void onResult(boolean ok, String message); }

    public static void syncAll(final Context ctx, final String url, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                ExpenseDb db = new ExpenseDb(ctx);
                List<Expense> all = db.queryAll();
                Map<Long, String> accNames = new HashMap<>();
                for (Account a : db.queryAccounts(true)) accNames.put(a.id, a.name);

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                JSONArray rows = new JSONArray();
                for (Expense e : all) {
                    JSONArray row = new JSONArray();
                    row.put(sdf.format(new Date(e.dateMillis)));
                    row.put(e.type == null ? "EXPENSE" : e.type);
                    row.put(e.category == null ? "" : e.category);
                    row.put(e.amount);
                    row.put(e.note == null ? "" : e.note);
                    String acc = accNames.containsKey(e.accountId) ? accNames.get(e.accountId) : "";
                    row.put(acc);
                    row.put(e.dateMillis);
                    rows.put(row);
                }
                JSONObject payload = new JSONObject();
                payload.put("type", "transactions");
                payload.put("rows", rows);

                int code = post(url, payload.toString());
                final boolean ok = code >= 200 && code < 400;
                final int count = all.size();
                main.post(() -> cb.onResult(ok, ok
                    ? ("Synced " + count + " transactions to Google Sheets")
                    : ("Sheet rejected the request (HTTP " + code + "). Check the URL & deployment access.")));
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                main.post(() -> cb.onResult(false, "Sync failed: " + msg));
            }
        });
        exec.shutdown();
    }

    private static int post(String urlStr, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true); // Apps Script 302-redirects to googleusercontent
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        // Drain the stream so the connection can be reused / closed cleanly.
        try (InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                while (r.readLine() != null) { /* ignore */ }
            }
        } catch (Exception ignored) {}
        conn.disconnect();
        return code;
    }
}
