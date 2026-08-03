package com.allinone.app.expense;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Two-way sync between the money manager and a user-owned Google Sheet, through a
 * Google Apps Script Web App (see res/raw/sheets_sync_script.js). No Google SDK or
 * OAuth client is needed — the user deploys the script once and pastes its URL.
 *
 * <p>Push writes a full snapshot: transactions, categories, people, loans,
 * repayments, recurring rules and settings. Pull restores that snapshot onto a
 * fresh install, ids included, so loans keep their people and repayments.</p>
 *
 * <p>{@link #autoSync(Context)} is fired by {@link ExpenseDb} after every write and
 * debounces, so a burst of edits results in a single upload.</p>
 */
public class GoogleSheetsSync {

    public interface Callback { void onResult(boolean ok, String message); }

    private static final int VERSION = 2;
    /** A burst of edits (e.g. a CSV import) collapses into one upload. */
    private static final long AUTO_DELAY_MS = 2500;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Runnable pendingAuto;
    /** True while a restore is writing to the db — those writes must not bounce back. */
    private static volatile boolean suspended;

    private GoogleSheetsSync() {}

    // ── Auto upload ─────────────────────────────────────────────────────────────

    /**
     * Queues a debounced background upload. Does nothing when auto-sync is off, no
     * URL is configured, or a restore is in progress.
     */
    public static void autoSync(Context ctx) {
        if (ctx == null || suspended) return;
        final Context app = ctx.getApplicationContext();
        MoneyPrefs prefs = new MoneyPrefs(app);
        if (!prefs.isAutoSync()) return;
        final String url = prefs.getSheetsUrl();
        if (!url.startsWith("http")) return;

        synchronized (GoogleSheetsSync.class) {
            if (pendingAuto != null) MAIN.removeCallbacks(pendingAuto);
            pendingAuto = () -> {
                synchronized (GoogleSheetsSync.class) { pendingAuto = null; }
                push(app, url, true, null);
            };
            MAIN.postDelayed(pendingAuto, AUTO_DELAY_MS);
        }
    }

    /** Manual "Sync now" — uploads everything and reports the outcome. */
    public static void syncAll(Context ctx, String url, Callback cb) {
        push(ctx, url, false, cb);
    }

    // ── Push ────────────────────────────────────────────────────────────────────

    private static void push(Context ctx, final String url, final boolean auto, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                ExpenseDb db = new ExpenseDb(app);
                MoneyPrefs prefs = new MoneyPrefs(app);

                Snapshot snap = buildSnapshot(db, prefs);
                // Never let a blank device silently wipe a sheet full of history. This
                // only fires for auto-sync (no callback), but it still reports: the
                // progress dialog cannot be dismissed, so an unanswered callback would
                // strand the user on a spinner forever.
                if (auto && snap.total == 0) {
                    report(cb, false, "Nothing to upload yet");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("action", "push");
                payload.put("version", VERSION);
                payload.put("data", snap.data);

                Resp r = request(url, "POST", payload.toString());
                if (!r.isOk()) { report(cb, false, r.errorMessage()); return; }
                prefs.setLastSync(System.currentTimeMillis());
                String msg = "Uploaded " + confirmedSummary(r, snap);
                String warnings = warningsFrom(r);
                if (!warnings.isEmpty()) {
                    msg += "\n\nThe sheet refused these styling steps:\n" + warnings;
                }
                report(cb, true, msg);
            } catch (Exception ex) {
                report(cb, false, "Sync failed: " + ex.getMessage());
            }
        });
    }

    /** True when there is anything worth uploading (used to warn before a manual push). */
    public static boolean hasLocalData(Context ctx) {
        ExpenseDb db = new ExpenseDb(ctx.getApplicationContext());
        return !db.queryAll().isEmpty()
            || !db.queryLoans(0, null).isEmpty()
            || !db.queryRecurring().isEmpty()
            || !db.queryPeople().isEmpty();
    }

    private static class Snapshot {
        JSONObject data;
        int transactions, categories, people, loans, payments, recurring, total;

        String summary() {
            return transactions + " transactions • " + loans + " loans • "
                + payments + " repayments • " + recurring + " recurring • "
                + people + " people • " + categories + " categories";
        }
    }

    /**
     * Reports the row counts the sheet says it wrote, falling back to what was sent
     * only if the reply carries none. Quoting the sheet back means the message cannot
     * claim an upload that did not land.
     */
    private static String confirmedSummary(Resp r, Snapshot sent) {
        JSONObject o = r.json();
        JSONObject counts = o == null ? null : o.optJSONObject("counts");
        if (counts == null) return sent.summary();
        return counts.optInt("transactions") + " transactions • "
            + counts.optInt("loans") + " loans • "
            + counts.optInt("payments") + " repayments • "
            + counts.optInt("recurring") + " recurring • "
            + counts.optInt("people") + " people • "
            + counts.optInt("categories") + " categories";
    }

    /** Styling steps the sheet rejected. Empty when the design applied cleanly. */
    private static String warningsFrom(Resp r) {
        JSONObject o = r.json();
        JSONArray w = o == null ? null : o.optJSONArray("warnings");
        if (w == null || w.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length(); i++) sb.append("• ").append(w.optString(i)).append('\n');
        return sb.toString().trim();
    }

    private static Snapshot buildSnapshot(ExpenseDb db, MoneyPrefs prefs) throws Exception {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Snapshot s = new Snapshot();
        JSONObject data = new JSONObject();

        JSONArray tx = new JSONArray();
        for (Expense e : db.queryAll()) {
            JSONObject o = new JSONObject();
            o.put("id", e.id);
            o.put("date", sdf.format(new Date(e.dateMillis)));
            o.put("type", e.type == null ? Expense.TYPE_EXPENSE : e.type);
            o.put("category", nz(e.category));
            o.put("amount", e.amount);
            o.put("note", nz(e.note));
            o.put("date_ms", e.dateMillis);
            tx.put(o);
        }
        data.put("transactions", tx);
        s.transactions = tx.length();

        JSONArray cats = new JSONArray();
        for (Category c : db.queryCategories(null)) {
            JSONObject o = new JSONObject();
            o.put("id", c.id);
            o.put("name", nz(c.name));
            o.put("color", c.color);
            o.put("type", nz(c.type));
            o.put("budget", c.budget);
            cats.put(o);
        }
        data.put("categories", cats);
        s.categories = cats.length();

        JSONArray people = new JSONArray();
        for (Person p : db.queryPeople()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", nz(p.name));
            o.put("phone", nz(p.phone));
            o.put("note", nz(p.note));
            people.put(o);
        }
        data.put("people", people);
        s.people = people.length();

        JSONArray loans = new JSONArray();
        for (Loan l : db.queryLoans(0, null)) {
            JSONObject o = new JSONObject();
            o.put("id", l.id);
            o.put("person_id", l.personId);
            o.put("person_name", nz(l.personName));
            o.put("direction", nz(l.direction));
            o.put("principal", l.principal);
            o.put("paid", l.paid);
            o.put("outstanding", l.outstanding);
            o.put("date", sdf.format(new Date(l.dateMillis)));
            o.put("date_ms", l.dateMillis);
            o.put("due_ms", l.dueMillis);
            o.put("note", nz(l.note));
            o.put("status", nz(l.status));
            loans.put(o);
        }
        data.put("loans", loans);
        s.loans = loans.length();

        JSONArray pays = new JSONArray();
        for (LoanPayment p : db.queryAllPayments()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("loan_id", p.loanId);
            o.put("amount", p.amount);
            o.put("date", sdf.format(new Date(p.dateMillis)));
            o.put("date_ms", p.dateMillis);
            pays.put(o);
        }
        data.put("payments", pays);
        s.payments = pays.length();

        JSONArray rules = new JSONArray();
        for (RecurringRule r : db.queryRecurring()) {
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("type", nz(r.type));
            o.put("category", nz(r.category));
            o.put("amount", r.amount);
            o.put("note", nz(r.note));
            o.put("interval_type", nz(r.intervalType));
            o.put("interval_n", Math.max(1, r.intervalN));
            o.put("next_date", sdf.format(new Date(r.nextMillis)));
            o.put("next_ms", r.nextMillis);
            o.put("enabled", r.enabled ? 1 : 0);
            rules.put(o);
        }
        data.put("recurring", rules);
        s.recurring = rules.length();

        JSONObject settings = new JSONObject();
        settings.put("currency", prefs.getCurrency());
        settings.put("budget", prefs.getBudget());
        data.put("settings", settings);

        s.data = data;
        s.total = s.transactions + s.loans + s.payments + s.recurring + s.people;
        return s;
    }

    // ── Pull / restore ──────────────────────────────────────────────────────────

    /**
     * Downloads the sheet and replaces every local money record with it. Intended for
     * a fresh install — the caller should confirm with the user first.
     */
    public static void restore(Context ctx, final String url, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                String sep = url.contains("?") ? "&" : "?";
                Resp r = request(url + sep + "action=pull", "GET", null);
                if (!r.isOk()) { report(cb, false, r.errorMessage()); return; }

                JSONObject o = new JSONObject(r.body);
                if (!o.optBoolean("ok", false)) {
                    report(cb, false, "Sheet said: " + o.optString("error", "unknown error"));
                    return;
                }

                List<Expense> tx = new ArrayList<>();
                JSONArray a = o.optJSONArray("transactions");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    Expense e = new Expense();
                    e.id = id(j, "id");
                    e.type = Expense.TYPE_INCOME.equalsIgnoreCase(j.optString("type"))
                        ? Expense.TYPE_INCOME : Expense.TYPE_EXPENSE;
                    e.category = j.optString("category", "");
                    e.amount = j.optDouble("amount", 0);
                    e.note = j.optString("note", "");
                    e.dateMillis = j.optLong("date_ms", 0);
                    if (e.dateMillis > 0) tx.add(e);
                }

                List<Category> cats = new ArrayList<>();
                a = o.optJSONArray("categories");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    Category c = new Category();
                    c.id = id(j, "id");
                    c.name = j.optString("name", "");
                    c.color = (int) j.optLong("color", 0xFF607D8B);
                    c.type = Category.TYPE_INCOME.equalsIgnoreCase(j.optString("type"))
                        ? Category.TYPE_INCOME : Category.TYPE_EXPENSE;
                    c.budget = j.optDouble("budget", 0);
                    if (!c.name.isEmpty()) cats.add(c);
                }

                List<Person> people = new ArrayList<>();
                a = o.optJSONArray("people");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    Person p = new Person();
                    p.id = id(j, "id");
                    p.name = j.optString("name", "");
                    p.phone = j.optString("phone", "");
                    p.note = j.optString("note", "");
                    if (!p.name.isEmpty()) people.add(p);
                }

                List<Loan> loans = new ArrayList<>();
                a = o.optJSONArray("loans");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    Loan l = new Loan();
                    l.id = id(j, "id");
                    l.personId = id(j, "person_id");
                    l.personName = j.optString("person_name", "");
                    l.direction = Loan.BORROWED.equalsIgnoreCase(j.optString("direction"))
                        ? Loan.BORROWED : Loan.LENT;
                    l.principal = j.optDouble("principal", 0);
                    l.dateMillis = j.optLong("date_ms", 0);
                    l.dueMillis = j.optLong("due_ms", 0);
                    l.note = j.optString("note", "");
                    l.status = Loan.STATUS_SETTLED.equalsIgnoreCase(j.optString("status"))
                        ? Loan.STATUS_SETTLED : Loan.STATUS_OPEN;
                    if (l.principal > 0) loans.add(l);
                }

                List<LoanPayment> pays = new ArrayList<>();
                a = o.optJSONArray("payments");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    LoanPayment p = new LoanPayment();
                    p.id = id(j, "id");
                    p.loanId = id(j, "loan_id");
                    p.amount = j.optDouble("amount", 0);
                    p.dateMillis = j.optLong("date_ms", 0);
                    if (p.amount > 0) pays.add(p);
                }

                List<RecurringRule> rules = new ArrayList<>();
                a = o.optJSONArray("recurring");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject j = a.optJSONObject(i);
                    if (j == null) continue;
                    RecurringRule r2 = new RecurringRule();
                    r2.id = id(j, "id");
                    r2.type = Expense.TYPE_INCOME.equalsIgnoreCase(j.optString("type"))
                        ? Expense.TYPE_INCOME : Expense.TYPE_EXPENSE;
                    r2.category = j.optString("category", "");
                    r2.amount = j.optDouble("amount", 0);
                    r2.note = j.optString("note", "");
                    r2.intervalType = j.optString("interval_type", RecurringRule.MONTHLY).toUpperCase(Locale.US);
                    r2.intervalN = Math.max(1, j.optInt("interval_n", 1));
                    r2.nextMillis = j.optLong("next_ms", 0);
                    r2.enabled = truthy(j.opt("enabled"));
                    if (r2.nextMillis > 0) rules.add(r2);
                }

                relink(people, loans, pays);

                ExpenseDb db = new ExpenseDb(app);
                suspended = true;
                try {
                    db.restoreAll(tx, cats, people, loans, pays, rules);
                } finally {
                    suspended = false;
                }

                MoneyPrefs prefs = new MoneyPrefs(app);
                JSONObject settings = o.optJSONObject("settings");
                if (settings != null) {
                    String cur = settings.optString("currency", "");
                    if (!cur.isEmpty()) prefs.setCurrency(cur);
                    double budget = settings.optDouble("budget", -1);
                    if (budget > 0) prefs.setBudget(budget);
                }
                prefs.setLastSync(System.currentTimeMillis());

                final String msg = "Restored " + tx.size() + " transactions • " + loans.size()
                    + " loans • " + pays.size() + " repayments • " + rules.size()
                    + " recurring • " + people.size() + " people";
                report(cb, true, msg);
            } catch (Exception ex) {
                suspended = false;
                report(cb, false, "Restore failed: " + ex.getMessage());
            }
        });
    }

    /**
     * Fills in missing ids and repairs the relations before anything is written. Rows
     * typed straight into the sheet have no id at all, and a person id can go missing
     * if the sheet mangled the column — loans are then re-matched on person name, so
     * a loan is never orphaned or dropped.
     */
    private static void relink(List<Person> people, List<Loan> loans, List<LoanPayment> pays) {
        long nextPersonId = 1;
        for (Person p : people) nextPersonId = Math.max(nextPersonId, p.id + 1);

        Map<String, Long> byName = new HashMap<>();
        Set<Long> personIds = new HashSet<>();
        for (Person p : people) {
            if (p.id <= 0) p.id = nextPersonId++;
            personIds.add(p.id);
            byName.put(p.name.trim().toLowerCase(Locale.US), p.id);
        }

        for (Loan l : loans) {
            if (personIds.contains(l.personId)) continue;
            String key = l.personName.trim().toLowerCase(Locale.US);
            Long match = byName.get(key);
            if (match == null) {
                Person p = new Person();
                p.id = nextPersonId++;
                p.name = l.personName.trim().isEmpty() ? "(unknown)" : l.personName.trim();
                people.add(p);
                personIds.add(p.id);
                byName.put(p.name.toLowerCase(Locale.US), p.id);
                match = p.id;
            }
            l.personId = match;
        }

        long nextLoanId = 1;
        for (Loan l : loans) nextLoanId = Math.max(nextLoanId, l.id + 1);
        Set<Long> loanIds = new HashSet<>();
        for (Loan l : loans) {
            if (l.id <= 0) l.id = nextLoanId++;
            loanIds.add(l.id);
        }
        // A repayment has nothing but its loan id to go on, so one pointing nowhere
        // cannot be placed and is left out rather than attached to the wrong loan.
        for (int i = pays.size() - 1; i >= 0; i--) {
            if (!loanIds.contains(pays.get(i).loanId)) pays.remove(i);
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────

    private static class Resp {
        int code;
        String body = "";

        /**
         * Only the script's own {"ok":true,…} counts as success. Google answers with a
         * sign-in or permission page — HTTP 200, HTML body — when a Web App is not
         * shared with "Anyone", and treating that as success reported uploads that
         * never happened.
         */
        boolean isOk() {
            if (code < 200 || code >= 400) return false;
            return json() != null && json().optBoolean("ok", false);
        }

        private JSONObject parsed;
        private boolean parseTried;

        JSONObject json() {
            if (!parseTried) {
                parseTried = true;
                try {
                    parsed = new JSONObject(body == null ? "" : body.trim());
                } catch (Exception e) {
                    parsed = null; // not our script's reply at all
                }
            }
            return parsed;
        }

        String errorMessage() {
            if (code < 200 || code >= 400) {
                return "Sheet rejected the request (HTTP " + code
                    + "). Check the Web App URL and that access is set to \"Anyone\".";
            }
            JSONObject o = json();
            if (o == null) {
                String t = body == null ? "" : body.trim();
                if (t.startsWith("<") || t.toLowerCase(Locale.US).contains("<html")) {
                    return "The URL returned a web page instead of data, so nothing was saved. "
                        + "Re-deploy with \"Who has access: Anyone\", and check you pasted the "
                        + "/exec Web App URL.";
                }
                return "Unexpected reply from the sheet: " + trim(t);
            }
            // The script wraps its own failures as {"ok":false,"error":"…"} — show that
            // text rather than the raw JSON, since it names the tab that failed.
            String err = o.optString("error", "");
            return err.isEmpty() ? ("The script reported an error: " + trim(body))
                                 : ("Script error — " + trim(err));
        }

        private String trim(String s) {
            s = s == null ? "" : s.trim();
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        }
    }

    private static Resp request(String urlStr, String method, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(true); // Apps Script 302s to googleusercontent
        conn.setConnectTimeout(15000);
        // A full push rewrites seven tabs and restyles them; Apps Script itself
        // allows six minutes, so a 30s client timeout gave up while it was working.
        conn.setReadTimeout(120000);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Resp r = new Resp();
        r.code = conn.getResponseCode();
        try (InputStream is = r.code < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            if (is != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                r.body = bos.toString("UTF-8");
            }
        } catch (Exception ignored) {
        } finally {
            conn.disconnect();
        }
        return r;
    }

    // ── Small helpers ───────────────────────────────────────────────────────────

    private static void report(final Callback cb, final boolean ok, final String msg) {
        if (cb == null) return;
        MAIN.post(() -> cb.onResult(ok, msg));
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * Reads a row id. Ids are plain row numbers, so anything outside that range is a
     * corrupted cell — most often a column left with a date format, which hands back
     * a timestamp instead of the number. Those become 0, i.e. "assign a fresh id".
     */
    private static long id(JSONObject j, String key) {
        long v = j.optLong(key, 0);
        return (v > 0 && v < 1000000000L) ? v : 0;
    }

    /** Sheet cells may hold true / TRUE / 1 / "yes" for a boolean column. */
    private static boolean truthy(Object v) {
        if (v == null) return true;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String s = String.valueOf(v).trim();
        return !(s.equalsIgnoreCase("false") || s.equals("0") || s.equalsIgnoreCase("no") || s.isEmpty());
    }
}
