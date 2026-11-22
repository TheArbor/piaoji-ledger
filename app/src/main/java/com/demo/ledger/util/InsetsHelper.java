package com.demo.ledger.util;

import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public final class InsetsHelper {

    private InsetsHelper() {
    }

    /**
     * targetSdk 35 强制 edge-to-edge，根布局需要自己补系统栏与输入法占位。
     */
    public static void pad(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });
    }
}
