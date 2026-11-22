package com.demo.ledger.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.demo.ledger.App;
import com.demo.ledger.util.UriFiles;

import java.util.ArrayList;
import java.util.List;

/**
 * 透明壳：接收微信/支付宝等应用分享进来的一张或多张截图，拷贝后转到批量导入页。
 */
public class ShareActivity extends AppCompatActivity {

    private static final String TAG = "Ledger";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        List<Uri> uris = UriFiles.imageUris(intent);
        if (uris.isEmpty()) {
            Log.e(TAG, "分享进来没有图片 Uri，action=" + intent.getAction());
            Toast.makeText(this, "没有拿到图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        App.get().io().execute(() -> {
            List<String> paths = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    paths.add(UriFiles.copyToReceipts(this, uri).getAbsolutePath());
                } catch (Exception e) {
                    Log.e(TAG, "分享图片拷贝失败", e);
                }
            }
            if (paths.isEmpty()) {
                App.get().ui(() -> {
                    Toast.makeText(this, "读取图片失败", Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
            App.get().ui(() -> {
                Intent batch = new Intent(this, BatchActivity.class);
                batch.putExtra(BatchActivity.EXTRA_IMAGE_PATHS, paths.toArray(new String[0]));
                startActivity(batch);
                finish();
            });
        });
    }
}
