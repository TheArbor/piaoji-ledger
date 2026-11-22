package com.demo.ledger.util;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public final class UriFiles {

    private static int counter;

    private UriFiles() {
    }

    /**
     * content:// 授权只在当前 Activity 生命周期内有效，拿到后必须立刻拷贝到内部存储。
     */
    public static File copyToReceipts(Context context, Uri uri) throws IOException {
        File dir = new File(context.getFilesDir(), "receipts");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建图片目录：" + dir.getAbsolutePath());
        }
        File target = new File(dir, "r_" + System.currentTimeMillis() + "_" + (counter++) + ".jpg");
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法打开图片流：" + uri);
        }
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return target;
    }

    /**
     * 拍照结果放在 cache 里会被系统回收，落库前转存到内部存储。
     */
    public static File copyToReceipts(Context context, File source) throws IOException {
        File dir = new File(context.getFilesDir(), "receipts");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建图片目录：" + dir.getAbsolutePath());
        }
        File target = new File(dir, "r_" + System.currentTimeMillis() + "_" + (counter++) + ".jpg");
        InputStream in = new FileInputStream(source);
        try {
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
        return target;
    }

    /**
     * 分享进来可能是一张或多张：EXTRA_STREAM、ArrayList 或 clipData 都兼容。
     */
    @SuppressWarnings("deprecation")
    public static List<Uri> imageUris(Intent intent) {
        List<Uri> result = new ArrayList<>();
        Parcelable extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (extra instanceof Uri) {
            result.add((Uri) extra);
        } else if (extra instanceof ArrayList) {
            for (Object item : (ArrayList<?>) extra) {
                if (item instanceof Uri && !result.contains(item)) {
                    result.add((Uri) item);
                }
            }
        }
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null && !result.contains(uri)) {
                    result.add(uri);
                }
            }
        }
        return result;
    }

    /**
     * 分享进来的图片可能放在 EXTRA_STREAM，也可能只在 clipData 里。
     */
    public static Uri firstImageUri(Intent intent) {
        List<Uri> uris = imageUris(intent);
        return uris.isEmpty() ? null : uris.get(0);
    }
}
