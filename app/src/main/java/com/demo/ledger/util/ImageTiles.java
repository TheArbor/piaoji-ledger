package com.demo.ledger.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 超长截图（账单列表）先按纵向切段：整图压到 1280 后文字会糊，切段能保住可读性。
 */
public final class ImageTiles {

    private static final String TAG = "Ledger";
    private static final int MAX_WIDTH = 1200;
    /** 整图解码像素上限，避免超长截图直接 OOM；超出时降采样。 */
    private static final long MAX_PIXELS = 24_000_000L;

    private ImageTiles() {
    }

    public static List<File> split(File source, File outDir) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        int srcWidth = bounds.outWidth;
        int srcHeight = bounds.outHeight;
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("图片解码失败：" + source.getAbsolutePath());
        }

        List<File> result = new ArrayList<>();
        if (srcHeight <= srcWidth * 2) {
            result.add(source);
            return result;
        }

        int sample = 1;
        while (srcWidth / sample > MAX_WIDTH) {
            sample *= 2;
        }
        while ((long) (srcWidth / sample) * (srcHeight / sample) > MAX_PIXELS) {
            sample *= 2;
        }
        if (srcHeight / sample > 16000) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        // 账单截图没有透明通道，565 能把整图内存减半，降低长图 OOM 概率
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap full = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (full == null) {
            throw new IOException("图片解码失败：" + source.getAbsolutePath());
        }

        if (!outDir.exists() && !outDir.mkdirs()) {
            full.recycle();
            throw new IOException("无法创建切片目录：" + outDir.getAbsolutePath());
        }

        int width = full.getWidth();
        int height = full.getHeight();
        int tileHeight = Math.max(1, Math.round(width * 1.7f));
        int step = Math.max(1, Math.round(tileHeight * 0.86f));
        int index = 0;
        try {
            for (int top = 0; top < height; top += step) {
                int bottom = Math.min(top + tileHeight, height);
                Bitmap tile = Bitmap.createBitmap(full, 0, top, width, bottom - top);
                File out = new File(outDir,
                        "tile_" + System.currentTimeMillis() + "_" + (index++) + ".jpg");
                FileOutputStream stream = new FileOutputStream(out);
                try {
                    if (!tile.compress(Bitmap.CompressFormat.JPEG, 88, stream)) {
                        throw new IOException("切片压缩失败");
                    }
                } finally {
                    stream.close();
                    tile.recycle();
                }
                result.add(out);
                if (bottom >= height) {
                    break;
                }
            }
        } finally {
            full.recycle();
        }
        Log.d(TAG, "长图切段：" + srcWidth + "x" + srcHeight + " -> " + result.size() + " 段");
        return result;
    }
}
