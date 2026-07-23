package com.allinone.app.attendance;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSV backup and restore for the whole module.
 *
 * <p>Attendance and leave go into one file rather than two. A backup that comes in halves
 * is one a user can restore half of — and a month of attendance restored without its leave
 * records reads as a month of unexplained absences. A leading {@code record} column keeps
 * the two row shapes apart, so the file stays a single well-formed CSV that a spreadsheet
 * can still open.
 */
public final class AttendanceBackup {

    private AttendanceBackup() {}

    public static final String HEADER =
            "record,day,end_day,check_in_ms,check_out_ms,leave_type,note";

    private static final String REC_ATTENDANCE = "ATTENDANCE";
    private static final String REC_LEAVE      = "LEAVE";

    /** Outcome of a restore, so the caller can report what actually landed. */
    public static class Result {
        public int days;
        public int leaves;
        public int skipped;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /** @return how many rows were written. */
    public static int export(ContentResolver cr, Uri uri, AttendanceDb db) throws IOException {
        List<AttendanceRecord> days = db.listAllDays();
        List<LeaveRecord> leaves = db.listLeaves();

        try (OutputStream os = cr.openOutputStream(uri);
             OutputStreamWriter w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            w.write(HEADER + "\n");
            for (AttendanceRecord r : days) {
                w.write(REC_ATTENDANCE + ","
                        + csv(r.day) + ","
                        + ","                       // end_day — attendance is a single day
                        + r.checkInMs + ","
                        + r.checkOutMs + ","
                        + ","                       // leave_type
                        + csv(r.note) + "\n");
            }
            for (LeaveRecord l : leaves) {
                w.write(REC_LEAVE + ","
                        + csv(l.startDay) + ","
                        + csv(l.endDay) + ","
                        + "0,0,"                    // no punches on a leave row
                        + csv(l.type) + ","
                        + csv(l.reason) + "\n");
            }
        }
        return days.size() + leaves.size();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Restores rows from a previously exported file.
     *
     * <p>Attendance is upserted by day, so restoring over a live log corrects days that
     * clash instead of duplicating them. Leaves are matched on start+end+type for the same
     * reason — re-importing the same backup twice must not double the leave balance.
     */
    public static Result importFrom(ContentResolver cr, Uri uri, AttendanceDb db)
            throws IOException {
        Result res = new Result();

        List<LeaveRecord> existing = db.listLeaves();

        try (InputStream is = cr.openInputStream(uri);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line = r.readLine();
            if (line == null) return res;
            // Only skip the first line when it really is the header; a file that lost it
            // would otherwise silently drop its first record.
            if (!line.toLowerCase(Locale.ROOT).startsWith("record")) {
                apply(line, db, existing, res);
            }

            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) apply(line, db, existing, res);
            }
        }
        return res;
    }

    private static void apply(String line, AttendanceDb db,
                              List<LeaveRecord> existing, Result res) {
        String[] c = parseLine(line);
        if (c.length < 2) { res.skipped++; return; }

        String kind = c[0].trim().toUpperCase(Locale.ROOT);
        try {
            if (REC_LEAVE.equals(kind)) {
                LeaveRecord l = new LeaveRecord();
                l.startDay = c[1].trim();
                l.endDay   = c.length > 2 && !c[2].trim().isEmpty() ? c[2].trim() : l.startDay;
                l.type     = c.length > 5 && !c[5].trim().isEmpty() ? c[5].trim()
                                                                    : LeaveRecord.TYPES[0];
                l.reason   = c.length > 6 ? c[6] : "";
                if (!isDay(l.startDay)) { res.skipped++; return; }

                for (LeaveRecord e : existing) {
                    if (e.startDay.equals(l.startDay) && e.endDay.equals(l.endDay)
                            && e.type.equals(l.type)) {
                        res.skipped++;
                        return;
                    }
                }
                l.id = db.saveLeave(l);
                existing.add(l);
                res.leaves++;

            } else if (REC_ATTENDANCE.equals(kind)) {
                AttendanceRecord a = new AttendanceRecord();
                a.day = c[1].trim();
                if (!isDay(a.day)) { res.skipped++; return; }
                a.checkInMs  = num(c, 3);
                a.checkOutMs = num(c, 4);
                a.note       = c.length > 6 ? c[6] : "";
                // An imported row was authored somewhere else, so it is a manual entry by
                // definition — it did not come from tapping the buttons on this device.
                a.manual = true;

                AttendanceRecord prev = db.getDay(a.day);
                if (prev != null) a.id = prev.id;
                db.saveDay(a);
                res.days++;

            } else {
                res.skipped++;
            }
        } catch (Exception e) {
            res.skipped++;
        }
    }

    private static long num(String[] c, int i) {
        if (c.length <= i) return 0;
        String s = c[i].trim();
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    /** Cheap shape check — enough to reject a header or a stray line, not a date parser. */
    private static boolean isDay(String s) {
        return s != null && s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-';
    }

    // ── CSV ───────────────────────────────────────────────────────────────────

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    // A doubled quote inside a quoted field is one literal quote.
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }
}
