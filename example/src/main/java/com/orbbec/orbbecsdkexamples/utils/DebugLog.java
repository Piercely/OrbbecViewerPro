package com.orbbec.orbbecsdkexamples.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Lightweight file logger for field diagnostics. Writes timestamped lines to
 * <code>Android/data/&lt;pkg&gt;/files/logs/orbbec_debug.log</code> which is
 * readable via MTP without any storage permission.
 */
public final class DebugLog {

    private static final long MAX_SIZE = 512 * 1024;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File sFile;

    private DebugLog() {
    }

    public static synchronized void init(Context context) {
        try {
            File dir = context.getExternalFilesDir("logs");
            if (dir == null) {
                return;
            }
            if (!dir.exists()) {
                dir.mkdirs();
            }
            sFile = new File(dir, "orbbec_debug.log");
            if (sFile.exists() && sFile.length() > MAX_SIZE) {
                sFile.delete();
            }
            log("DebugLog", "===== app start =====");
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void log(String tag, String msg) {
        Log.i(tag, msg);
        if (sFile == null) {
            return;
        }
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(sFile, true);
            fos.write((TS.format(new Date()) + " " + tag + ": " + msg + "\n").getBytes());
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static String getLogFilePath() {
        return sFile != null ? sFile.getAbsolutePath() : null;
    }
}
