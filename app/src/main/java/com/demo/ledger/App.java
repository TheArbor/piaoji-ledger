package com.demo.ledger;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.google.android.material.color.DynamicColors;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {

    private static App instance;

    private ExecutorService io;
    private Handler main;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Material You：Android 12+ 跟随系统壁纸取色，低版本回落到主题里的品牌色
        DynamicColors.applyToActivitiesIfAvailable(this);
        io = Executors.newSingleThreadExecutor();
        main = new Handler(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public ExecutorService io() {
        return io;
    }

    public void ui(Runnable action) {
        main.post(action);
    }
}
