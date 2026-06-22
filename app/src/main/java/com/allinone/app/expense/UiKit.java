package com.allinone.app.expense;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Small helpers for building the money-manager list screens programmatically. */
public class UiKit {

    public static int dp(Context ctx, int dp) {
        return (int) (dp * ctx.getResources().getDisplayMetrics().density);
    }

    public static void applyInsets(final View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            root.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
    }

    /** A rounded card-style row: left colour dot + title/subtitle, right value. */
    public static View row(Context ctx, int dotColor, String title, String subtitle,
                           String rightText, int rightColor, View.OnClickListener click) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(ctx, 14);
        box.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 10);
        box.setLayoutParams(lp);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1A1A2E);
        bg.setCornerRadius(dp(ctx, 16));
        box.setBackground(bg);
        if (click != null) {
            box.setClickable(true);
            box.setFocusable(true);
            box.setOnClickListener(click);
        }

        if (dotColor != 0) {
            View dot = new View(ctx);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(dotColor);
            dot.setBackground(d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 14), dp(ctx, 14));
            dlp.setMarginEnd(dp(ctx, 12));
            dot.setLayoutParams(dlp);
            box.addView(dot);
        }

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(15f);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        texts.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextColor(0xFFB0B0C8);
            tvSub.setTextSize(12f);
            tvSub.setPadding(0, dp(ctx, 2), 0, 0);
            texts.addView(tvSub);
        }
        box.addView(texts);

        if (rightText != null) {
            TextView tvRight = new TextView(ctx);
            tvRight.setText(rightText);
            tvRight.setTextColor(rightColor == 0 ? 0xFFFFFFFF : rightColor);
            tvRight.setTextSize(15f);
            tvRight.setTypeface(tvRight.getTypeface(), Typeface.BOLD);
            tvRight.setGravity(Gravity.END);
            box.addView(tvRight);
        }
        return box;
    }

    public static TextView emptyHint(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFF606080);
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(ctx, 48), 0, 0);
        return tv;
    }

    /** Section heading inside a list. */
    public static TextView heading(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text.toUpperCase());
        tv.setTextColor(0xFFB0B0C8);
        tv.setTextSize(11f);
        tv.setLetterSpacing(0.1f);
        tv.setPadding(dp(ctx, 2), dp(ctx, 8), 0, dp(ctx, 8));
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }
}
