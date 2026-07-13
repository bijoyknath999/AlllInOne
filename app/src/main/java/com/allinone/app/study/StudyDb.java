package com.allinone.app.study;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** SQLite store for study plans, chapters, and the daily study log (streak + focus minutes). */
public class StudyDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "study.db";
    private static final int DB_VERSION = 1;

    private static final String T_PLANS = "plans";
    private static final String T_CHAPTERS = "chapters";
    private static final String T_DAYS = "study_days";

    public StudyDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_PLANS + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "subject TEXT," +
            "color INTEGER," +
            "start_ms INTEGER," +
            "target_days INTEGER," +
            "reminder_enabled INTEGER DEFAULT 1," +
            "reminder_hour INTEGER DEFAULT 19," +
            "reminder_minute INTEGER DEFAULT 0," +
            "created_at INTEGER)");

        db.execSQL("CREATE TABLE " + T_CHAPTERS + "(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "plan_id INTEGER," +
            "name TEXT," +
            "order_idx INTEGER," +
            "done INTEGER DEFAULT 0," +
            "done_ms INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + T_DAYS + "(" +
            "day TEXT PRIMARY KEY," +
            "focus_minutes INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 — nothing to migrate yet.
    }

    // ---------------- Plans ----------------

    public long insertPlan(StudyPlan p) {
        return getWritableDatabase().insert(T_PLANS, null, planValues(p));
    }

    public void updatePlan(StudyPlan p) {
        getWritableDatabase().update(T_PLANS, planValues(p), "id=?",
            new String[]{String.valueOf(p.id)});
    }

    public void deletePlan(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(T_CHAPTERS, "plan_id=?", new String[]{String.valueOf(id)});
        db.delete(T_PLANS, "id=?", new String[]{String.valueOf(id)});
    }

    public List<StudyPlan> getAllPlans() {
        List<StudyPlan> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_PLANS, null, null, null, null, null, "id DESC");
        while (c.moveToNext()) {
            StudyPlan p = planFromCursor(c);
            fillCounts(p);
            list.add(p);
        }
        c.close();
        return list;
    }

    public List<StudyPlan> getReminderPlans() {
        List<StudyPlan> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_PLANS, null, "reminder_enabled=1", null, null, null, null);
        while (c.moveToNext()) {
            StudyPlan p = planFromCursor(c);
            fillCounts(p);
            list.add(p);
        }
        c.close();
        return list;
    }

    public StudyPlan getPlan(long id) {
        Cursor c = getReadableDatabase().query(T_PLANS, null, "id=?",
            new String[]{String.valueOf(id)}, null, null, null);
        StudyPlan p = null;
        if (c.moveToFirst()) {
            p = planFromCursor(c);
            fillCounts(p);
        }
        c.close();
        return p;
    }

    public void setReminderEnabled(long id, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put("reminder_enabled", enabled ? 1 : 0);
        getWritableDatabase().update(T_PLANS, cv, "id=?", new String[]{String.valueOf(id)});
    }

    private void fillCounts(StudyPlan p) {
        p.totalChapters = countChapters(p.id, false);
        p.doneChapters = countChapters(p.id, true);
    }

    private int countChapters(long planId, boolean doneOnly) {
        String sel = doneOnly ? "plan_id=? AND done=1" : "plan_id=?";
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + T_CHAPTERS + " WHERE " + sel,
            new String[]{String.valueOf(planId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    private ContentValues planValues(StudyPlan p) {
        ContentValues cv = new ContentValues();
        cv.put("subject", p.subject);
        cv.put("color", p.color);
        cv.put("start_ms", p.startMs);
        cv.put("target_days", p.targetDays);
        cv.put("reminder_enabled", p.reminderEnabled ? 1 : 0);
        cv.put("reminder_hour", p.reminderHour);
        cv.put("reminder_minute", p.reminderMinute);
        cv.put("created_at", p.createdAt);
        return cv;
    }

    private StudyPlan planFromCursor(Cursor c) {
        StudyPlan p = new StudyPlan();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.subject = c.getString(c.getColumnIndexOrThrow("subject"));
        p.color = c.getInt(c.getColumnIndexOrThrow("color"));
        p.startMs = c.getLong(c.getColumnIndexOrThrow("start_ms"));
        p.targetDays = c.getInt(c.getColumnIndexOrThrow("target_days"));
        p.reminderEnabled = c.getInt(c.getColumnIndexOrThrow("reminder_enabled")) == 1;
        p.reminderHour = c.getInt(c.getColumnIndexOrThrow("reminder_hour"));
        p.reminderMinute = c.getInt(c.getColumnIndexOrThrow("reminder_minute"));
        p.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return p;
    }

    // ---------------- Chapters ----------------

    public long insertChapter(Chapter ch) {
        return getWritableDatabase().insert(T_CHAPTERS, null, chapterValues(ch));
    }

    public void updateChapter(Chapter ch) {
        getWritableDatabase().update(T_CHAPTERS, chapterValues(ch), "id=?",
            new String[]{String.valueOf(ch.id)});
    }

    public void deleteChapter(long id) {
        getWritableDatabase().delete(T_CHAPTERS, "id=?", new String[]{String.valueOf(id)});
    }

    /** Toggle done state, stamp the completion date, and record the study day for the streak. */
    public void setChapterDone(Chapter ch, boolean done) {
        ch.done = done;
        ch.doneMs = done ? System.currentTimeMillis() : 0;
        ContentValues cv = new ContentValues();
        cv.put("done", done ? 1 : 0);
        cv.put("done_ms", ch.doneMs);
        getWritableDatabase().update(T_CHAPTERS, cv, "id=?", new String[]{String.valueOf(ch.id)});
        if (done) recordStudyToday();
    }

    public List<Chapter> getChapters(long planId) {
        List<Chapter> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_CHAPTERS, null, "plan_id=?",
            new String[]{String.valueOf(planId)}, null, null, "order_idx ASC, id ASC");
        while (c.moveToNext()) list.add(chapterFromCursor(c));
        c.close();
        return list;
    }

    /** The next unfinished chapter (by order) — i.e. today's chapter to tackle. */
    public Chapter getNextChapter(long planId) {
        Cursor c = getReadableDatabase().query(T_CHAPTERS, null, "plan_id=? AND done=0",
            new String[]{String.valueOf(planId)}, null, null, "order_idx ASC, id ASC", "1");
        Chapter ch = null;
        if (c.moveToFirst()) ch = chapterFromCursor(c);
        c.close();
        return ch;
    }

    public int nextOrderIdx(long planId) {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT COALESCE(MAX(order_idx), -1) + 1 FROM " + T_CHAPTERS + " WHERE plan_id=?",
            new String[]{String.valueOf(planId)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    private ContentValues chapterValues(Chapter ch) {
        ContentValues cv = new ContentValues();
        cv.put("plan_id", ch.planId);
        cv.put("name", ch.name);
        cv.put("order_idx", ch.orderIdx);
        cv.put("done", ch.done ? 1 : 0);
        cv.put("done_ms", ch.doneMs);
        return cv;
    }

    private Chapter chapterFromCursor(Cursor c) {
        Chapter ch = new Chapter();
        ch.id = c.getLong(c.getColumnIndexOrThrow("id"));
        ch.planId = c.getLong(c.getColumnIndexOrThrow("plan_id"));
        ch.name = c.getString(c.getColumnIndexOrThrow("name"));
        ch.orderIdx = c.getInt(c.getColumnIndexOrThrow("order_idx"));
        ch.done = c.getInt(c.getColumnIndexOrThrow("done")) == 1;
        ch.doneMs = c.getLong(c.getColumnIndexOrThrow("done_ms"));
        return ch;
    }

    // ---------------- Study log (streak + focus) ----------------

    private static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /** Ensure today has a row (marks the day as "studied"). */
    public void recordStudyToday() {
        String key = dayKey(System.currentTimeMillis());
        getWritableDatabase().execSQL(
            "INSERT OR IGNORE INTO " + T_DAYS + "(day, focus_minutes) VALUES(?, 0)",
            new Object[]{key});
    }

    /** Add focus-session minutes to today's total (also marks the day studied). */
    public void addFocusMinutes(int minutes) {
        String key = dayKey(System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("INSERT OR IGNORE INTO " + T_DAYS + "(day, focus_minutes) VALUES(?, 0)",
            new Object[]{key});
        db.execSQL("UPDATE " + T_DAYS + " SET focus_minutes = focus_minutes + ? WHERE day=?",
            new Object[]{minutes, key});
    }

    public int getFocusMinutesToday() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT focus_minutes FROM " + T_DAYS + " WHERE day=?",
            new String[]{dayKey(System.currentTimeMillis())});
        int m = 0;
        if (c.moveToFirst()) m = c.getInt(0);
        c.close();
        return m;
    }

    /** Consecutive studied days ending today (or yesterday — grace until a full day is missed). */
    public int getStreak() {
        Cursor c = getReadableDatabase().rawQuery(
            "SELECT day FROM " + T_DAYS + " ORDER BY day DESC", null);
        java.util.HashSet<String> days = new java.util.HashSet<>();
        while (c.moveToNext()) days.add(c.getString(0));
        c.close();
        if (days.isEmpty()) return 0;

        Calendar cal = Calendar.getInstance();
        String today = dayKey(cal.getTimeInMillis());
        // If today isn't logged yet, start counting from yesterday so the streak stays alive.
        if (!days.contains(today)) {
            cal.add(Calendar.DAY_OF_MONTH, -1);
            if (!days.contains(dayKey(cal.getTimeInMillis()))) return 0;
        }
        int streak = 0;
        while (days.contains(dayKey(cal.getTimeInMillis()))) {
            streak++;
            cal.add(Calendar.DAY_OF_MONTH, -1);
        }
        return streak;
    }
}
