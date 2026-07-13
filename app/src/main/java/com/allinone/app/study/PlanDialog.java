package com.allinone.app.study;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.allinone.app.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/** Responsive, interactive add / edit dialog for a study plan. */
public class PlanDialog {

    public interface OnSaved {
        void saved(StudyPlan plan, boolean isNew);
    }

    private static final int[] DURATIONS = {7, 15, 30, 60, 90};

    public static void show(Activity act, StudyDb db, StudyPlan existing, OnSaved cb) {
        View v = LayoutInflater.from(act).inflate(R.layout.dialog_add_plan, null);

        TextView tvTitle = v.findViewById(R.id.tv_title);
        EditText etSubject = v.findViewById(R.id.et_subject);
        EditText etDays = v.findViewById(R.id.et_days);
        LinearLayout llPresets = v.findViewById(R.id.ll_presets);
        LinearLayout llColors = v.findViewById(R.id.ll_colors);
        TextView tvDeadline = v.findViewById(R.id.tv_deadline);
        CheckBox cbReminder = v.findViewById(R.id.cb_reminder);
        TextView tvTime = v.findViewById(R.id.tv_reminder_time);
        TextView btnCancel = v.findViewById(R.id.btn_cancel);
        TextView btnSave = v.findViewById(R.id.btn_save);

        final int[] pickedColor = {existing != null ? existing.color : StudyPlan.COLORS[0]};
        final int[] rHour = {existing != null ? existing.reminderHour : 19};
        final int[] rMin = {existing != null ? existing.reminderMinute : 0};

        // Duration preset chips (interactive highlight)
        final List<TextView> chips = new ArrayList<>();
        for (int d : DURATIONS) {
            TextView chip = buildChip(act, d + " days");
            chip.setOnClickListener(x -> etDays.setText(String.valueOf(d)));
            chips.add(chip);
            llPresets.addView(chip);
        }

        // Colour swatches
        for (int col : StudyPlan.COLORS) {
            View dot = buildSwatch(act, col, col == pickedColor[0]);
            dot.setOnClickListener(x -> {
                pickedColor[0] = col;
                refreshSwatches(llColors, col);
            });
            llColors.addView(dot);
        }

        // Live deadline + chip highlight react to the day count
        Runnable refresh = () -> {
            int days = parseDays(etDays.getText().toString());
            for (int i = 0; i < chips.size(); i++) styleChip(chips.get(i), DURATIONS[i] == days);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_MONTH, Math.max(1, days));
            String date = new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(c.getTime());
            tvDeadline.setText("🎯 Finish by " + date + "  ·  " + Math.max(1, days) + " days");
        };
        etDays.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { refresh.run(); }
        });

        // Reminder time
        Runnable updateTime = () -> {
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, rHour[0]);
            c.set(Calendar.MINUTE, rMin[0]);
            tvTime.setText(new SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.getTime()));
        };
        updateTime.run();
        tvTime.setOnClickListener(x -> new TimePickerDialog(act, (tp, h, m) -> {
            rHour[0] = h; rMin[0] = m; updateTime.run();
        }, rHour[0], rMin[0], false).show());

        // Prefill
        if (existing != null) {
            tvTitle.setText("Edit Study Goal");
            btnSave.setText("Save Changes");
            etSubject.setText(existing.subject);
            etDays.setText(String.valueOf(existing.targetDays));
            cbReminder.setChecked(existing.reminderEnabled);
        } else {
            etDays.setText("30");
        }
        refresh.run();

        final AlertDialog dialog = new AlertDialog.Builder(act).setView(v).create();
        applyResponsiveWindow(act, dialog);

        btnCancel.setOnClickListener(x -> dialog.dismiss());
        btnSave.setOnClickListener(x -> {
            String subject = etSubject.getText().toString().trim();
            if (TextUtils.isEmpty(subject)) {
                Toast.makeText(act, "Enter a subject", Toast.LENGTH_SHORT).show();
                return;
            }
            int days = Math.max(1, parseDays(etDays.getText().toString()));

            boolean isNew = existing == null;
            StudyPlan p = isNew ? new StudyPlan() : existing;
            p.subject = subject;
            p.targetDays = days;
            p.color = pickedColor[0];
            p.reminderEnabled = cbReminder.isChecked();
            p.reminderHour = rHour[0];
            p.reminderMinute = rMin[0];

            if (isNew) {
                p.startMs = StudyPlan.midnight(System.currentTimeMillis());
                p.createdAt = System.currentTimeMillis();
                p.id = db.insertPlan(p);
            } else {
                db.updatePlan(p);
                StudyReminderScheduler.cancel(act, p);
            }
            if (p.reminderEnabled) {
                StudyReminderReceiver.createChannel(act);
                StudyReminderScheduler.schedule(act, p);
            }
            dialog.dismiss();
            cb.saved(p, isNew);
        });

        dialog.show();
    }

    private static int parseDays(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static void applyResponsiveWindow(Activity act, AlertDialog dialog) {
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawableResource(android.R.color.transparent);
        DisplayMetrics dm = act.getResources().getDisplayMetrics();
        int width = (int) Math.min(dm.widthPixels * 0.94f, dp(act, 460));
        w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void refreshSwatches(LinearLayout container, int selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Integer) applySwatchBg(child, (Integer) tag, (Integer) tag == selected);
        }
    }

    private static TextView buildChip(Activity act, String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(13f);
        int h = dp(act, 9), w = dp(act, 15);
        t.setPadding(w, h, w, h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(act, 8));
        t.setLayoutParams(lp);
        t.setClickable(true);
        t.setFocusable(true);
        styleChip(t, false);
        return t;
    }

    private static void styleChip(TextView chip, boolean selected) {
        chip.setTextColor(selected ? 0xFFFFFFFF : 0xFFB0B0C8);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFF6C63FF : 0xFF222238);
        bg.setCornerRadius(dp(chip.getContext(), 22));
        chip.setBackground(bg);
    }

    private static View buildSwatch(Activity act, int color, boolean selected) {
        View dot = new View(act);
        dot.setTag(color);
        int size = dp(act, 36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(act, 10));
        dot.setLayoutParams(lp);
        applySwatchBg(dot, color, selected);
        dot.setClickable(true);
        dot.setFocusable(true);
        return dot;
    }

    private static void applySwatchBg(View dot, int color, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) g.setStroke(dp(dot.getContext(), 3), 0xFFFFFFFF);
        dot.setBackground(g);
    }

    private static int dp(android.content.Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }
}
