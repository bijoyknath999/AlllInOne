package com.allinone.app;

import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivityFocusTimerBinding;
import com.allinone.app.study.FocusController;
import com.allinone.app.study.StudyDb;

import java.util.Locale;

/**
 * A Pomodoro focus timer. The countdown lives in {@link FocusController} (saved end-timestamp +
 * exact alarm + chronometer notification) so it keeps running when the screen is left or closed.
 * This Activity just renders and drives that state while visible.
 */
public class FocusTimerActivity extends AppCompatActivity {

    private ActivityFocusTimerBinding binding;
    private StudyDb db;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int FOCUS_COLOR = 0xFF6C63FF;
    private static final int BREAK_COLOR = 0xFF00BFA6;
    private static final int BREAK_MIN = 5;

    private final int[] presets = {15, 25, 45, 60};
    private int focusMin = 25;
    private boolean isFocus = true;
    private boolean running = false;
    private long totalMs;
    private long remainingMs;
    private long endAt;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            remainingMs = endAt - System.currentTimeMillis();
            if (remainingMs <= 0) {
                remainingMs = 0;
                updateClock();
                binding.ring.setProgress(1f);
                onSessionEnded();
                return;
            }
            updateClock();
            binding.ring.setProgress(totalMs == 0 ? 0 : 1f - (remainingMs / (float) totalMs));
            ui.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityFocusTimerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.getRoot().setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        db = new StudyDb(this);
        FocusController.createChannels(this);
        requestNotificationPermission();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnPrimary.setOnClickListener(v -> { if (running) pause(); else start(); });
        binding.btnReset.setOnClickListener(v -> {
            FocusController.stopIdle(this);
            resetTo(isFocus ? focusMin : BREAK_MIN, isFocus);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncFromState();
        refreshFocusStat();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop UI updates only — the timer keeps running via alarm + notification.
        ui.removeCallbacks(tick);
    }

    /** Reconcile the UI with the persisted timer state (handles returning after backgrounding). */
    private void syncFromState() {
        FocusController.State s = FocusController.load(this);
        if (s.running) {
            long rem = s.endAt - System.currentTimeMillis();
            if (rem > 0) {
                isFocus = s.focusMode;
                focusMin = s.focusMin;
                totalMs = s.totalMs;
                endAt = s.endAt;
                remainingMs = rem;
                running = true;
                applyMode();
                binding.btnPrimary.setText("Pause");
                buildPresetChips();
                updateClock();
                ui.removeCallbacks(tick);
                ui.post(tick);
                return;
            } else {
                // Ended while we were away — finalise it (dedupes with the alarm) and continue.
                isFocus = s.focusMode;
                focusMin = s.focusMin;
                endAt = s.endAt;
                FocusController.complete(this, s.endAt);
                running = false;
                advanceMode();
                return;
            }
        }
        if (s.paused && s.remaining > 0) {
            isFocus = s.focusMode;
            focusMin = s.focusMin;
            totalMs = s.totalMs;
            remainingMs = s.remaining;
            running = false;
            applyMode();
            binding.ring.setProgress(totalMs == 0 ? 0 : 1f - (remainingMs / (float) totalMs));
            updateClock();
            binding.btnPrimary.setText("Resume");
            buildPresetChips();
            return;
        }
        resetTo(isFocus ? focusMin : BREAK_MIN, isFocus);
    }

    private void buildPresetChips() {
        binding.llPresets.removeAllViews();
        for (int m : presets) {
            boolean sel = m == focusMin && isFocus;
            TextView chip = makeChip(m + " min", sel);
            chip.setOnClickListener(v -> {
                if (running) return;
                focusMin = m;
                FocusController.stopIdle(this);
                resetTo(m, true);
            });
            binding.llPresets.addView(chip);
        }

        // Custom chip — shows the chosen value when it isn't one of the presets
        boolean customSelected = isFocus && !isPreset(focusMin);
        TextView custom = makeChip(customSelected ? focusMin + " min" : "Custom", customSelected);
        custom.setOnClickListener(v -> { if (!running) showCustomDialog(); });
        binding.llPresets.addView(custom);
    }

    private TextView makeChip(String text, boolean selected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(13f);
        int pv = dp(9), ph = dp(16);
        chip.setPadding(ph, pv, ph, pv);
        chip.setTextColor(selected ? 0xFFFFFFFF : 0xFFB0B0C8);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? FOCUS_COLOR : 0xFF222238);
        bg.setCornerRadius(dp(22));
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private boolean isPreset(int m) {
        for (int p : presets) if (p == m) return true;
        return false;
    }

    private void showCustomDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_custom_timer, null);
        EditText et = v.findViewById(R.id.et_minutes);
        View btnCancel = v.findViewById(R.id.btn_cancel);
        View btnSet = v.findViewById(R.id.btn_set);
        if (!isPreset(focusMin)) et.setText(String.valueOf(focusMin));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) Math.min(getResources().getDisplayMetrics().widthPixels * 0.9f,
                420 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }

        btnCancel.setOnClickListener(x -> dialog.dismiss());
        btnSet.setOnClickListener(x -> {
            int mins;
            try { mins = Integer.parseInt(et.getText().toString().trim()); }
            catch (Exception e) { mins = 0; }
            if (mins < 1)   mins = 1;
            if (mins > 180) mins = 180;
            focusMin = mins;
            FocusController.stopIdle(this);
            resetTo(mins, true);
            dialog.dismiss();
        });
        dialog.show();
    }

    private void resetTo(int minutes, boolean focus) {
        ui.removeCallbacks(tick);
        isFocus = focus;
        running = false;
        totalMs = minutes * 60_000L;
        remainingMs = totalMs;
        applyMode();
        updateClock();
        binding.ring.setProgress(0f);
        binding.btnPrimary.setText("Start");
        buildPresetChips();
    }

    private void applyMode() {
        int color = isFocus ? FOCUS_COLOR : BREAK_COLOR;
        binding.ring.setRingColor(color);
        binding.tvMode.setText(isFocus ? "FOCUS" : "BREAK");
        binding.tvMode.setTextColor(color);
        binding.tvHint.setText(isFocus
            ? "Put your phone down and study"
            : "Rest your eyes — you earned it");
        binding.llPresets.setVisibility(isFocus ? View.VISIBLE : View.GONE);
    }

    private void start() {
        running = true;
        endAt = System.currentTimeMillis() + remainingMs;
        FocusController.start(this, totalMs, remainingMs, isFocus, focusMin);
        binding.btnPrimary.setText("Pause");
        buildPresetChips();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void pause() {
        running = false;
        ui.removeCallbacks(tick);
        remainingMs = Math.max(0, endAt - System.currentTimeMillis());
        FocusController.pause(this, remainingMs);
        binding.btnPrimary.setText("Resume");
        buildPresetChips();
    }

    /** Called when the current session's time hits zero while the screen is visible. */
    private void onSessionEnded() {
        running = false;
        ui.removeCallbacks(tick);
        FocusController.complete(this, endAt); // dedupes with the alarm if it already fired
        advanceMode();
    }

    /** Move to the next mode (break after focus, focus after break) in an idle, ready-to-start state. */
    private void advanceMode() {
        refreshFocusStat();
        if (isFocus) {
            resetTo(BREAK_MIN, false);
            binding.tvHint.setText("Nice work! Session logged — take a break ☕");
        } else {
            resetTo(focusMin, true);
            binding.tvHint.setText("Break over — ready for another focus session?");
        }
    }

    private void updateClock() {
        long totalSec = remainingMs / 1000;
        binding.tvClock.setText(String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60));
    }

    private void refreshFocusStat() {
        int focus = db.getFocusMinutesToday();
        binding.tvFocusToday.setText(focus >= 60
            ? (focus / 60) + "h " + (focus % 60) + "m" : focus + "m");
        binding.tvSessions.setText(String.valueOf(FocusController.getSessionsToday(this)));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
            }
        }
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
