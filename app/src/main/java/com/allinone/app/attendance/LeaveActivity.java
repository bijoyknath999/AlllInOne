package com.allinone.app.attendance;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivityLeaveBinding;
import com.allinone.app.expense.UiKit;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The leave register: every absence as an inclusive date range, with a per-type tally.
 *
 * <p>Deliberately a plain record rather than a request workflow — there is no approver on a
 * personal phone, so states like "pending" would never advance and would only ever be
 * noise. What matters here is how many days of each type have gone, which the summary
 * answers directly.
 */
public class LeaveActivity extends AppCompatActivity {

    private ActivityLeaveBinding b;
    private AttendanceDb db;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityLeaveBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());

        db = new AttendanceDb(this);
        b.btnBack.setOnClickListener(v -> finish());
        b.btnAdd.setOnClickListener(v -> edit(null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        List<LeaveRecord> all = db.listLeaves();
        b.llList.removeAllViews();

        if (all.isEmpty()) {
            b.tvSummary.setText("");
            b.llList.addView(UiKit.emptyHint(this, getString(R.string.att_leave_empty)));
            return;
        }

        // Tally by type, keeping the order they appear in so the summary is stable between
        // renders rather than reshuffling as records are added.
        Map<String, Integer> byType = new LinkedHashMap<>();
        int total = 0;
        for (LeaveRecord l : all) {
            int d = l.days();
            total += d;
            Integer prev = byType.get(l.type);
            byType.put(l.type, (prev == null ? 0 : prev) + d);
        }

        StringBuilder sum = new StringBuilder(total + " day(s) total");
        for (Map.Entry<String, Integer> e : byType.entrySet()) {
            sum.append("   •   ").append(e.getKey()).append(' ').append(e.getValue());
        }
        b.tvSummary.setText(sum);

        String today = AttendanceRecord.today();
        for (final LeaveRecord l : all) {
            String sub = l.rangeLabel();
            if (!l.reason.isEmpty()) sub += "\n" + l.reason;
            if (l.covers(today)) sub += "\nActive today";

            b.llList.addView(UiKit.row(this, getColor(R.color.att_leave),
                    l.type + " • " + l.days() + " day(s)", sub,
                    null, 0, v -> edit(l)));
        }
    }

    // ── Add / edit ────────────────────────────────────────────────────────────

    /** @param existing null to create a new entry. */
    private void edit(final LeaveRecord existing) {
        final LeaveRecord draft = new LeaveRecord();
        if (existing != null) {
            draft.id       = existing.id;
            draft.startDay = existing.startDay;
            draft.endDay   = existing.endDay;
            draft.type     = existing.type;
            draft.reason   = existing.reason;
            draft.createdAt = existing.createdAt;
        } else {
            draft.startDay = AttendanceRecord.today();
            draft.endDay   = draft.startDay;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 22);
        box.setPadding(p, p, p, p);

        final TextView tvType  = valueRow(box, "Type");
        final TextView tvStart = valueRow(box, "From");
        final TextView tvEnd   = valueRow(box, "To");

        final EditText etReason = new EditText(this);
        etReason.setHint("Reason (optional)");
        etReason.setText(draft.reason);
        etReason.setTextColor(getColor(R.color.text_primary));
        etReason.setHintTextColor(getColor(R.color.text_hint));
        etReason.setBackgroundResource(R.drawable.bg_input_field);
        etReason.setTextSize(13f);
        int ip = UiKit.dp(this, 12);
        etReason.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = UiKit.dp(this, 12);
        etReason.setLayoutParams(rlp);
        box.addView(etReason);

        tvType.setText(draft.type);
        tvStart.setText(AttendanceRecord.formatDay(draft.startDay));
        tvEnd.setText(AttendanceRecord.formatDay(draft.endDay));

        tvType.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Leave type")
                .setItems(LeaveRecord.TYPES, (d, w) -> {
                    draft.type = LeaveRecord.TYPES[w];
                    tvType.setText(draft.type);
                })
                .show());

        tvStart.setOnClickListener(v -> pickDay(draft.startDay, day -> {
            draft.startDay = day;
            tvStart.setText(AttendanceRecord.formatDay(day));
            // Dragging the start past the end would leave an inverted range that counts as
            // a single day; pull the end along so the record stays meaningful.
            if (draft.endDay.compareTo(draft.startDay) < 0) {
                draft.endDay = draft.startDay;
                tvEnd.setText(AttendanceRecord.formatDay(draft.endDay));
            }
        }));

        tvEnd.setOnClickListener(v -> pickDay(draft.endDay, day -> {
            if (day.compareTo(draft.startDay) < 0) {
                toast("End date cannot be before the start");
                return;
            }
            draft.endDay = day;
            tvEnd.setText(AttendanceRecord.formatDay(day));
        }));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Add leave" : "Edit leave")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    draft.reason = etReason.getText().toString().trim();
                    db.saveLeave(draft);
                    render();
                    toast("Saved");
                })
                .setNegativeButton("Cancel", null);

        if (existing != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                db.deleteLeave(existing.id);
                render();
                toast("Leave removed");
            });
        }
        builder.show();
    }

    private TextView valueRow(LinearLayout parent, String label) {
        TextView lab = new TextView(this);
        lab.setText(label.toUpperCase(Locale.getDefault()));
        lab.setTextColor(getColor(R.color.text_hint));
        lab.setTextSize(10f);
        lab.setLetterSpacing(0.1f);
        lab.setPadding(0, UiKit.dp(this, 10), 0, 0);
        parent.addView(lab);

        TextView val = new TextView(this);
        val.setTextColor(getColor(R.color.att_accent));
        val.setTextSize(16f);
        val.setPadding(0, UiKit.dp(this, 2), 0, 0);
        val.setClickable(true);
        val.setFocusable(true);
        parent.addView(val);
        return val;
    }

    private interface OnDay { void picked(String day); }

    private void pickDay(String current, final OnDay cb) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(AttendanceRecord.dayStartMs(current));
        new DatePickerDialog(this, (view, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(y, m, d, 0, 0, 0);
            cb.picked(AttendanceRecord.dayKey(picked.getTimeInMillis()));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
