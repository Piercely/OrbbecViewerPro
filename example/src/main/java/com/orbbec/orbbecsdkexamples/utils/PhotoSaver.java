package com.orbbec.orbbecsdkexamples.utils;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves captured frames as JPEG into the system gallery
 * (Pictures/OrbbecViewer), works on both legacy storage and scoped storage.
 */
public final class PhotoSaver {

    private static final String TAG = "PhotoSaver";
    public static final String ALBUM_DIR = "OrbbecViewer";

    private static final SimpleDateFormat NAME_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private PhotoSaver() {
    }

    /**
     * Save a bitmap to the gallery.
     *
     * @param prefix file name prefix, e.g. "COLOR" / "DEPTH" / "IR"
     * @return a user readable save path on success, null on failure
     */
    public static String save(Context context, Bitmap bitmap, String prefix) {
        if (bitmap == null) {
            return null;
        }
        String fileName = prefix + "_" + NAME_FORMAT.format(new Date()) + ".jpg";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(context, bitmap, fileName);
        }
        return saveToPublicDirectory(context, bitmap, fileName);
    }

    private static String saveViaMediaStore(Context context, Bitmap bitmap, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = null;
        try {
            uri = context.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return null;
            }
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                    return null;
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return Environment.DIRECTORY_PICTURES + File.separator + ALBUM_DIR
                    + File.separator + fileName;
        } catch (Exception e) {
            Log.e(TAG, "saveViaMediaStore failed: " + e.getMessage(), e);
            if (uri != null) {
                context.getContentResolver().delete(uri, null, null);
            }
            return null;
        }
    }

    private static String saveToPublicDirectory(Context context, Bitmap bitmap, String fileName) {
        OutputStream out = null;
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    ALBUM_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "mkdirs failed: " + dir.getAbsolutePath());
                return null;
            }
            File file = new File(dir, fileName);
            out = new FileOutputStream(file);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                return null;
            }
            out.flush();
            MediaScannerConnection.scanFile(context,
                    new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            return file.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "saveToPublicDirectory failed: " + e.getMessage(), e);
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
