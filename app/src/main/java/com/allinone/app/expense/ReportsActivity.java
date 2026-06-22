package com.allinone.app.expense;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.allinone.app.R;
import com.allinone.app.databinding.ActivityReportsBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ReportsActivity extends AppCompatActivity {

    private static final int[] FALLBACK = {
        0xFF388E3C, 0xFF1976D2, 0xFFF57C00, 0xFFE53935, 0xFF7B1FA2, 0xFF0097A7, 0xFF607D8B,
        0xFF2E7D32, 0xFF00897B, 0xFFC2185B, 0xFF5E35B1, 0xFFEF6C00
    };

    private ActivityReportsBinding b;
    private ExpenseDb db;
    private MoneyPrefs prefs;
    private boolean periodMonth = true;
    private String pieType = Expense.TYPE_EXPENSE;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        EdgeToEdge.enable(this);
        b = ActivityReportsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        UiKit.applyInsets(b.getRoot());
        db = new ExpenseDb(this);
        prefs = new MoneyPrefs(this);
        b.btnBack.setOnClickListener(v -> finish());
        b.tvPMonth.setOnClickListener(v -> { periodMonth = true; render(); });
        b.tvPYear.setOnClickListener(v -> { periodMonth = false; render(); });
        b.tvPieExpense.setOnClickListener(v -> { pieType = Expense.TYPE_EXPENSE; render(); });
        b.tvPieIncome.setOnClickListener(v -> { pieType = Expense.TYPE_INCOME; render(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        styleToggle(b.tvPMonth, periodMonth);
        styleToggle(b.tvPYear, !periodMonth);
        boolean isExp = Expense.TYPE_EXPENSE.equals(pieType);
        styleToggle(b.tvPieExpense, isExp);
        styleToggle(b.tvPieIncome, !isExp);

        long[] range = periodMonth ? monthRange() : yearRange();
        Map<String, Integer> colorMap = categoryColorMap();

        // Pie
        Map<String, Double> totals = db.categoryTotals(range[0], range[1], pieType);
        List<PieChartView.Slice> slices = new ArrayList<>();
        double sum = 0;
        for (double v : totals.values()) sum += v;
        b.tvPieTitle.setText((isExp ? "Spending" : "Income") + " by category — " + prefs.format(sum));

        int idx = 0;
        b.llLegend.removeAllViews();
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            int color = colorMap.containsKey(e.getKey())
                ? colorMap.get(e.getKey()) : FALLBACK[idx % FALLBACK.length];
            slices.add(new PieChartView.Slice(e.getKey(), e.getValue(), color));
            double pct = sum > 0 ? (e.getValue() / sum * 100.0) : 0;
            b.llLegend.addView(legendRow(color, e.getKey(),
                prefs.format(e.getValue()) + "  (" + String.format(Locale.getDefault(), "%.0f", pct) + "%)"));
            idx++;
        }
        b.pieChart.setData(slices);
        if (totals.isEmpty()) {
            b.llLegend.addView(emptyText("No " + (isExp ? "spending" : "income") + " in this period"));
        }

        // Bar — last 6 months of the selected type
        b.tvBarTitle.setText((isExp ? "Expense" : "Income") + " — last 6 months");
        b.barChart.setData(lastSixMonths(pieType, isExp ? getColor(R.color.color_primary) : getColor(R.color.income_green)));
    }

    private List<BarChartView.Bar> lastSixMonths(String type, int color) {
        List<BarChartView.Bar> bars = new ArrayList<>();
        SimpleDateFormat mfmt = new SimpleDateFormat("MMM", Locale.getDefault());
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MONTH, -5);
        for (int i = 0; i < 6; i++) {
            Calendar m = (Calendar) c.clone();
            m.set(Calendar.DAY_OF_MONTH, 1);
            m.set(Calendar.HOUR_OF_DAY, 0); m.set(Calendar.MINUTE, 0); m.set(Calendar.SECOND, 0);
            long start = m.getTimeInMillis();
            m.set(Calendar.DAY_OF_MONTH, m.getActualMaximum(Calendar.DAY_OF_MONTH));
            m.set(Calendar.HOUR_OF_DAY, 23); m.set(Calendar.MINUTE, 59);
            long end = m.getTimeInMillis();
            double v = db.sumByType(start, end, type);
            bars.add(new BarChartView.Bar(mfmt.format(c.getTime()), v, color));
            c.add(Calendar.MONTH, 1);
        }
        return bars;
    }

    private Map<String, Integer> categoryColorMap() {
        Map<String, Integer> map = new HashMap<>();
        for (Category c : db.queryCategories(null)) map.put(c.name, c.color);
        return map;
    }

    private View legendRow(int color, String name, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 5));

        View dot = new View(this);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(UiKit.dp(this, 12), UiKit.dp(this, 12));
        dlp.setMarginEnd(UiKit.dp(this, 10));
        dot.setLayoutParams(dlp);
        row.addView(dot);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setTextSize(14f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvName);

        TextView tvVal = new TextView(this);
        tvVal.setText(value);
        tvVal.setTextColor(0xFFB0B0C8);
        tvVal.setTextSize(13f);
        tvVal.setTypeface(tvVal.getTypeface(), Typeface.BOLD);
        row.addView(tvVal);
        return row;
    }

    private TextView emptyText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFF606080);
        tv.setTextSize(13f);
        tv.setPadding(0, UiKit.dp(this, 8), 0, 0);
        return tv;
    }

    private void styleToggle(TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        tv.setTextColor(getColor(on ? R.color.color_primary : R.color.text_secondary));
    }

    private long[] monthRange() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        long start = c.getTimeInMillis();
        c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
        return new long[]{start, c.getTimeInMillis()};
    }

    private long[] yearRange() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0);
        long start = c.getTimeInMillis();
        c.set(Calendar.MONTH, Calendar.DECEMBER);
        c.set(Calendar.DAY_OF_MONTH, 31);
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59);
        return new long[]{start, c.getTimeInMillis()};
    }
}
