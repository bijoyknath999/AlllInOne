package com.allinone.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.allinone.app.databinding.ActivitySplashBinding;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION_MS = 2600L;

    private ActivitySplashBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivitySplashBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Handle edge-to-edge insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        startSplashAnimation();
    }

    private void startSplashAnimation() {
        // Logo: scale + fade in
        Animation logoAnim = AnimationUtils.loadAnimation(this, R.anim.scale_fade_in);
        binding.ivSplashLogo.startAnimation(logoAnim);

        // App name fades in after logo (delay 400ms)
        handler.postDelayed(() -> {
            ObjectAnimator nameAlpha = ObjectAnimator.ofFloat(binding.tvSplashName, View.ALPHA, 0f, 1f);
            nameAlpha.setDuration(500);
            ObjectAnimator nameTranslate = ObjectAnimator.ofFloat(binding.tvSplashName,
                    View.TRANSLATION_Y, 20f, 0f);
            nameTranslate.setDuration(500);

            AnimatorSet nameSet = new AnimatorSet();
            nameSet.playTogether(nameAlpha, nameTranslate);
            nameSet.start();
        }, 400L);

        // Tagline fades in after name (delay 700ms)
        handler.postDelayed(() -> {
            ObjectAnimator tagAlpha = ObjectAnimator.ofFloat(binding.tvSplashTagline, View.ALPHA, 0f, 1f);
            tagAlpha.setDuration(500);
            ObjectAnimator tagTranslate = ObjectAnimator.ofFloat(binding.tvSplashTagline,
                    View.TRANSLATION_Y, 20f, 0f);
            tagTranslate.setDuration(500);

            AnimatorSet tagSet = new AnimatorSet();
            tagSet.playTogether(tagAlpha, tagTranslate);
            tagSet.start();
        }, 700L);

        // Loading progress bar appears after tagline
        handler.postDelayed(() -> {
            ObjectAnimator pbAlpha = ObjectAnimator.ofFloat(binding.pbSplash, View.ALPHA, 0f, 1f);
            pbAlpha.setDuration(400);
            pbAlpha.start();
            animateProgress();
        }, 1000L);

        // Navigate to MainActivity
        handler.postDelayed(this::goToMain, SPLASH_DURATION_MS);
    }

    private void animateProgress() {
        // Smoothly animate progress from 0 to 100 over 1.5 seconds
        ObjectAnimator progressAnim = ObjectAnimator.ofInt(binding.pbSplash, "progress", 0, 100);
        progressAnim.setDuration(1500);
        progressAnim.start();
    }

    private void goToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
