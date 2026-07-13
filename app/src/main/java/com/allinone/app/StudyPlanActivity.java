package com.allinone.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityStudyPlanBinding;
import com.allinone.app.study.Chapter;
import com.allinone.app.study.ChapterAdapter;
import com.allinone.app.study.PlanDialog;
import com.allinone.app.study.StudyDb;
import com.allinone.app.study.StudyPlan;
import com.allinone.app.study.StudyReminderScheduler;

import java.util.ArrayList;
import java.util.List;

/** Detail view of a study plan: progress ring, today's chapter, and the chapter checklist. */
public class StudyPlanActivity extends AppCompatActivity {

    private ActivityStudyPlanBinding binding;
    private StudyDb db;
    private ChapterAdapter adapter;
    private final List<Chapter> chapters = new ArrayList<>();

    private long planId;
    private StudyPlan plan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityStudyPlanBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.getRoot().setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        db = new StudyDb(this);
        planId = getIntent().getLongExtra("plan_id", -1);
        if (planId < 0) { finish(); return; }

        binding.btnBack.setOnClickListener(v -> finish());
        binding.btnMenu.setOnClickListener(this::showMenu);
        binding.fabAdd.setOnClickListener(v -> showAddChapters());
        binding.btnMarkDone.setOnClickListener(v -> markTodayDone());

        binding.switchReminder.setOnCheckedChangeListener((b, checked) -> {
            if (plan == null) return;
            plan.reminderEnabled = checked;
            db.setReminderEnabled(plan.id, checked);
            if (checked) StudyReminderScheduler.schedule(this, plan);
            else StudyReminderScheduler.cancel(this, plan);
        });

        adapter = new ChapterAdapter(chapters, new ChapterAdapter.Listener() {
            @Override public void onToggle(Chapter ch, boolean done) {
                db.setChapterDone(ch, done);
                load();
            }
            @Override public void onDelete(Chapter ch) {
                new AlertDialog.Builder(StudyPlanActivity.this)
                    .setTitle("Delete chapter")
                    .setMessage("Remove \"" + ch.name + "\"?")
                    .setPositiveButton("Delete", (d, w) -> { db.deleteChapter(ch.id); load(); })
                    .setNegativeButton("Cancel", null)
                    .show();
            }
        });
        binding.rvChapters.setLayoutManager(new LinearLayoutManager(this));
        binding.rvChapters.setNestedScrollingEnabled(false);
        binding.rvChapters.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        plan = db.getPlan(planId);
        if (plan == null) { finish(); return; }

        int accent = plan.color;
        binding.tvSubject.setText(plan.subject);
        binding.tvRange.setText(plan.rangeLabel());

        binding.ring.setRingColor(accent);
        binding.ring.setProgress(plan.progress());
        binding.tvPercent.setText(plan.progressPercent() + "%");
        binding.tvPercent.setTextColor(accent);
        binding.tvProgressSub.setText(plan.doneChapters + " / " + plan.totalChapters);

        binding.tvDaysLeft.setText(String.valueOf(plan.daysRemaining()));
        binding.tvStatus.setText(plan.statusLabel());
        binding.tvStatus.setTextColor(statusColor(plan));

        // Today's chapter card
        Chapter next = db.getNextChapter(planId);
        if (plan.isComplete()) {
            binding.cardToday.setVisibility(View.VISIBLE);
            binding.tvTodayChapter.setText("All chapters complete 🎉");
            binding.tvTodayPace.setText("Amazing work finishing " + plan.subject + "!");
            binding.btnMarkDone.setVisibility(View.GONE);
        } else if (next != null) {
            binding.cardToday.setVisibility(View.VISIBLE);
            binding.tvTodayChapter.setText(next.name);
            int due = plan.chaptersDueToday();
            String pace = due > 0
                ? "Target: " + due + " chapter" + (due == 1 ? "" : "s") + " today"
                : "You're on pace — keep going!";
            binding.tvTodayPace.setText(pace + "  ·  ~" + fmtPace(plan.pacePerDay()) + "/day to finish");
            binding.btnMarkDone.setVisibility(View.VISIBLE);
        } else {
            binding.cardToday.setVisibility(View.VISIBLE);
            binding.tvTodayChapter.setText("No chapters yet");
            binding.tvTodayPace.setText("Tap + to add the chapters you need to cover");
            binding.btnMarkDone.setVisibility(View.GONE);
        }

        binding.switchReminder.setOnCheckedChangeListener(null);
        binding.switchReminder.setChecked(plan.reminderEnabled);
        binding.tvReminderTime.setText("Daily at " + plan.reminderTimeLabel());
        binding.switchReminder.setOnCheckedChangeListener((b, checked) -> {
            plan.reminderEnabled = checked;
            db.setReminderEnabled(plan.id, checked);
            if (checked) StudyReminderScheduler.schedule(this, plan);
            else StudyReminderScheduler.cancel(this, plan);
        });

        chapters.clear();
        chapters.addAll(db.getChapters(planId));
        adapter.setAccent(accent);
        adapter.setNextChapterId(next != null ? next.id : -1);
        adapter.notifyDataSetChanged();
        binding.tvChaptersEmpty.setVisibility(chapters.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void markTodayDone() {
        Chapter next = db.getNextChapter(planId);
        if (next == null) return;
        db.setChapterDone(next, true);
        Toast.makeText(this, "\"" + next.name + "\" done ✓", Toast.LENGTH_SHORT).show();
        load();
    }

    private void showAddChapters() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_add_chapter, null);
        EditText et = v.findViewById(R.id.et_chapters);
        TextView tvCount = v.findViewById(R.id.tv_count);
        View btnCancel = v.findViewById(R.id.btn_cancel);
        View btnSave = v.findViewById(R.id.btn_save);

        // Live count of non-empty lines
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                tvCount.setText(String.valueOf(countLines(s.toString())));
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            int width = (int) Math.min(getResources().getDisplayMetrics().widthPixels * 0.94f,
                460 * getResources().getDisplayMetrics().density);
            dialog.getWindow().setLayout(width,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }

        btnCancel.setOnClickListener(x -> dialog.dismiss());
        btnSave.setOnClickListener(x -> {
            String raw = et.getText().toString().trim();
            if (TextUtils.isEmpty(raw)) { dialog.dismiss(); return; }
            int added = 0;
            for (String line : raw.split("\\n")) {
                String name = line.trim();
                if (name.isEmpty()) continue;
                db.insertChapter(new Chapter(planId, name, db.nextOrderIdx(planId)));
                added++;
            }
            if (added > 0) {
                Toast.makeText(this, "Added " + added + " chapter" + (added == 1 ? "" : "s"),
                    Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
            load();
        });
        dialog.show();
    }

    private static int countLines(String raw) {
        int n = 0;
        for (String line : raw.split("\\n")) if (!line.trim().isEmpty()) n++;
        return n;
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Edit goal");
        menu.getMenu().add(0, 2, 1, "Delete goal");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                PlanDialog.show(this, db, plan, (p, isNew) -> load());
            } else if (item.getItemId() == 2) {
                confirmDelete();
            }
            return true;
        });
        menu.show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
            .setTitle("Delete goal")
            .setMessage("Delete \"" + plan.subject + "\" and all its chapters?")
            .setPositiveButton("Delete", (d, w) -> {
                StudyReminderScheduler.cancel(this, plan);
                db.deletePlan(planId);
                finish();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private int statusColor(StudyPlan p) {
        if (p.isComplete()) return 0xFF66BB6A;
        if (p.isOverdue()) return 0xFFE53935;
        if (p.expectedDoneToday() - p.doneChapters > 0) return 0xFFFFA726;
        return 0xFF66BB6A;
    }

    private static String fmtPace(double pace) {
        if (pace <= 0) return "0";
        if (pace < 1) return String.format(java.util.Locale.US, "%.1f", pace);
        return String.valueOf((int) Math.ceil(pace));
    }
}
