package com.demo.ledger.ui;

import android.graphics.Color;
import android.graphics.Typeface;
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
import com.demo.ledger.data.BillRepository;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.net.LlmClient;
import com.demo.ledger.net.LlmParser;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.google.android.material.color.MaterialColors;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class InsightActivity extends AppCompatActivity {

    public static final String EXTRA_YEAR = "year";
    public static final String EXTRA_MONTH = "month";

    private static final String TAG = "Ledger";

    private Prefs prefs;
    private BillRepository repository;
    private int year;
    private int month;

    private Button btnGenerate;
    private ProgressBar progress;
    private TextView errorView;
    private TextView summaryView;
    private TextView tipsView;
    private LinearLayout bars;
    private View resultCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_insight);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        prefs = new Prefs(this);
        repository = new BillRepository(this);
        Calendar now = Calendar.getInstance();
        year = getIntent().getIntExtra(EXTRA_YEAR, now.get(Calendar.YEAR));
        month = getIntent().getIntExtra(EXTRA_MONTH, now.get(Calendar.MONTH) + 1);

        TextView monthLabel = findViewById(R.id.monthLabel);
        monthLabel.setText(String.format(Locale.CHINA, "%d年%d月消费洞察", year, month));

        btnGenerate = findViewById(R.id.btnGenerate);
        progress = findViewById(R.id.progress);
        errorView = findViewById(R.id.errorView);
        summaryView = findViewById(R.id.summaryView);
        tipsView = findViewById(R.id.tipsView);
        bars = findViewById(R.id.bars);
        resultCard = findViewById(R.id.resultCard);

        btnGenerate.setOnClickListener(v -> generate());
    }

    private void generate() {
        if (!prefs.isConfigured()) {
            errorView.setText("请先在设置页填写 BaseURL、API Key 和模型名");
            return;
        }
        btnGenerate.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        errorView.setText("");

        final int y = year;
        final int m = month;
        App.get().io().execute(() -> {
            try {
                LinkedHashMap<String, long[]> categories = repository.categoryTotals(y, m);
                if (categories.isEmpty()) {
                    throw new IllegalStateException("这个月还没有账单，先记几笔再来");
                }
                List<String[]> merchants = repository.topMerchants(y, m, 3);
                long total = 0;
                for (long[] value : categories.values()) {
                    total += value[0];
                }
                int prevMonth = m == 1 ? 12 : m - 1;
                int prevYear = m == 1 ? y - 1 : y;
                long prevTotal = repository.totalCents(prevYear, prevMonth);

                String facts = buildFacts(categories, merchants, total, prevTotal);
                Log.d(TAG, "洞察输入：" + facts);
                LlmParser.Insight insight = LlmClient.insight(prefs, facts);

                final long totalCents = total;
                App.get().ui(() -> render(categories, insight, totalCents));
            } catch (Exception e) {
                Log.e(TAG, "生成洞察失败", e);
                App.get().ui(() -> {
                    btnGenerate.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    errorView.setText("生成失败：" + e.getMessage());
                });
            }
        });
    }

    private String buildFacts(LinkedHashMap<String, long[]> categories,
                              List<String[]> merchants, long total, long prevTotal) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format(Locale.CHINA, "%d年%d月消费统计\n", year, month));
        long count = 0;
        for (long[] value : categories.values()) {
            count += value[1];
        }
        builder.append(String.format(Locale.CHINA, "总支出：%.2f 元，共 %d 笔\n", total / 100.0, count));
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
            builder.append(String.format(Locale.CHINA, "上月总支出：%.2f 元，本月环比 %+.1f%%\n",
                    prevTotal / 100.0, ratio));
        } else {
            builder.append("上月没有记账数据，无法计算环比。\n");
        }
        return builder.toString();
    }

    private void render(LinkedHashMap<String, long[]> categories,
                        LlmParser.Insight insight, long totalCents) {
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

        summaryView.setText(insight.summary);
        tipsView.setText("");
        if (!insight.tips.isEmpty()) {
            StringBuilder tips = new StringBuilder();
            for (int i = 0; i < insight.tips.size(); i++) {
                tips.append(i + 1).append(". ").append(insight.tips.get(i));
                if (i < insight.tips.size() - 1) {
                    tips.append("\n\n");
                }
            }
            tipsView.setText(tips.toString());
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
