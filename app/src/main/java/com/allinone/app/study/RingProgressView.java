package com.allinone.app.study;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** A rounded circular progress ring. Set progress 0..1 and an accent colour. */
public class RingProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private float progress = 0f;      // 0..1
    private int ringColor = 0xFF6C63FF;
    private float strokeDp = 10f;

    public RingProgressView(Context c) { super(c); init(); }
    public RingProgressView(Context c, AttributeSet a) { super(c, a); init(); }
    public RingProgressView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(0xFF252540);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setColor(ringColor);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setProgress(float p) {
        progress = Math.max(0f, Math.min(1f, p));
        invalidate();
    }

    public void setRingColor(int color) {
        ringColor = color;
        progressPaint.setColor(color);
        invalidate();
    }

    public void setStrokeWidthDp(float dp) {
        strokeDp = dp;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float stroke = strokeDp * getResources().getDisplayMetrics().density;
        trackPaint.setStrokeWidth(stroke);
        progressPaint.setStrokeWidth(stroke);

        float pad = stroke / 2f + 1f;
        int size = Math.min(getWidth(), getHeight());
        float left = (getWidth() - size) / 2f + pad;
        float top = (getHeight() - size) / 2f + pad;
        arc.set(left, top, left + size - 2 * pad, top + size - 2 * pad);

        canvas.drawArc(arc, 0, 360, false, trackPaint);
        canvas.drawArc(arc, -90, 360 * progress, false, progressPaint);
    }
}
