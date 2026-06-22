package com.allinone.app.expense;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Lightweight donut chart — no third-party dependency. */
public class PieChartView extends View {

    public static class Slice {
        public String label;
        public double value;
        public int color;
        public Slice(String label, double value, int color) {
            this.label = label; this.value = value; this.color = color;
        }
    }

    private final List<Slice> data = new ArrayList<>();
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public PieChartView(Context c) { super(c); init(); }
    public PieChartView(Context c, @Nullable AttributeSet a) { super(c, a); init(); }

    private void init() {
        arcPaint.setStyle(Paint.Style.FILL);
        holePaint.setColor(0xFF1A1A2E);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(dp(13));
    }

    public void setData(List<Slice> slices) {
        data.clear();
        if (slices != null) data.addAll(slices);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        int size = Math.min(w, h);
        double total = 0;
        for (Slice s : data) total += s.value;

        float cx = w / 2f, cy = h / 2f;
        float radius = size / 2f - dp(6);

        if (total <= 0) {
            arcPaint.setColor(0xFF252540);
            canvas.drawCircle(cx, cy, radius, arcPaint);
            canvas.drawCircle(cx, cy, radius * 0.6f, holePaint);
            textPaint.setColor(0xFF606080);
            canvas.drawText("No data", cx, cy + dp(5), textPaint);
            return;
        }

        rect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        float start = -90f;
        for (Slice s : data) {
            float sweep = (float) (s.value / total * 360.0);
            arcPaint.setColor(s.color);
            canvas.drawArc(rect, start, sweep, true, arcPaint);
            start += sweep;
        }
        // donut hole
        canvas.drawCircle(cx, cy, radius * 0.6f, holePaint);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextSize(dp(12));
        canvas.drawText(data.size() + (data.size() == 1 ? " category" : " categories"), cx, cy + dp(4), textPaint);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
