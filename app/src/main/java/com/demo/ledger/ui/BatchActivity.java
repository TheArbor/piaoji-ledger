package com.demo.ledger.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.demo.ledger.App;
import com.demo.ledger.R;
import com.demo.ledger.data.Bill;
import com.demo.ledger.data.BillRepository;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.net.LlmClient;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.demo.ledger.util.UriFiles;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 批量导入：多张图片依次识别，长截图自动切段，结果可逐条修改后一起保存。
 */
public class BatchActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATHS = "image_paths";

    private static final String TAG = "Ledger";

    private final List<String> paths = new ArrayList<>();
    private final List<Bill> bills = new ArrayList<>();
    private int processed;

    private Prefs prefs;
    private BillRepository repository;
    private BatchAdapter adapter;
    private TextView statusView;
    private TextView errorView;
    private TextView emptyView;
    private ProgressBar progress;
    private Button btnSave;

    private final ActivityResultLauncher<PickVisualMediaRequest> addLauncher =
            registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(20), uris -> {
                if (uris == null || uris.isEmpty()) {
                    return;
                }
                final List<Uri> picked = new ArrayList<>(uris);
                App.get().io().execute(() -> {
                    List<String> added = new ArrayList<>();
                    for (Uri uri : picked) {
                        try {
                            added.add(UriFiles.copyToReceipts(BatchActivity.this, uri).getAbsolutePath());
                        } catch (Exception e) {
                            Log.e(TAG, "拷贝图片失败", e);
                        }
                    }
                    App.get().ui(() -> {
                        if (added.isEmpty()) {
                            toast("图片读取失败");
                            return;
                        }
                        paths.addAll(added);
                        recognize();
                    });
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        prefs = new Prefs(this);
        repository = new BillRepository(this);

        String[] extra = getIntent().getStringArrayExtra(EXTRA_IMAGE_PATHS);
        if (extra != null) {
            for (String path : extra) {
                if (path != null) {
                    paths.add(path);
                }
            }
        }

        statusView = findViewById(R.id.batchStatus);
        errorView = findViewById(R.id.errorView);
        emptyView = findViewById(R.id.emptyView);
        progress = findViewById(R.id.progress);
        btnSave = findViewById(R.id.btnSave);

        adapter = new BatchAdapter();
        RecyclerView list = findViewById(R.id.batchList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        btnSave.setOnClickListener(v -> saveAll());
        findViewById(R.id.btnAdd).setOnClickListener(v -> addLauncher.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));

        if (paths.isEmpty()) {
            statusView.setText("没有收到图片");
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recognize();
        }
    }

    private void recognize() {
        if (!prefs.isConfigured()) {
            progress.setVisibility(View.GONE);
            errorView.setText("请先在设置页填写 BaseURL、API Key 和模型名，或查看“模型接入教程”");
            return;
        }
        if (processed >= paths.size()) {
            return;
        }
        progress.setVisibility(View.VISIBLE);
        btnSave.setEnabled(false);
        errorView.setText("");
        emptyView.setVisibility(View.GONE);

        final int total = paths.size();
        final File tileDir = new File(getCacheDir(), "tiles");
        App.get().io().execute(() -> {
            Throwable failure = null;
            try {
                for (int i = processed; i < total; i++) {
                    final int index = i;
                    final File imageFile = new File(paths.get(index));
                    App.get().ui(() -> statusView.setText(
                            String.format(Locale.CHINA, "正在识别第 %d/%d 张…", index + 1, total)));
                    try {
                        List<Bill> part = LlmClient.recognizeMany(prefs, imageFile, tileDir,
                                (done, count, found) -> App.get().ui(() -> {
                                    // 普通图片整图一次请求（count=1），超长截图分段推进时显示段落进度
                                    if (count > 1) {
                                        statusView.setText(String.format(Locale.CHINA,
                                                "正在识别第 %d/%d 张（长图 %d/%d 段）…",
                                                index + 1, total, Math.max(done, 0), count));
                                    }
                                }));
                        for (Bill bill : part) {
                            bill.imagePath = paths.get(index);
                            bill.source = "batch";
                        }
                        App.get().ui(() -> {
                            bills.addAll(part);
                            adapter.notifyDataSetChanged();
                            updateFooter();
                        });
                    } catch (Throwable t) {
                        // 单张失败（含超长图 OOM）不能中断整批，也不能让界面永远停在识别中
                        Log.e(TAG, "识别第 " + (index + 1) + " 张失败", t);
                        final String message = LlmClient.friendlyThrowable(t);
                        App.get().ui(() -> errorView.setText(String.format(Locale.CHINA,
                                "第 %d 张识别失败：%s", index + 1, message)));
                    } finally {
                        processed = index + 1;
                    }
                }
            } catch (Throwable t) {
                failure = t;
                Log.e(TAG, "批量识别中断", t);
            }
            final Throwable error = failure;
            App.get().ui(() -> {
                progress.setVisibility(View.GONE);
                if (error != null) {
                    errorView.setText("识别中断：" + LlmClient.friendlyThrowable(error));
                }
                statusView.setText(String.format(Locale.CHINA,
                        "共 %d 张图片 · 识别到 %d 笔支出", total, bills.size()));
                emptyView.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
                btnSave.setEnabled(!bills.isEmpty());
                updateFooter();
                if (processed < paths.size()) {
                    recognize();
                }
            });
        });
    }

    private void updateFooter() {
        btnSave.setText(bills.isEmpty() ? "全部保存"
                : String.format(Locale.CHINA, "全部保存（%d 笔）", bills.size()));
    }

    private void saveAll() {
        if (bills.isEmpty()) {
            return;
        }
        final List<Bill> snapshot = new ArrayList<>(bills);
        btnSave.setEnabled(false);
        App.get().io().execute(() -> {
            int saved = 0;
            for (Bill bill : snapshot) {
                try {
                    repository.insert(bill);
                    saved++;
                } catch (Exception e) {
                    Log.e(TAG, "保存账单失败", e);
                }
            }
            final int count = saved;
            App.get().ui(() -> {
                toast("已保存 " + count + " 笔");
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private void removeBill(int position) {
        if (position < 0 || position >= bills.size()) {
            return;
        }
        bills.remove(position);
        adapter.notifyItemRemoved(position);
        updateFooter();
        emptyView.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
        btnSave.setEnabled(!bills.isEmpty());
    }

    private void editBill(int position) {
        if (position < 0 || position >= bills.size()) {
            return;
        }
        final Bill bill = bills.get(position);
        int padding = dp(24);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, dp(4), padding, 0);

        EditText merchant = new EditText(this);
        merchant.setHint("商家");
        merchant.setSingleLine(true);
        merchant.setText(bill.merchant);

        EditText amount = new EditText(this);
        amount.setHint("金额（元）");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setText(String.format(Locale.CHINA, "%.2f", bill.amountCents / 100.0));

        EditText category = new EditText(this);
        category.setHint("分类：餐饮外卖/交通/购物/日用/娱乐/医疗/居住/其他");
        category.setSingleLine(true);
        category.setText(bill.category);

        final long[] picked = {bill.occurredAt};
        TextView date = new TextView(this);
        date.setText("日期：" + formatDate(picked[0]));
        date.setTextSize(14);
        date.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, Color.BLUE));
        date.setPadding(dp(4), dp(14), 0, dp(14));
        date.setOnClickListener(v -> {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(picked[0]);
            new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
                Calendar chosen = Calendar.getInstance();
                chosen.set(year, month, dayOfMonth, 12, 0, 0);
                picked[0] = chosen.getTimeInMillis();
                date.setText("日期：" + formatDate(picked[0]));
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                    calendar.get(Calendar.DAY_OF_MONTH)).show();
        });

        container.addView(merchant);
        container.addView(amount);
        container.addView(category);
        container.addView(date);

        new MaterialAlertDialogBuilder(this)
                .setTitle("修改账单")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    long cents = toCents(amount.getText().toString());
                    if (cents <= 0) {
                        toast("金额需要大于 0");
                        return;
                    }
                    String name = merchant.getText().toString().trim();
                    bill.merchant = name.isEmpty() ? "未知商家" : name;
                    bill.amountCents = cents;
                    String text = category.getText().toString().trim();
                    bill.category = Bill.isValidCategory(text) ? text : Bill.CATEGORY_OTHER;
                    bill.occurredAt = picked[0];
                    adapter.notifyItemChanged(position);
                })
                .show();
    }

    private String formatDate(long millis) {
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(new Date(millis));
    }

    private long toCents(String raw) {
        try {
            return new BigDecimal(raw.trim().isEmpty() ? "0" : raw.trim())
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class BatchAdapter extends RecyclerView.Adapter<BatchAdapter.Holder> {

        class Holder extends RecyclerView.ViewHolder {
            final TextView merchant;
            final TextView meta;
            final TextView amount;

            Holder(View itemView) {
                super(itemView);
                merchant = itemView.findViewById(R.id.merchant);
                meta = itemView.findViewById(R.id.meta);
                amount = itemView.findViewById(R.id.amount);
                itemView.findViewById(R.id.btnRemove)
                        .setOnClickListener(v -> removeBill(getAdapterPosition()));
                itemView.setOnClickListener(v -> editBill(getAdapterPosition()));
            }
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_batch_bill, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            Bill bill = bills.get(position);
            holder.merchant.setText(bill.merchant);
            holder.meta.setText(String.format(Locale.CHINA, "%s · %s",
                    bill.category, formatDate(bill.occurredAt)));
            holder.amount.setText(String.format(Locale.CHINA, "¥%,.2f", bill.amountCents / 100.0));
        }

        @Override
        public int getItemCount() {
            return bills.size();
        }
    }
}
