package com.demo.ledger.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Prefs {

    /**
     * 预置的 OpenAI 兼容视觉模型供应商：{显示名, BaseURL, 模型名}
     */
    public static final String[][] PRESETS = {
            {"通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-max"},
            {"智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-plus"},
            {"Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-vision-preview"},
            {"火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "doubao-vision-pro-32k"},
            {"自定义", "", ""}
    };

    public static final String DEFAULT_BASE_URL = PRESETS[0][1];

    private static final String FILE = "ledger_prefs";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PRESET = "preset";
    private static final String KEY_PROFILES = "model_profiles";
    private static final String KEY_ACTIVE_PROFILE = "active_profile";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getBaseUrl() {
        return sp.getString(KEY_BASE_URL, "").trim();
    }

    public void setBaseUrl(String value) {
        sp.edit().putString(KEY_BASE_URL, value.trim()).apply();
    }

    public String getApiKey() {
        return sp.getString(KEY_API_KEY, "").trim();
    }

    public void setApiKey(String value) {
        sp.edit().putString(KEY_API_KEY, value.trim()).apply();
    }

    public String getModel() {
        return sp.getString(KEY_MODEL, "").trim();
    }

    public void setModel(String value) {
        sp.edit().putString(KEY_MODEL, value.trim()).apply();
    }

    public int getPresetIndex() {
        return sp.getInt(KEY_PRESET, 0);
    }

    public void applyPreset(int index) {
        if (index < 0 || index >= PRESETS.length) {
            return;
        }
        SharedPreferences.Editor editor = sp.edit().putInt(KEY_PRESET, index);
        if (!PRESETS[index][1].isEmpty()) {
            editor.putString(KEY_BASE_URL, PRESETS[index][1]);
            editor.putString(KEY_MODEL, PRESETS[index][2]);
            editor.remove(KEY_ACTIVE_PROFILE);
        }
        editor.apply();
    }

    public boolean isConfigured() {
        return !getBaseUrl().isEmpty() && !getApiKey().isEmpty() && !getModel().isEmpty();
    }

    /**
     * 已保存的模型配置，按保存顺序返回；解析失败时返回空列表，不让旧数据卡住设置页。
     */
    public List<ModelProfile> getProfiles() {
        List<ModelProfile> result = new ArrayList<>();
        String raw = sp.getString(KEY_PROFILES, "");
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                ModelProfile profile = new ModelProfile();
                profile.name = item.optString("name", "").trim();
                profile.baseUrl = item.optString("baseUrl", "").trim();
                profile.model = item.optString("model", "").trim();
                profile.apiKey = item.optString("apiKey", "").trim();
                if (!profile.name.isEmpty()) {
                    result.add(profile);
                }
            }
        } catch (JSONException ignored) {
            // 数据损坏时按没有配置处理
        }
        return result;
    }

    /**
     * 同名配置覆盖保存，避免列表里出现重复条目。
     */
    public void saveProfile(ModelProfile profile) {
        if (profile == null || profile.name == null || profile.name.trim().isEmpty()) {
            return;
        }
        profile.name = profile.name.trim();
        List<ModelProfile> profiles = getProfiles();
        boolean replaced = false;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).name.equalsIgnoreCase(profile.name)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            profiles.add(profile);
        }
        JSONArray array = new JSONArray();
        for (ModelProfile item : profiles) {
            JSONObject object = new JSONObject();
            try {
                object.put("name", item.name);
                object.put("baseUrl", item.baseUrl);
                object.put("model", item.model);
                object.put("apiKey", item.apiKey);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(object);
        }
        sp.edit().putString(KEY_PROFILES, array.toString()).apply();
    }

    public void deleteProfile(String name) {
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        List<ModelProfile> profiles = getProfiles();
        JSONArray array = new JSONArray();
        for (ModelProfile item : profiles) {
            if (item.name.equalsIgnoreCase(name.trim())) {
                continue;
            }
            JSONObject object = new JSONObject();
            try {
                object.put("name", item.name);
                object.put("baseUrl", item.baseUrl);
                object.put("model", item.model);
                object.put("apiKey", item.apiKey);
            } catch (JSONException ignored) {
                continue;
            }
            array.put(object);
        }
        SharedPreferences.Editor editor = sp.edit().putString(KEY_PROFILES, array.toString());
        if (name.trim().equalsIgnoreCase(getActiveProfileName())) {
            editor.remove(KEY_ACTIVE_PROFILE);
        }
        editor.apply();
    }

    public String getActiveProfileName() {
        return sp.getString(KEY_ACTIVE_PROFILE, "").trim();
    }

    public void setActiveProfileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            sp.edit().remove(KEY_ACTIVE_PROFILE).apply();
        } else {
            sp.edit().putString(KEY_ACTIVE_PROFILE, name.trim()).apply();
        }
    }

    /**
     * 加载一套已保存配置：BaseURL、模型名、API Key 一起恢复，并记下当前使用的配置名。
     */
    public void applyProfile(ModelProfile profile) {
        if (profile == null) {
            return;
        }
        sp.edit()
                .putString(KEY_BASE_URL, safe(profile.baseUrl))
                .putString(KEY_MODEL, safe(profile.model))
                .putString(KEY_API_KEY, safe(profile.apiKey))
                .putString(KEY_ACTIVE_PROFILE, safe(profile.name))
                .apply();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
