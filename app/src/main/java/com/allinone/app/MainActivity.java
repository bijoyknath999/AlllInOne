package com.allinone.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivityMainBinding;
import com.allinone.app.update.GithubUpdateChecker;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Android 15 edge-to-edge: apply system bar insets to content
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            binding.llContent.setPadding(
                    binding.llContent.getPaddingLeft(),
                    bars.top,
                    binding.llContent.getPaddingRight(),
                    bars.bottom + 32
            );
            return insets;
        });

        setupClickListeners();
        animateContent();
        GithubUpdateChecker.check(this);
    }

    private void setupClickListeners() {
        binding.cardYtDownloader.setOnClickListener(v -> {
            Intent intent = new Intent(this, YtDownloaderActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
        });

        binding.cardDownloadManager.setOnClickListener(v -> {
            Intent intent = new Intent(this, DownloadManagerActivity.class);
            startActivity(intent);
            overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
        });

        binding.cardExpenseTracker.setOnClickListener(v -> {
            startActivity(new Intent(this, ExpenseTrackerActivity.class));
            overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
        });

        binding.cardReminder.setOnClickListener(v -> {
            startActivity(new Intent(this, ReminderActivity.class));
            overridePendingTransition(R.anim.slide_up_fade_in, android.R.anim.fade_out);
        });

    }

    private void animateContent() {
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        slideUp.setStartOffset(100);
        binding.llHeader.startAnimation(slideUp);

        Animation cardAnim1 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        cardAnim1.setStartOffset(250);
        binding.cardYtDownloader.startAnimation(cardAnim1);

        Animation cardAnim2 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        cardAnim2.setStartOffset(350);
        binding.cardDownloadManager.startAnimation(cardAnim2);

        Animation cardAnim3 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        cardAnim3.setStartOffset(430);
        binding.cardExpenseTracker.startAnimation(cardAnim3);

        Animation cardAnim4 = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        cardAnim4.setStartOffset(510);
        binding.cardReminder.startAnimation(cardAnim4);

    }
}
