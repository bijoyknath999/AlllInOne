package com.allinone.app.expense;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Lightweight vertical bar chart with x-axis labels — no third-party dependency. */
public class BarChartView extends View {

    public static class Bar {
        public String label;
        public double value;
        public int color;
        public Bar(String label, double value, int color) {
            this.label = label; this.value = value; this.color = color;
        }
    }

    private final List<Bar> data = new ArrayList<>();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public BarChartView(Context c) { super(c); init(); }
    public BarChartView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(0xFFB0B0C8);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(10));
    }

    public void setData(List<Bar> bars) {
        data.clear();
        if (bars != null) data.addAll(bars);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (data.isEmpty()) {
            textPaint.setColor(0xFF606080);
            canvas.drawText("No data", getWidth() / 2f, getHeight() / 2f, textPaint);
            return;
        }
        float w = getWidth(), h = getHeight();
        float labelH = dp(18), valueH = dp(14), topPad = dp(6);
        float chartH = h - labelH - valueH - topPad;

        double max = 0;
        for (Bar b : data) max = Math.max(max, b.value);
        if (max <= 0) max = 1;

        int n = data.size();
        float slot = w / n;
        float barW = Math.min(slot * 0.6f, dp(46));

        for (int i = 0; i < n; i++) {
            Bar b = data.get(i);
            float cx = slot * i + slot / 2f;
            float bh = (float) (b.value / max * chartH);
            float top = topPad + valueH + (chartH - bh);
            float bottom = topPad + valueH + chartH;
            barPaint.setColor(b.color);
            RectF r = new RectF(cx - barW / 2f, top, cx + barW / 2f, bottom);
            canvas.drawRoundRect(r, dp(4), dp(4), barPaint);

            // x label
            textPaint.setColor(0xFFB0B0C8);
            textPaint.setTextSize(dp(10));
            canvas.drawText(b.label, cx, h - dp(4), textPaint);

            // value on top (compact)
            if (b.value > 0) {
                textPaint.setColor(0xFF8888A0);
                textPaint.setTextSize(dp(9));
                canvas.drawText(compact(b.value), cx, top - dp(3), textPaint);
            }
        }
    }

    private String compact(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000) return String.format("%.1fk", v / 1_000);
        return String.valueOf(Math.round(v));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
