package com.demo.ledger.ui;

import android.app.DatePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.demo.ledger.App;
import com.demo.ledger.R;
import com.demo.ledger.data.Bill;
import com.demo.ledger.data.BillRepository;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.net.LlmClient;
import com.demo.ledger.net.LlmParser;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class ReportActivity extends AppCompatActivity {

    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_MONTH = "month";

    private static final String TAG = "Ledger";
    private static final String[] PERIODS = {"日", "周", "月", "年"};
    private static final int[] CHIP_IDS = {R.id.chipDay, R.id.chipWeek, R.id.chipMonth, R.id.chipYear};

    private Prefs prefs;
    private BillRepository repository;

    private int periodIndex = 2;
    private long anchor;
    private long rangeStart;
    private long rangeEnd;
    private int requestSeq;

    private TextView rangeLabel;
    private TextView[] chipViews;
    private TextView kpiTotal;
    private TextView kpiCount;
    private TextView kpiAvg;
    private TextView kpiMax;
    private Button btnGenerate;
    private ProgressBar progress;
    private TextView errorView;
    private View resultCard;
    private TextView scoreView;
    private TextView verdictView;
    private LinearProgressIndicator scoreBar;
    private LinearLayout bars;
    private LinearLayout overviewSection;
    private LinearLayout structureSection;
    private LinearLayout riskSection;
    private LinearLayout adviceSection;
    private TextView overviewView;
    private TextView structureView;
    private TextView riskView;
    private TextView adviceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        prefs = new Prefs(this);
        repository = new BillRepository(this);

        Calendar now = Calendar.getInstance();
        int year = getIntent().getIntExtra(EXTRA_YEAR, now.get(Calendar.YEAR));
        int month = getIntent().getIntExtra(EXTRA_MONTH, now.get(Calendar.MONTH) + 1);
        if (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH) + 1) {
            anchor = now.getTimeInMillis();
        } else {
            Calendar target = Calendar.getInstance();
            target.clear();
            target.set(year, month - 1, 1, 12, 0, 0);
            anchor = target.getTimeInMillis();
        }

        rangeLabel = findViewById(R.id.rangeLabel);
        kpiTotal = findViewById(R.id.kpiTotal);
        kpiCount = findViewById(R.id.kpiCount);
        kpiAvg = findViewById(R.id.kpiAvg);
        kpiMax = findViewById(R.id.kpiMax);
        btnGenerate = findViewById(R.id.btnGenerate);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.errorView);
        resultCard = findViewById(R.id.resultCard);
        scoreView = findViewById(R.id.scoreView);
        verdictView = findViewById(R.id.verdictView);
        scoreBar = findViewById(R.id.scoreBar);
        bars = findViewById(R.id.bars);
        overviewSection = findViewById(R.id.overviewSection);
        structureSection = findViewById(R.id.structureSection);
        riskSection = findViewById(R.id.riskSection);
        adviceSection = findViewById(R.id.adviceSection);
        overviewView = findViewById(R.id.overviewView);
        structureView = findViewById(R.id.structureView);
        riskView = findViewById(R.id.riskView);
        adviceView = findViewById(R.id.adviceView);

        chipViews = new TextView[CHIP_IDS.length];
        for (int i = 0; i < CHIP_IDS.length; i++) {
            final int index = i;
            chipViews[i] = findViewById(CHIP_IDS[i]);
            chipViews[i].setOnClickListener(v -> setPeriod(index));
        }

        findViewById(R.id.btnPeriodPrev).setOnClickListener(v -> shiftPeriod(-1));
        findViewById(R.id.btnPeriodNext).setOnClickListener(v -> shiftPeriod(1));
        rangeLabel.setOnClickListener(v -> showDatePicker());

        btnGenerate.setOnClickListener(v -> generate());
        setPeriod(2);
    }

    private void setPeriod(int index) {
        periodIndex = index;
        refresh();
    }

    private void shiftPeriod(int delta) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(anchor);
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        if (periodIndex == 0) {
            calendar.add(Calendar.DAY_OF_YEAR, delta);
        } else if (periodIndex == 1) {
            calendar.add(Calendar.DAY_OF_YEAR, delta * 7);
        } else if (periodIndex == 2) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.add(Calendar.MONTH, delta);
            calendar.set(Calendar.DAY_OF_MONTH,
                    Math.min(day, calendar.getActualMaximum(Calendar.DAY_OF_MONTH)));
        } else {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.add(Calendar.YEAR, delta);
            calendar.set(Calendar.DAY_OF_MONTH,
                    Math.min(day, calendar.getActualMaximum(Calendar.DAY_OF_MONTH)));
        }
        anchor = calendar.getTimeInMillis();
        refresh();
    }

    private void showDatePicker() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(anchor);
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, dayOfMonth, 12, 0, 0);
            anchor = picked.getTimeInMillis();
            refresh();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void refresh() {
        computeRange();
        renderChips();
        rangeLabel.setText(buildRangeText());

        resultCard.setVisibility(View.GONE);
        errorView.setText("");
        scoreBar.setProgressCompat(0, false);
        loadKpi();
    }

    private void computeRange() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(anchor);
        if (periodIndex == 0) {
            startOfDay(calendar);
            rangeStart = calendar.getTimeInMillis();
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        } else if (periodIndex == 1) {
            calendar.setFirstDayOfWeek(Calendar.MONDAY);
            calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            startOfDay(calendar);
            rangeStart = calendar.getTimeInMillis();
            calendar.add(Calendar.DAY_OF_YEAR, 7);
        } else if (periodIndex == 2) {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            startOfDay(calendar);
            rangeStart = calendar.getTimeInMillis();
            calendar.add(Calendar.MONTH, 1);
        } else {
            calendar.set(Calendar.DAY_OF_MONTH, 1);
            calendar.set(Calendar.MONTH, Calendar.JANUARY);
            startOfDay(calendar);
            rangeStart = calendar.getTimeInMillis();
            calendar.add(Calendar.YEAR, 1);
        }
        rangeEnd = calendar.getTimeInMillis();
    }

    private void startOfDay(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    private String buildRangeText() {
        SimpleDateFormat full = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA);
        SimpleDateFormat day = new SimpleDateFormat("M月d日", Locale.CHINA);
        SimpleDateFormat month = new SimpleDateFormat("yyyy年M月", Locale.CHINA);
        SimpleDateFormat year = new SimpleDateFormat("yyyy年", Locale.CHINA);
        if (periodIndex == 0) {
            return full.format(new Date(rangeStart));
        }
        if (periodIndex == 1) {
            return day.format(new Date(rangeStart)) + " - " + day.format(new Date(rangeEnd - 1));
        }
        if (periodIndex == 2) {
            return month.format(new Date(rangeStart));
        }
        return year.format(new Date(rangeStart));
    }

    private void renderChips() {
        int selectedBg = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, Color.BLUE);
        int selectedText = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
        int normalBg = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY);
        int normalText = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        for (int i = 0; i < chipViews.length; i++) {
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(19));
            background.setColor(i == periodIndex ? selectedBg : normalBg);
            chipViews[i].setBackground(background);
            chipViews[i].setTextColor(i == periodIndex ? selectedText : normalText);
            chipViews[i].setTypeface(i == periodIndex
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void loadKpi() {
        final int seq = ++requestSeq;
        final long start = rangeStart;
        final long end = rangeEnd;
        final int days = Math.max(1, (int) Math.round((end - start) / 86400000.0));
        App.get().io().execute(() -> {
            List<Bill> bills = repository.listByRange(start, end);
            long total = 0;
            long maxBill = 0;
            for (Bill bill : bills) {
                total += bill.amountCents;
                if (bill.amountCents > maxBill) {
                    maxBill = bill.amountCents;
                }
            }
            final long totalCents = total;
            final long maxCents = maxBill;
            final int count = bills.size();
            App.get().ui(() -> {
                if (seq != requestSeq) {
                    return;
                }
                kpiTotal.setText(String.format(Locale.CHINA, "¥%,.2f", totalCents / 100.0));
                kpiCount.setText(String.format(Locale.CHINA, "%d 笔", count));
                kpiAvg.setText(String.format(Locale.CHINA, "¥%,.2f", totalCents / 100.0 / days));
                kpiMax.setText(String.format(Locale.CHINA, "¥%,.2f", maxCents / 100.0));
            });
        });
    }

    private void generate() {
        if (!prefs.isConfigured()) {
            errorView.setText("请先在设置页填写 BaseURL、API Key 和模型名，或查看“模型接入教程”");
            return;
        }
        btnGenerate.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        errorView.setText("");
        resultCard.setVisibility(View.GONE);

        final long start = rangeStart;
        final long end = rangeEnd;
        final String rangeText = buildRangeText();
        final String periodName = PERIODS[periodIndex];
        final int days = Math.max(1, (int) Math.round((end - start) / 86400000.0));
        final long prevStart = start - (end - start);
        App.get().io().execute(() -> {
            try {
                List<Bill> bills = repository.listByRange(start, end);
                if (bills.isEmpty()) {
                    throw new IllegalStateException("这个时间范围内还没有账单，先记几笔再来");
                }
                LinkedHashMap<String, long[]> categories = repository.categoryTotals(start, end);
                List<String[]> merchants = repository.topMerchants(start, end, 3);
                long total = 0;
                long maxBill = 0;
                for (Bill bill : bills) {
                    total += bill.amountCents;
                    if (bill.amountCents > maxBill) {
                        maxBill = bill.amountCents;
                    }
                }
                long prevTotal = repository.totalCents(prevStart, start);

                String facts = buildFacts(rangeText, periodName, bills, categories, merchants,
                        total, maxBill, days, prevTotal);
                Log.d(TAG, "财报输入：" + facts);
                LlmParser.Report report = LlmClient.report(prefs, facts);

                final long totalCents = total;
                App.get().ui(() -> render(categories, report, totalCents));
            } catch (Exception e) {
                Log.e(TAG, "生成财报失败", e);
                App.get().ui(() -> {
                    btnGenerate.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    errorView.setText("生成失败：" + e.getMessage());
                });
            }
        });
    }

    private String buildFacts(String rangeText, String periodName, List<Bill> bills,
                              LinkedHashMap<String, long[]> categories, List<String[]> merchants,
                              long total, long maxBill, int days, long prevTotal) {
        long weekday = 0;
        long weekend = 0;
        long peakDayCents = 0;
        int peakDay = 0;
        long[] daily = new long[days];
        for (Bill bill : bills) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(bill.occurredAt);
            int offset = (int) ((bill.occurredAt - rangeStart) / 86400000L);
            if (offset >= 0 && offset < days) {
                daily[offset] += bill.amountCents;
            }
            int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
            if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
                weekend += bill.amountCents;
            } else {
                weekday += bill.amountCents;
            }
        }
        for (int i = 0; i < daily.length; i++) {
            if (daily[i] > peakDayCents) {
                peakDayCents = daily[i];
                peakDay = i + 1;
            }
        }

        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.CHINA, "%s消费统计（按%s统计，共 %d 天）\n",
                rangeText, periodName, days));
        builder.append(String.format(Locale.CHINA,
                "总支出：%.2f 元，共 %d 笔，日均 %.2f 元，最大单笔 %.2f 元\n",
                total / 100.0, bills.size(), total / 100.0 / days, maxBill / 100.0));
        builder.append(String.format(Locale.CHINA,
                "工作日支出：%.2f 元；周末支出：%.2f 元\n", weekday / 100.0, weekend / 100.0));
        if (peakDay > 0) {
            builder.append(String.format(Locale.CHINA,
                    "支出最高的一天：统计期内第 %d 天，%.2f 元\n", peakDay, peakDayCents / 100.0));
        }
        builder.append("分类明细：\n");
        for (LinkedHashMap.Entry<String, long[]> entry : categories.entrySet()) {
            builder.append(String.format(Locale.CHINA, "- %s：%.2f 元，%d 笔\n",
                    entry.getKey(), entry.getValue()[0] / 100.0, entry.getValue()[1]));
        }
        if (!merchants.isEmpty()) {
            builder.append("金额最高的商家：\n");
            for (String[] merchant : merchants) {
                builder.append(String.format(Locale.CHINA, "- %s：%.2f 元\n",
                        merchant[0], Long.parseLong(merchant[1]) / 100.0));
            }
        }
        if (prevTotal > 0) {
            double ratio = (total - prevTotal) * 100.0 / prevTotal;
            builder.append(String.format(Locale.CHINA,
                    "上一个等长周期总支出：%.2f 元，本周期环比 %+.1f%%\n",
                    prevTotal / 100.0, ratio));
        } else {
            builder.append("上一个等长周期没有记账数据，无法计算环比。\n");
        }
        return builder.toString();
    }

    private void render(LinkedHashMap<String, long[]> categories,
                        LlmParser.Report report, long totalCents) {
        btnGenerate.setEnabled(true);
        progress.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        resultCard.setAlpha(0f);
        resultCard.setTranslationY(dp(18));
        resultCard.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(340)
                .setInterpolator(new DecelerateInterpolator(1.6f))
                .start();

        scoreView.setText(String.valueOf(report.score));
        verdictView.setText(report.verdict.isEmpty() ? "本周期财务体检完成" : report.verdict);
        scoreBar.setProgressCompat(report.score, true);

        bindSection(overviewSection, overviewView, report.overview);
        bindSection(structureSection, structureView, report.structure);
        bindSection(riskSection, riskView, report.risk);

        if (report.advice.isEmpty()) {
            adviceSection.setVisibility(View.GONE);
        } else {
            adviceSection.setVisibility(View.VISIBLE);
            StringBuilder advice = new StringBuilder();
            for (int i = 0; i < report.advice.size(); i++) {
                advice.append(i + 1).append(". ").append(report.advice.get(i));
                if (i < report.advice.size() - 1) {
                    advice.append("\n\n");
                }
            }
            adviceView.setText(advice.toString());
        }

        bars.removeAllViews();
        int[] palette = DonutView.colors(this);
        int index = 0;
        for (LinkedHashMap.Entry<String, long[]> entry : categories.entrySet()) {
            bars.addView(buildBarRow(entry.getKey(), entry.getValue()[0], totalCents,
                    palette[index % palette.length]));
            index++;
        }
    }

    private void bindSection(LinearLayout section, TextView view, String text) {
        if (text == null || text.isEmpty()) {
            section.setVisibility(View.GONE);
            return;
        }
        section.setVisibility(View.VISIBLE);
        view.setText(text);
    }

    private View buildBarRow(String category, long cents, long totalCents, int color) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(6), 0, dp(6));

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText(category);
        name.setTextSize(13);
        name.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
        name.setTypeface(Typeface.DEFAULT_BOLD);
        labelRow.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amount = new TextView(this);
        amount.setText(String.format(Locale.CHINA, "%.2f 元", cents / 100.0));
        amount.setTextSize(13);
        amount.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        labelRow.addView(amount);
        wrapper.addView(labelRow);

        int percent = totalCents == 0 ? 0 : (int) Math.round(cents * 100.0 / totalCents);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(8));
        trackParams.topMargin = dp(6);

        View filled = new View(this);
        filled.setBackgroundColor(color);
        View rest = new View(this);
        rest.setBackgroundColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY));
        track.addView(filled, new LinearLayout.LayoutParams(0, dp(8), Math.max(percent, 1)));
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(8), Math.max(100 - percent, 1)));
        wrapper.addView(track, trackParams);
        return wrapper;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
