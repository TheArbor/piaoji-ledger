package com.demo.ledger.ui;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.demo.ledger.R;
import com.demo.ledger.data.Prefs;
import com.demo.ledger.util.InsetsHelper;
import com.demo.ledger.util.Motion;
import com.google.android.material.color.MaterialColors;

import java.util.Locale;

/**
 * 模型接入教程：把四家预置供应商的地址与模型名直接展示出来。
 */
public class TutorialActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tutorial);
        InsetsHelper.pad(findViewById(R.id.root));
        Motion.applyWindow(this);

        LinearLayout list = findViewById(R.id.presetList);
        for (String[] preset : Prefs.PRESETS) {
            if (preset[1].isEmpty()) {
                continue;
            }
            list.addView(buildPresetRow(preset[0], preset[1], preset[2]));
        }
    }

    private LinearLayout buildPresetRow(String name, String baseUrl, String model) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView title = new TextView(this);
        title.setText(String.format(Locale.CHINA, "%s · %s", name, model));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY));
        row.addView(title);

        TextView url = new TextView(this);
        url.setText(baseUrl);
        url.setTextSize(11);
        url.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY));
        row.addView(url);
        return row;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
