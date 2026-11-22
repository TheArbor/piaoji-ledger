package com.demo.ledger.ui;

import android.os.Bundle;
import android.content.Intent;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.demo.ledger.App;
import com.demo.ledger.R;
import com.demo.ledger.data.ModelProfile;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.net.LlmClient;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "Ledger";

    private Prefs prefs;
    private MaterialAutoCompleteTextView presetDropdown;
    private MaterialAutoCompleteTextView profileDropdown;
    private TextInputEditText inputBaseUrl;
    private TextInputEditText inputApiKey;
    private TextInputEditText inputModel;
    private TextView testResult;
    private Button btnTest;
    private Button btnSave;

    private final List<ModelProfile> profiles = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        prefs = new Prefs(this);
        presetDropdown = findViewById(R.id.presetDropdown);
        profileDropdown = findViewById(R.id.profileDropdown);
        inputBaseUrl = findViewById(R.id.inputBaseUrl);
        inputApiKey = findViewById(R.id.inputApiKey);
        inputModel = findViewById(R.id.inputModel);
        testResult = findViewById(R.id.testResult);
        btnTest = findViewById(R.id.btnTest);
        btnSave = findViewById(R.id.btnSave);
        findViewById(R.id.btnTutorial).setOnClickListener(v ->
                startActivity(new Intent(this, TutorialActivity.class)));

        String[] names = new String[Prefs.PRESETS.length];
        for (int i = 0; i < Prefs.PRESETS.length; i++) {
            names[i] = Prefs.PRESETS[i][0];
        }
        presetDropdown.setSimpleItems(names);
        int presetIndex = Math.max(0, Math.min(prefs.getPresetIndex(), names.length - 1));
        presetDropdown.setText(names[presetIndex], false);

        fill();

        // 只有用户真正点选预设时才覆盖输入框，避免打开设置页就把已保存的自定义配置冲掉
        presetDropdown.setOnItemClickListener((parent, view, position, id) -> {
            prefs.applyPreset(position);
            fill();
        });

        profileDropdown.setOnItemClickListener((parent, view, position, id) -> {
            if (position <= 0 || position > profiles.size()) {
                return;
            }
            ModelProfile profile = profiles.get(position - 1);
            prefs.applyProfile(profile);
            fill();
            testResult.setTextColor(getColor(R.color.primary));
            testResult.setText("已加载配置：" + profile.name);
        });

        refreshProfiles(null);

        findViewById(R.id.btnSaveProfile).setOnClickListener(v -> saveCurrentAsProfile());
        findViewById(R.id.btnDeleteProfile).setOnClickListener(v -> deleteSelectedProfile());

        btnSave.setOnClickListener(v -> {
            persist();
            testResult.setText("已保存");
            testResult.setTextColor(getColor(R.color.primary));
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        });

        btnTest.setOnClickListener(v -> testConnection());
    }

    private void fill() {
        String baseUrl = prefs.getBaseUrl();
        inputBaseUrl.setText(baseUrl.isEmpty() ? Prefs.DEFAULT_BASE_URL : baseUrl);
        inputApiKey.setText(prefs.getApiKey());
        inputModel.setText(prefs.getModel());
    }

    private void persist() {
        prefs.setBaseUrl(value(inputBaseUrl));
        prefs.setApiKey(value(inputApiKey));
        prefs.setModel(value(inputModel));
        Log.d(TAG, "已保存模型配置，key=" + LlmClient.maskKey(prefs.getApiKey()));
    }

    /**
     * 保存当前填写的参数为一条可复用配置，同名则覆盖。
     */
    private void saveCurrentAsProfile() {
        persist();
        if (!prefs.isConfigured()) {
            Toast.makeText(this, "请先填写 BaseURL、API Key 和模型名", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("配置名称，例如：公司通义");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(prefs.getActiveProfileName());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        input.setPadding(padding, input.getPaddingTop(), padding, input.getPaddingBottom());

        new MaterialAlertDialogBuilder(this)
                .setTitle("保存为配置")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText() == null ? "" : input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "配置名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ModelProfile profile = new ModelProfile();
                    profile.name = name;
                    profile.baseUrl = value(inputBaseUrl);
                    profile.model = value(inputModel);
                    profile.apiKey = value(inputApiKey);
                    prefs.saveProfile(profile);
                    prefs.setActiveProfileName(name);
                    refreshProfiles(name);
                    Toast.makeText(this, "已保存配置：" + name, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void deleteSelectedProfile() {
        String name = profileDropdown.getText() == null
                ? "" : profileDropdown.getText().toString().trim();
        ModelProfile selected = null;
        for (ModelProfile profile : profiles) {
            if (profile.name.equalsIgnoreCase(name)) {
                selected = profile;
                break;
            }
        }
        if (selected == null) {
            Toast.makeText(this, "请先选择一个已保存的配置", Toast.LENGTH_SHORT).show();
            return;
        }
        final ModelProfile profile = selected;
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除配置")
                .setMessage("确定删除“" + profile.name + "”？当前填写的参数不会被清空。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    prefs.deleteProfile(profile.name);
                    refreshProfiles(null);
                    Toast.makeText(this, "已删除：" + profile.name, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void refreshProfiles(String selectName) {
        profiles.clear();
        profiles.addAll(prefs.getProfiles());
        List<String> names = new ArrayList<>();
        names.add("未选择已保存配置");
        for (ModelProfile profile : profiles) {
            names.add(profile.name);
        }
        String[] items = names.toArray(new String[0]);
        profileDropdown.setSimpleItems(items);

        String target = selectName == null ? prefs.getActiveProfileName() : selectName;
        int selection = 0;
        if (target != null && !target.isEmpty()) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).name.equalsIgnoreCase(target)) {
                    selection = i + 1;
                    break;
                }
            }
        }
        profileDropdown.setText(items[selection], false);
    }

    private void testConnection() {
        persist();
        testResult.setTextColor(getColor(R.color.primary));
        testResult.setText("测试中…");
        btnTest.setEnabled(false);
        App.get().io().execute(() -> {
            try {
                String message = LlmClient.testConnection(prefs);
                App.get().ui(() -> {
                    btnTest.setEnabled(true);
                    testResult.setText(message);
                });
            } catch (Exception e) {
                Log.e(TAG, "测试连接失败", e);
                App.get().ui(() -> {
                    btnTest.setEnabled(true);
                    testResult.setTextColor(getColor(R.color.error));
                    testResult.setText("连接失败：" + e.getMessage());
                });
            }
        });
    }

    private String value(TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }
}
