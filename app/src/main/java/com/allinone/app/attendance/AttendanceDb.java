package com.allinone.app.attendance;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** SQLite store for the office attendance log and the leave register. */
public class AttendanceDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "attendance.db";
    private static final int    DB_VERSION = 1;

    private static final String T_DAYS   = "attendance_days";
    private static final String T_LEAVES = "leaves";

    public AttendanceDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // day is UNIQUE, not just indexed: it is what stops a double tap on Check In from
        // creating a second row for the same date that the summaries would then count twice.
        db.execSQL("CREATE TABLE " + T_DAYS + "(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "day TEXT UNIQUE," +
                "check_in_ms INTEGER DEFAULT 0," +
                "check_out_ms INTEGER DEFAULT 0," +
                "note TEXT," +
                "manual INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE " + T_LEAVES + "(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_day TEXT," +
                "end_day TEXT," +
                "type TEXT," +
                "reason TEXT," +
                "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 — nothing to migrate yet.
    }

    // ── Attendance ────────────────────────────────────────────────────────────

    /** The record for a day, or null when nothing was logged. */
    public AttendanceRecord getDay(String day) {
        Cursor c = getReadableDatabase().query(T_DAYS, null, "day=?",
                new String[]{day}, null, null, null);
        AttendanceRecord r = c.moveToFirst() ? dayFromCursor(c) : null;
        c.close();
        return r;
    }

    public AttendanceRecord getToday() {
        return getDay(AttendanceRecord.today());
    }

    /**
     * Writes a day, replacing whatever was there.
     *
     * <p>Keyed on the day string rather than the row id so that a record built by the
     * import, by a manual correction, or by the punch buttons all land on the same row.
     */
    public long saveDay(AttendanceRecord r) {
        ContentValues v = new ContentValues();
        v.put("day", r.day);
        v.put("check_in_ms", r.checkInMs);
        v.put("check_out_ms", r.checkOutMs);
        v.put("note", r.note == null ? "" : r.note);
        v.put("manual", r.manual ? 1 : 0);

        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update(T_DAYS, v, "day=?", new String[]{r.day});
        if (updated > 0) return r.id;
        return db.insert(T_DAYS, null, v);
    }

    public void deleteDay(long id) {
        getWritableDatabase().delete(T_DAYS, "id=?", new String[]{String.valueOf(id)});
    }

    /**
     * Stamps a check-in for today.
     *
     * @return false when today already has one — re-punching would overwrite the real
     *         arrival time with a later one, which is a silent data loss rather than a
     *         no-op. The caller should offer a manual edit instead.
     */
    public boolean checkIn(long ms) {
        AttendanceRecord r = getToday();
        if (r != null && r.hasCheckIn()) return false;
        if (r == null) r = new AttendanceRecord(AttendanceRecord.today());
        r.checkInMs = ms;
        saveDay(r);
        return true;
    }

    /** Stamps a check-out for today. False when there is no check-in, or it already exists. */
    public boolean checkOut(long ms) {
        AttendanceRecord r = getToday();
        if (r == null || !r.hasCheckIn() || r.hasCheckOut()) return false;
        r.checkOutMs = ms;
        saveDay(r);
        return true;
    }

    /** Every day in an inclusive {@code yyyy-MM-dd} range, newest first. */
    public List<AttendanceRecord> listDays(String fromDay, String toDay) {
        List<AttendanceRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_DAYS, null, "day>=? AND day<=?",
                new String[]{fromDay, toDay}, null, null, "day DESC");
        while (c.moveToNext()) out.add(dayFromCursor(c));
        c.close();
        return out;
    }

    public List<AttendanceRecord> listAllDays() {
        List<AttendanceRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_DAYS, null, null, null, null, null, "day DESC");
        while (c.moveToNext()) out.add(dayFromCursor(c));
        c.close();
        return out;
    }

    private AttendanceRecord dayFromCursor(Cursor c) {
        AttendanceRecord r = new AttendanceRecord();
        r.id         = c.getLong(c.getColumnIndexOrThrow("id"));
        r.day        = c.getString(c.getColumnIndexOrThrow("day"));
        r.checkInMs  = c.getLong(c.getColumnIndexOrThrow("check_in_ms"));
        r.checkOutMs = c.getLong(c.getColumnIndexOrThrow("check_out_ms"));
        r.note       = c.getString(c.getColumnIndexOrThrow("note"));
        r.manual     = c.getInt(c.getColumnIndexOrThrow("manual")) == 1;
        if (r.note == null) r.note = "";
        return r;
    }

    // ── Leaves ────────────────────────────────────────────────────────────────

    public long saveLeave(LeaveRecord l) {
        ContentValues v = new ContentValues();
        v.put("start_day", l.startDay);
        v.put("end_day", l.endDay);
        v.put("type", l.type);
        v.put("reason", l.reason == null ? "" : l.reason);
        v.put("created_at", l.createdAt == 0 ? System.currentTimeMillis() : l.createdAt);

        SQLiteDatabase db = getWritableDatabase();
        if (l.id > 0) {
            db.update(T_LEAVES, v, "id=?", new String[]{String.valueOf(l.id)});
            return l.id;
        }
        return db.insert(T_LEAVES, null, v);
    }

    public void deleteLeave(long id) {
        getWritableDatabase().delete(T_LEAVES, "id=?", new String[]{String.valueOf(id)});
    }

    /** Every leave, most recent start date first. */
    public List<LeaveRecord> listLeaves() {
        List<LeaveRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_LEAVES, null, null, null, null, null,
                "start_day DESC");
        while (c.moveToNext()) out.add(leaveFromCursor(c));
        c.close();
        return out;
    }

    /** Leaves overlapping an inclusive day range. */
    public List<LeaveRecord> listLeavesOverlapping(String fromDay, String toDay) {
        List<LeaveRecord> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_LEAVES, null, "end_day>=? AND start_day<=?",
                new String[]{fromDay, toDay}, null, null, "start_day DESC");
        while (c.moveToNext()) out.add(leaveFromCursor(c));
        c.close();
        return out;
    }

    /** True when any leave covers the day — used to label a day as absent-by-design. */
    public LeaveRecord leaveOn(String day) {
        List<LeaveRecord> list = listLeavesOverlapping(day, day);
        return list.isEmpty() ? null : list.get(0);
    }

    private LeaveRecord leaveFromCursor(Cursor c) {
        LeaveRecord l = new LeaveRecord();
        l.id        = c.getLong(c.getColumnIndexOrThrow("id"));
        l.startDay  = c.getString(c.getColumnIndexOrThrow("start_day"));
        l.endDay    = c.getString(c.getColumnIndexOrThrow("end_day"));
        l.type      = c.getString(c.getColumnIndexOrThrow("type"));
        l.reason    = c.getString(c.getColumnIndexOrThrow("reason"));
        l.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        if (l.reason == null) l.reason = "";
        if (l.type == null) l.type = LeaveRecord.TYPES[0];
        return l;
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    /** Totals for one month, for the strip above the day list. */
    public static class MonthStats {
        public int presentDays;
        public int incompleteDays;
        public int leaveDays;
        public int totalMinutes;

        public int averageMinutes() {
            return presentDays == 0 ? 0 : totalMinutes / presentDays;
        }
    }

    public MonthStats monthStats(int year, int zeroBasedMonth) {
        String from = monthFirstDay(year, zeroBasedMonth);
        String to   = monthLastDay(year, zeroBasedMonth);

        MonthStats s = new MonthStats();
        for (AttendanceRecord r : listDays(from, to)) {
            if (r.isComplete()) {
                s.presentDays++;
                s.totalMinutes += r.workedMinutes();
            } else if (r.hasCheckIn()) {
                // Checked in but never out — present, but with no measurable hours. Counting
                // it as present would drag the average down towards zero.
                s.incompleteDays++;
            }
        }
        for (LeaveRecord l : listLeavesOverlapping(from, to)) {
            s.leaveDays += l.daysWithin(from, to);
        }
        return s;
    }

    public void deleteAllDays()   { getWritableDatabase().delete(T_DAYS, null, null); }
    public void deleteAllLeaves() { getWritableDatabase().delete(T_LEAVES, null, null); }

    // ── Month helpers ─────────────────────────────────────────────────────────

    public static String monthFirstDay(int year, int zeroBasedMonth) {
        Calendar c = Calendar.getInstance();
        c.set(year, zeroBasedMonth, 1, 0, 0, 0);
        return AttendanceRecord.dayKey(c.getTimeInMillis());
    }

    public static String monthLastDay(int year, int zeroBasedMonth) {
        Calendar c = Calendar.getInstance();
        c.set(year, zeroBasedMonth, 1, 0, 0, 0);
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        return AttendanceRecord.dayKey(c.getTimeInMillis());
    }
}
