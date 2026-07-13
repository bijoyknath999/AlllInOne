package com.allinone.app.study;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** A lightweight container that lays children left-to-right and wraps to a new line when they don't fit. */
public class FlowLayout extends ViewGroup {

    private int lineSpacing;

    public FlowLayout(Context c) { super(c); init(c); }
    public FlowLayout(Context c, AttributeSet a) { super(c, a); init(c); }
    public FlowLayout(Context c, AttributeSet a, int s) { super(c, a, s); init(c); }

    private void init(Context c) {
        lineSpacing = (int) (8 * c.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int maxRowWidth = width - getPaddingLeft() - getPaddingRight();

        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            measureChildWithMargins(child, widthSpec, 0, heightSpec, 0);
            int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x + cw > maxRowWidth && x > 0) {
                x = 0;
                y += lineHeight + lineSpacing;
                lineHeight = 0;
            }
            x += cw;
            lineHeight = Math.max(lineHeight, ch);
        }
        int totalHeight = getPaddingTop() + getPaddingBottom() + y + lineHeight;
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(totalHeight, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxRowWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int cw = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int ch = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (x + cw > maxRowWidth && x > 0) {
                x = 0;
                y += lineHeight + lineSpacing;
                lineHeight = 0;
            }
            int childLeft = getPaddingLeft() + x + lp.leftMargin;
            int childTop = getPaddingTop() + y + lp.topMargin;
            child.layout(childLeft, childTop,
                childLeft + child.getMeasuredWidth(), childTop + child.getMeasuredHeight());
            x += cw;
            lineHeight = Math.max(lineHeight, ch);
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
