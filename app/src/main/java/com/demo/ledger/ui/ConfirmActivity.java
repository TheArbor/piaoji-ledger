package com.demo.ledger.ui;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.demo.ledger.App;
import com.demo.ledger.R;
import com.demo.ledger.data.Bill;
import com.demo.ledger.data.BillRepository;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.net.LlmClient;
import com.demo.ledger.util.ImageCompressor;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.demo.ledger.util.UriFiles;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Locale;

public class ConfirmActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_PATH = "image_path";
    public static final String EXTRA_SOURCE = "source";

    private static final String TAG = "Ledger";

    private Prefs prefs;
    private BillRepository repository;

    private File imageFile;
    private String source;
    private String rawText;
    private long occurredAt = System.currentTimeMillis();
    private boolean recognizing;
    private boolean retryAfterSettings;

    private ImageView preview;
    private ProgressBar progress;
    private TextView status;
    private TextView errorView;
    private TextView dateView;
    private TextInputEditText inputMerchant;
    private TextInputEditText inputAmount;
    private MaterialAutoCompleteTextView inputCategory;
    private Button btnRetry;
    private Button btnSave;
    private Button btnGoSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_confirm);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        if (path == null) {
            throw new IllegalStateException("缺少图片路径，无法进入确认页");
        }
        imageFile = new File(path);
        if (!imageFile.exists()) {
            throw new IllegalStateException("图片不存在：" + path);
        }
        source = getIntent().getStringExtra(EXTRA_SOURCE);

        prefs = new Prefs(this);
        repository = new BillRepository(this);

        preview = findViewById(R.id.preview);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        errorView = findViewById(R.id.errorView);
        dateView = findViewById(R.id.dateView);
        inputMerchant = findViewById(R.id.inputMerchant);
        inputAmount = findViewById(R.id.inputAmount);
        inputCategory = findViewById(R.id.inputCategory);
        btnRetry = findViewById(R.id.btnRetry);
        btnSave = findViewById(R.id.btnSave);
        btnGoSettings = findViewById(R.id.btnGoSettings);

        inputCategory.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, Bill.CATEGORIES));
        inputCategory.setText(Bill.CATEGORY_OTHER, false);

        dateView.setOnClickListener(v -> pickDate());
        dateView.setText(formatDate());

        btnRetry.setOnClickListener(v -> recognize());
        btnSave.setOnClickListener(v -> save());
        btnGoSettings.setOnClickListener(v -> {
            retryAfterSettings = true;
            startActivity(new Intent(this, SettingsActivity.class));
        });

        loadPreview();
        recognize();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (retryAfterSettings && prefs.isConfigured()) {
            retryAfterSettings = false;
            recognize();
        }
    }

    private void loadPreview() {
        App.get().io().execute(() -> {
            try {
                Bitmap bitmap = ImageCompressor.decodeSampled(imageFile, 1200);
                App.get().ui(() -> preview.setImageBitmap(bitmap));
            } catch (Throwable t) {
                Log.e(TAG, "预览解码失败", t);
                App.get().ui(() -> showError("预览加载失败：" + LlmClient.friendlyThrowable(t)));
            }
        });
    }

    private void recognize() {
        if (recognizing) {
            return;
        }
        if (!prefs.isConfigured()) {
            status.setText("尚未配置模型");
            showError("请先在设置页填写 BaseURL、API Key 和模型名，或手动填写下方账单信息。");
            btnGoSettings.setVisibility(View.VISIBLE);
            return;
        }
        btnGoSettings.setVisibility(View.GONE);
        recognizing = true;
        progress.setVisibility(View.VISIBLE);
        status.setText("正在识别账单…");
        errorView.setText("");
        btnRetry.setEnabled(false);
        btnSave.setEnabled(false);

        App.get().io().execute(() -> {
            try {
                LlmClient.Recognized recognized = LlmClient.recognize(imageFile, prefs);
                rawText = recognized.raw;
                Bill bill = recognized.bill;
                App.get().ui(() -> {
                    recognizing = false;
                    progress.setVisibility(View.GONE);
                    btnRetry.setEnabled(true);
                    btnSave.setEnabled(true);
                    status.setText("请核对识别结果，可直接修改后保存");
                    fill(bill);
                });
            } catch (Throwable t) {
                // 包括超长图 OOM 在内的任何异常都要复位界面，避免一直停在“正在识别”
                Log.e(TAG, "识别失败", t);
                final String message = LlmClient.friendlyThrowable(t);
                App.get().ui(() -> {
                    recognizing = false;
                    progress.setVisibility(View.GONE);
                    btnRetry.setEnabled(true);
                    btnSave.setEnabled(true);
                    status.setText("识别失败，可手动填写");
                    showError(message);
                    if (!prefs.isConfigured()) {
                        btnGoSettings.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void fill(Bill bill) {
        inputMerchant.setText(bill.merchant);
        inputAmount.setText(String.format(Locale.CHINA, "%.2f", bill.amountCents / 100.0));
        if (Bill.isValidCategory(bill.category)) {
            inputCategory.setText(bill.category, false);
        }
        occurredAt = bill.occurredAt;
        dateView.setText(formatDate());
    }

    private void pickDate() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(occurredAt);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, y, m, d) -> {
            Calendar picked = Calendar.getInstance();
            picked.setTimeInMillis(occurredAt);
            picked.set(y, m, d);
            occurredAt = picked.getTimeInMillis();
            dateView.setText(formatDate());
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH));
        dialog.show();
    }

    private void save() {
        long cents;
        try {
            cents = new BigDecimal(text(inputAmount).replace("¥", "").replace("￥", "").trim())
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (Exception e) {
            showError("金额格式不正确，请填写数字，例如 12.50");
            return;
        }
        if (cents <= 0) {
            showError("金额必须大于 0");
            return;
        }
        String merchant = text(inputMerchant);
        if (TextUtils.isEmpty(merchant)) {
            showError("请填写商家名称");
            return;
        }
        String category = text(inputCategory);
        if (!Bill.isValidCategory(category)) {
            category = Bill.CATEGORY_OTHER;
        }

        Bill bill = new Bill();
        bill.amountCents = cents;
        bill.merchant = merchant;
        bill.category = category;
        bill.occurredAt = occurredAt;
        bill.imagePath = imageFile.getAbsolutePath();
        bill.source = source;
        bill.rawResponse = rawText;
        bill.createdAt = System.currentTimeMillis();

        final Bill toSave = bill;
        btnSave.setEnabled(false);
        App.get().io().execute(() -> {
            try {
                if (toSave.imagePath.startsWith(getCacheDir().getAbsolutePath())) {
                    toSave.imagePath = UriFiles.copyToReceipts(this, imageFile).getAbsolutePath();
                }
                repository.insert(toSave);
                App.get().ui(() -> {
                    Toast.makeText(this, "已记账", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                Log.e(TAG, "保存账单失败", e);
                App.get().ui(() -> {
                    btnSave.setEnabled(true);
                    showError("保存失败：" + e.getMessage());
                });
            }
        });
    }

    private String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private String formatDate() {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        return "交易时间：" + format.format(new java.util.Date(occurredAt));
    }

    private void showError(String message) {
        errorView.setText(message == null ? "未知错误" : message);
    }
}
