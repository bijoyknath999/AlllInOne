package com.allinone.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.allinone.app.databinding.ActivityStudyFocusBinding;
import com.allinone.app.study.PlanAdapter;
import com.allinone.app.study.PlanDialog;
import com.allinone.app.study.StudyDb;
import com.allinone.app.study.StudyPlan;
import com.allinone.app.study.StudyReminderReceiver;

import java.util.ArrayList;
import java.util.List;

/** Dashboard for the Study Focus module: streak, focus time, and study goals. */
public class StudyFocusActivity extends AppCompatActivity {

    private ActivityStudyFocusBinding binding;
    private StudyDb db;
    private PlanAdapter adapter;
    private final List<StudyPlan> plans = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityStudyFocusBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.getRoot().setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        db = new StudyDb(this);
        StudyReminderReceiver.createChannel(this);
        requestNotificationPermission();

        binding.btnBack.setOnClickListener(v -> finish());
        binding.cardFocusTimer.setOnClickListener(v -> {
            startActivity(new Intent(this, FocusTimerActivity.class));
            overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
        });
        binding.fabAdd.setOnClickListener(v ->
            PlanDialog.show(this, db, null, (plan, isNew) -> {
                load();
                openPlan(plan.id); // jump straight in to add chapters
            }));

        adapter = new PlanAdapter(plans, p -> openPlan(p.id));
        binding.rvPlans.setLayoutManager(new LinearLayoutManager(this));
        binding.rvPlans.setNestedScrollingEnabled(false);
        binding.rvPlans.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void openPlan(long id) {
        Intent i = new Intent(this, StudyPlanActivity.class);
        i.putExtra("plan_id", id);
        startActivity(i);
        overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
    }

    private void load() {
        plans.clear();
        plans.addAll(db.getAllPlans());
        adapter.notifyDataSetChanged();
        binding.tvEmptyHint.setVisibility(plans.isEmpty() ? View.VISIBLE : View.GONE);

        // Stats
        int streak = db.getStreak();
        int focus = db.getFocusMinutesToday();
        int active = 0;
        for (StudyPlan p : plans) if (!p.isComplete()) active++;

        binding.tvStreak.setText(String.valueOf(streak));
        binding.tvStreakLabel.setText(streak == 1 ? "day streak" : "day streak");
        binding.tvFocus.setText(focus >= 60 ? (focus / 60) + "h " + (focus % 60) + "m" : focus + "m");
        binding.tvActive.setText(String.valueOf(active));

        Animation a = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        binding.rvPlans.startAnimation(a);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
            }
        }
    }
}
