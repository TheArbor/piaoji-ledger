package com.demo.ledger.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class ImageCompressor {

    private static final String TAG = "Ledger";

    private ImageCompressor() {
    }

    public static Bitmap decodeSampled(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IllegalStateException("图片解码失败：" + file.getAbsolutePath());
        }

        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / sample > maxSide) {
            sample *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            throw new IllegalStateException("图片解码失败：" + file.getAbsolutePath());
        }

        int rotation = readRotation(file);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            bitmap = rotated;
        }
        return bitmap;
    }

    /**
     * 压到长边 maxSide 的 JPEG，供上传使用。
     */
    public static File compressToJpeg(File source, File target, int maxSide, int quality)
            throws IOException {
        Bitmap bitmap = decodeSampled(source, maxSide);
        FileOutputStream out = new FileOutputStream(target);
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw new IOException("图片压缩失败：" + source.getAbsolutePath());
            }
        } finally {
            out.close();
            bitmap.recycle();
        }
        return target;
    }

    @SuppressWarnings("deprecation")
    private static int readRotation(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            Log.w(TAG, "读取 EXIF 失败，按不旋转处理：" + file.getAbsolutePath(), e);
            return 0;
        }
    }
}
