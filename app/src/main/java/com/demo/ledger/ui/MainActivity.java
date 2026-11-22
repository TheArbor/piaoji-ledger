package com.demo.ledger.ui;

import android.animation.ValueAnimator;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.demo.ledger.App;
import com.demo.ledger.R;
import com.demo.ledger.data.Bill;
import com.demo.ledger.data.BillRepository;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.demo.ledger.util.UriFiles;
import com.google.android.material.color.MaterialColors;

import java.io.File;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Ledger";

    private BillRepository repository;
    private BillAdapter adapter;

    private TextView monthLabel;
    private TextView totalAmount;
    private TextView billCount;
    private TextView statAvg;
    private TextView statMax;
    private TextView trendPeak;
    private TextView emptyView;
    private LinearLayout legend;
    private DonutView donut;
    private TrendChartView trendChart;
    private RecyclerView billList;
    private ValueAnimator totalAnimator;

    private int year;
    private int month;

    private File pendingCameraFile;

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                File file = pendingCameraFile;
                pendingCameraFile = null;
                if (result.getResultCode() == RESULT_OK && file != null && file.length() > 0) {
                    openConfirm(file, "camera");
                } else if (file != null) {
                    Log.w(TAG, "拍照未返回有效文件：" + file.getAbsolutePath());
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(20), uris -> {
                if (uris == null || uris.isEmpty()) {
                    return;
                }
                copyAllAndOpen(uris);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        repository = new BillRepository(this);
        adapter = new BillAdapter();

        monthLabel = findViewById(R.id.monthLabel);
        totalAmount = findViewById(R.id.totalAmount);
        billCount = findViewById(R.id.billCount);
        statAvg = findViewById(R.id.statAvg);
        statMax = findViewById(R.id.statMax);
        trendPeak = findViewById(R.id.trendPeak);
        emptyView = findViewById(R.id.emptyView);
        legend = findViewById(R.id.legend);
        donut = findViewById(R.id.donut);
        trendChart = findViewById(R.id.trendChart);

        billList = findViewById(R.id.billList);
        billList.setLayoutManager(new LinearLayoutManager(this));
        billList.setAdapter(adapter);
        billList.setLayoutAnimation(AnimationUtils.loadLayoutAnimation(this, R.anim.layout_reveal));

        Calendar now = Calendar.getInstance();
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH) + 1;
        monthLabel.setText(String.format(Locale.CHINA, "%d年%d月", year, month));

        findViewById(R.id.btnPrev).setOnClickListener(v -> shiftMonth(-1));
        findViewById(R.id.btnNext).setOnClickListener(v -> shiftMonth(1));
        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnInsight).setOnClickListener(v -> {
            Intent intent = new Intent(this, InsightActivity.class);
            intent.putExtra(InsightActivity.EXTRA_YEAR, year);
            intent.putExtra(InsightActivity.EXTRA_MONTH, month);
            startActivity(intent);
        });
        findViewById(R.id.btnReport).setOnClickListener(v -> {
            Intent intent = new Intent(this, ReportActivity.class);
            intent.putExtra(ReportActivity.EXTRA_YEAR, year);
            intent.putExtra(ReportActivity.EXTRA_MONTH, month);
            startActivity(intent);
        });
        findViewById(R.id.btnCamera).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnGallery).setOnClickListener(v -> pickFromGallery());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void shiftMonth(int delta) {
        month += delta;
        if (month > 12) {
            month = 1;
            year++;
        } else if (month < 1) {
            month = 12;
            year--;
        }
        monthLabel.setText(String.format(Locale.CHINA, "%d年%d月", year, month));
        loadData();
    }

    private void loadData() {
        final int targetYear = year;
        final int targetMonth = month;
        App.get().io().execute(() -> {
            List<Bill> bills = repository.listByMonth(targetYear, targetMonth);
            LinkedHashMap<String, long[]> categories = repository.categoryTotals(targetYear, targetMonth);
            long total = 0;
            for (Bill bill : bills) {
                total += bill.amountCents;
            }
            final long totalCents = total;
            App.get().ui(() -> {
                if (targetYear != year || targetMonth != month) {
                    return;
                }
                render(bills, categories, totalCents);
            });
        });
    }

    private void render(List<Bill> bills, LinkedHashMap<String, long[]> categories, long totalCents) {
        animateTotal(totalCents);
        billCount.setText(String.format(Locale.CHINA, "共 %d 笔", bills.size()));

        int daysInMonth = YearMonth.of(year, month).lengthOfMonth();
        long[] daily = new long[daysInMonth];
        long maxBill = 0;
        long dailyPeak = 0;
        for (Bill bill : bills) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(bill.occurredAt);
            int day = calendar.get(Calendar.DAY_OF_MONTH);
            if (day >= 1 && day <= daysInMonth) {
                daily[day - 1] += bill.amountCents;
            }
            if (bill.amountCents > maxBill) {
                maxBill = bill.amountCents;
            }
        }
        for (long value : daily) {
            if (value > dailyPeak) {
                dailyPeak = value;
            }
        }

        statAvg.setText(String.format(Locale.CHINA, "日均 ¥%,.2f", totalCents / 100.0 / daysInMonth));
        statMax.setText(String.format(Locale.CHINA, "最大单笔 ¥%,.2f", maxBill / 100.0));
        trendPeak.setText(dailyPeak == 0 ? "峰值 —"
                : String.format(Locale.CHINA, "峰值 ¥%,.0f", dailyPeak / 100.0));
        trendChart.setData(daily);

        adapter.submit(bills);
        emptyView.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);

        int[] palette = donut.palette();
        List<DonutView.Slice> slices = new ArrayList<>();
        legend.removeAllViews();
        int index = 0;
        for (LinkedHashMap.Entry<String, long[]> entry : categories.entrySet()) {
            long cents = entry.getValue()[0];
            long count = entry.getValue()[1];
            int color = palette[index % palette.length];
            slices.add(new DonutView.Slice(entry.getKey(), cents, color));
            legend.addView(buildChip(entry.getKey(), cents, count, totalCents, color));
            index++;
        }
        donut.setSlices(slices);
        if (!bills.isEmpty()) {
            billList.scheduleLayoutAnimation();
        }
    }

    private void animateTotal(long targetCents) {
        if (totalAnimator != null) {
            totalAnimator.cancel();
        }
        totalAnimator = ValueAnimator.ofFloat(0f, 1f);
        totalAnimator.setDuration(620);
        totalAnimator.setInterpolator(new DecelerateInterpolator(1.8f));
        totalAnimator.addUpdateListener(animation -> {
            long shown = Math.round(targetCents * (float) animation.getAnimatedValue());
            totalAmount.setText(String.format(Locale.CHINA, "¥%,.2f", shown / 100.0));
        });
        totalAnimator.start();
    }

    private View buildChip(String category, long cents, long count, long totalCents, int color) {
        int percent = totalCents == 0 ? 0 : (int) Math.round(cents * 100.0 / totalCents);
        SpannableStringBuilder text = new SpannableStringBuilder(String.format(Locale.CHINA,
                "● %s %d%% · %d笔  ¥%,.2f", category, percent, count, cents / 100.0));
        text.setSpan(new ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
        GradientDrawable background = new GradientDrawable();
        background.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY));
        background.setCornerRadius(dp(17));
        chip.setBackground(background);
        chip.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        return chip;
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建拍照缓存目录：" + dir.getAbsolutePath());
            }
            File file = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            pendingCameraFile = file;
            cameraLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            pendingCameraFile = null;
            toast("没有找到可用的相机应用");
            Log.e(TAG, "启动相机失败", e);
        }
    }

    private void pickFromGallery() {
        galleryLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void copyAllAndOpen(List<Uri> uris) {
        App.get().io().execute(() -> {
            List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    paths.add(UriFiles.copyToReceipts(this, uri).getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "拷贝相册图片失败", e);
                }
            }
            if (paths.isEmpty()) {
                App.get().ui(() -> toast("拷贝图片失败，请重试"));
                return;
            }
            App.get().ui(() -> {
                Intent intent = new Intent(this, BatchActivity.class);
                intent.putExtra(BatchActivity.EXTRA_IMAGE_PATHS, paths.toArray(new String[0]));
                startActivity(intent);
            });
        });
    }

    private void openConfirm(File file, String source) {
        Intent intent = new Intent(this, ConfirmActivity.class);
        intent.putExtra(ConfirmActivity.EXTRA_IMAGE_PATH, file.getAbsolutePath());
        intent.putExtra(ConfirmActivity.EXTRA_SOURCE, source);
        startActivity(intent);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
