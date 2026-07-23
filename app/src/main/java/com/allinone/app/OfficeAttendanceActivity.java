package com.allinone.app;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.attendance.AttendanceBackup;
import com.allinone.app.attendance.AttendanceDb;
import com.allinone.app.attendance.AttendanceRecord;
import com.allinone.app.attendance.LeaveActivity;
import com.allinone.app.attendance.LeaveRecord;
import com.allinone.app.databinding.ActivityOfficeAttendanceBinding;
import com.allinone.app.expense.UiKit;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Office attendance: punch in and out, correct any day by hand, and back the whole log up
 * to CSV.
 *
 * <p>The punch buttons are the fast path, but they are not the source of truth — a phone
 * left in a bag, a flat battery or a forgotten tap all produce days that are wrong, and a
 * log you cannot correct is one people stop trusting and stop using. Every day on this
 * screen is therefore editable through the same dialog, whether it came from a button or
 * from the keyboard.
 */
public class OfficeAttendanceActivity extends AppCompatActivity {

    private ActivityOfficeAttendanceBinding b;
    private AttendanceDb db;

    /** Month currently listed, as a calendar pinned to its first day. */
    private final Calendar month = Calendar.getInstance();

    private final ActivityResultLauncher<String> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    uri -> { if (uri != null) doExport(uri); });

    private final ActivityResultLauncher<String> importLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(),
                    uri -> { if (uri != null) confirmImport(uri); });

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityOfficeAttendanceBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());

        db = new AttendanceDb(this);
        month.set(Calendar.DAY_OF_MONTH, 1);

        b.btnBack.setOnClickListener(v -> finish());
        b.btnAdd.setOnClickListener(v -> pickDayToEdit());
        b.btnPunch.setOnClickListener(v -> punch());
        b.tvFixToday.setOnClickListener(v -> editDay(AttendanceRecord.today()));
        b.btnLeave.setOnClickListener(v -> startActivity(new Intent(this, LeaveActivity.class)));
        b.btnBackup.setOnClickListener(v -> showBackupSheet());

        b.btnPrevMonth.setOnClickListener(v -> shiftMonth(-1));
        b.btnNextMonth.setOnClickListener(v -> shiftMonth(1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Leave added on the other screen changes both the summary and today's state.
        render();
    }

    private void shiftMonth(int delta) {
        month.add(Calendar.MONTH, delta);
        render();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    private void render() {
        renderToday();
        renderMonth();
    }

    private void renderToday() {
        String today = AttendanceRecord.today();
        AttendanceRecord r = db.getDay(today);
        LeaveRecord leave = db.leaveOn(today);

        b.tvTodayDate.setText(AttendanceRecord.formatDay(today).toUpperCase(Locale.getDefault()));
        b.tvInTime.setText(r == null ? "—" : AttendanceRecord.formatTime(r.checkInMs));
        b.tvOutTime.setText(r == null ? "—" : AttendanceRecord.formatTime(r.checkOutMs));
        b.tvWorked.setText(r == null ? "—" : AttendanceRecord.formatDuration(r.workedMinutes()));

        boolean hasIn  = r != null && r.hasCheckIn();
        boolean hasOut = r != null && r.hasCheckOut();

        if (hasIn && hasOut) {
            b.tvTodayState.setText(R.string.att_state_done);
            b.tvPunch.setText(R.string.att_btn_done);
            // Nothing left to punch: keep the button visible so the card does not jump, but
            // stop it accepting taps that could only produce a duplicate.
            b.btnPunch.setEnabled(false);
            b.btnPunch.setAlpha(0.45f);
        } else if (hasIn) {
            b.tvTodayState.setText(R.string.att_state_in);
            b.tvPunch.setText(R.string.att_btn_check_out);
            b.btnPunch.setEnabled(true);
            b.btnPunch.setAlpha(1f);
        } else {
            b.tvTodayState.setText(leave != null
                    ? getString(R.string.att_state_leave) : getString(R.string.att_state_not_in));
            b.tvPunch.setText(R.string.att_btn_check_in);
            b.btnPunch.setEnabled(true);
            b.btnPunch.setAlpha(1f);
        }
    }

    private void renderMonth() {
        int year = month.get(Calendar.YEAR);
        int mon  = month.get(Calendar.MONTH);

        b.tvMonth.setText(new SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                .format(month.getTime()));

        AttendanceDb.MonthStats st = db.monthStats(year, mon);
        StringBuilder sum = new StringBuilder();
        sum.append("Present ").append(st.presentDays)
           .append("   •   Hours ").append(AttendanceRecord.formatDuration(st.totalMinutes))
           .append("   •   Leave ").append(st.leaveDays);
        if (st.incompleteDays > 0) {
            sum.append("\n").append(st.incompleteDays)
               .append(st.incompleteDays == 1 ? " day is missing a check-out"
                                              : " days are missing a check-out");
        }
        if (st.presentDays > 0) {
            sum.append("\nAverage ").append(AttendanceRecord.formatDuration(st.averageMinutes()))
               .append(" per day");
        }
        b.tvMonthSummary.setText(sum);

        String from = AttendanceDb.monthFirstDay(year, mon);
        String to   = AttendanceDb.monthLastDay(year, mon);
        List<AttendanceRecord> days = db.listDays(from, to);
        List<LeaveRecord> leaves = db.listLeavesOverlapping(from, to);

        b.llList.removeAllViews();
        if (days.isEmpty() && leaves.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, getString(R.string.att_empty)));
            return;
        }

        b.llList.addView(UiKit.heading(this, "Days"));
        if (days.isEmpty()) {
            b.llList.addView(UiKit.emptyHint(this, "No days recorded."));
        }
        for (final AttendanceRecord r : days) {
            b.llList.addView(dayRow(r));
        }

        if (!leaves.isEmpty()) {
            b.llList.addView(UiKit.heading(this, "Leave this month"));
            for (final LeaveRecord l : leaves) {
                b.llList.addView(UiKit.row(this, getColor(R.color.att_leave),
                        l.type + " • " + l.daysWithin(from, to) + "d",
                        l.rangeLabel() + (l.reason.isEmpty() ? "" : "\n" + l.reason),
                        null, 0,
                        v -> startActivity(new Intent(this, LeaveActivity.class))));
            }
        }
    }

    private View dayRow(final AttendanceRecord r) {
        String times = AttendanceRecord.formatTime(r.checkInMs)
                + "  →  " + AttendanceRecord.formatTime(r.checkOutMs);
        if (r.manual) times += "   (edited)";
        if (!r.note.isEmpty()) times += "\n" + r.note;

        int dot = r.isComplete() ? getColor(R.color.att_in) : getColor(R.color.att_out);
        String right = r.isComplete()
                ? AttendanceRecord.formatDuration(r.workedMinutes()) : "open";

        return UiKit.row(this, dot,
                AttendanceRecord.formatDay(r.day), times,
                right, r.isComplete() ? getColor(R.color.text_primary)
                                      : getColor(R.color.att_out),
                v -> editDay(r.day));
    }

    // ── Punching ──────────────────────────────────────────────────────────────

    private void punch() {
        long now = System.currentTimeMillis();
        AttendanceRecord r = db.getToday();

        if (r == null || !r.hasCheckIn()) {
            if (db.checkIn(now)) {
                toast("Checked in at " + AttendanceRecord.formatTime(now));
            }
        } else if (!r.hasCheckOut()) {
            if (db.checkOut(now)) {
                AttendanceRecord after = db.getToday();
                toast("Checked out — " + AttendanceRecord.formatDuration(after.workedMinutes()));
            }
        }
        render();
    }

    // ── Manual entry / correction ─────────────────────────────────────────────

    /** Date picker first, then the usual edit dialog for whatever day was chosen. */
    private void pickDayToEdit() {
        Calendar c = Calendar.getInstance();
        new DatePickerDialog(this, (view, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, d, 0, 0, 0);
            editDay(AttendanceRecord.dayKey(picked.getTimeInMillis()));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    /**
     * Edits one day's punches.
     *
     * <p>Times are held in a scratch record and only written on save, so backing out of the
     * dialog cannot leave a day half-corrected.
     */
    private void editDay(final String day) {
        AttendanceRecord existing = db.getDay(day);
        final AttendanceRecord draft = new AttendanceRecord(day);
        if (existing != null) {
            draft.id         = existing.id;
            draft.checkInMs  = existing.checkInMs;
            draft.checkOutMs = existing.checkOutMs;
            draft.note       = existing.note;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final TextView tvIn  = timeRow(box, "Check in");
        final TextView tvOut = timeRow(box, "Check out");

        final EditText etNote = new EditText(this);
        etNote.setHint("Note (optional)");
        etNote.setText(draft.note);
        etNote.setTextColor(getColor(R.color.text_primary));
        etNote.setHintTextColor(getColor(R.color.text_hint));
        etNote.setBackgroundResource(R.drawable.bg_input_field);
        etNote.setTextSize(13f);
        int ip = UiKit.dp(this, 12);
        etNote.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = UiKit.dp(this, 12);
        etNote.setLayoutParams(nlp);
        box.addView(etNote);

        tvIn.setText(AttendanceRecord.formatTime(draft.checkInMs));
        tvOut.setText(AttendanceRecord.formatTime(draft.checkOutMs));

        tvIn.setOnClickListener(v -> pickTime(day, draft.checkInMs, ms -> {
            draft.checkInMs = ms;
            tvIn.setText(AttendanceRecord.formatTime(ms));
        }));
        tvOut.setOnClickListener(v -> pickTime(day, draft.checkOutMs, ms -> {
            draft.checkOutMs = ms;
            tvOut.setText(AttendanceRecord.formatTime(ms));
        }));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(AttendanceRecord.formatDay(day))
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    draft.note = etNote.getText().toString().trim();
                    if (!draft.hasCheckIn() && !draft.hasCheckOut() && draft.note.isEmpty()) {
                        toast("Nothing to save");
                        return;
                    }
                    // A check-out before the check-in would silently score as zero hours;
                    // refusing it here keeps every total on the screen above trustworthy.
                    if (draft.isComplete() && draft.checkOutMs <= draft.checkInMs) {
                        toast("Check-out must be after check-in");
                        return;
                    }
                    draft.manual = true;
                    db.saveDay(draft);
                    render();
                    toast("Saved");
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                db.deleteDay(existing.id);
                render();
                toast("Day removed");
            });
        }
        builder.show();
    }

    /** A tappable "label / value" line inside the edit dialog. */
    private TextView timeRow(LinearLayout parent, String label) {
        TextView lab = new TextView(this);
        lab.setText(label.toUpperCase(Locale.getDefault()));
        lab.setTextColor(getColor(R.color.text_hint));
        lab.setTextSize(10f);
        lab.setLetterSpacing(0.1f);
        lab.setPadding(0, UiKit.dp(this, 10), 0, 0);
        parent.addView(lab);

        TextView val = new TextView(this);
        val.setTextColor(getColor(R.color.att_accent));
        val.setTextSize(17f);
        val.setPadding(0, UiKit.dp(this, 2), 0, 0);
        val.setClickable(true);
        val.setFocusable(true);
        parent.addView(val);
        return val;
    }

    private interface OnTime { void picked(long ms); }

    /**
     * Time picker anchored to {@code day}, not to today.
     *
     * <p>The stored value is a full timestamp, so a time chosen for a past date has to be
     * combined with that date — using the current date instead would file the correction
     * under the wrong day.
     */
    private void pickTime(String day, long current, final OnTime cb) {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date(current > 0 ? current : AttendanceRecord.dayStartMs(day)));
        if (current <= 0) c.set(Calendar.HOUR_OF_DAY, 9);

        new TimePickerDialog(this, (view, hour, minute) -> {
            Calendar out = Calendar.getInstance();
            out.setTimeInMillis(AttendanceRecord.dayStartMs(day));
            out.set(Calendar.HOUR_OF_DAY, hour);
            out.set(Calendar.MINUTE, minute);
            out.set(Calendar.SECOND, 0);
            out.set(Calendar.MILLISECOND, 0);
            cb.picked(out.getTimeInMillis());
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
    }

    // ── Backup ────────────────────────────────────────────────────────────────

    private void showBackupSheet() {
        new AlertDialog.Builder(this)
                .setTitle("Backup & restore")
                .setItems(new String[]{"Export everything to CSV", "Import from CSV"},
                        (d, which) -> {
                            if (which == 0) {
                                String name = "attendance_backup_"
                                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                                .format(new Date()) + ".csv";
                                exportLauncher.launch(name);
                            } else {
                                importLauncher.launch("text/*");
                            }
                        })
                .setNegativeButton("Close", null)
                .show();
    }

    private void doExport(Uri uri) {
        try {
            int rows = AttendanceBackup.export(getContentResolver(), uri, db);
            toast("Exported " + rows + " record(s)");
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void confirmImport(final Uri uri) {
        // Import overwrites same-day records, so say so before it happens rather than after.
        new AlertDialog.Builder(this)
                .setTitle("Import backup?")
                .setMessage("Days already in your log will be replaced by the file's version. "
                        + "Leave entries that match one you already have are skipped.")
                .setPositiveButton("Import", (d, w) -> doImport(uri))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doImport(Uri uri) {
        try {
            AttendanceBackup.Result r =
                    AttendanceBackup.importFrom(getContentResolver(), uri, db);
            render();
            String msg = "Imported " + r.days + " day(s), " + r.leaves + " leave(s)";
            if (r.skipped > 0) msg += " • " + r.skipped + " skipped";
            toast(msg);
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
