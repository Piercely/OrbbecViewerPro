package com.orbbec.orbbecsdkexamples.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes crash stack traces to the app-private external files dir (readable via
 * MTP on all Android versions), then chains to the default handler so the
 * process actually terminates instead of hanging.
 */
public class MyCrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context mContext;
    private final Thread.UncaughtExceptionHandler mDefaultHandler;

    public MyCrashHandler(Context context) {
        mContext = context.getApplicationContext();
        mDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            saveCrashInfoToFile(t, e);
        } catch (Throwable ignored) {
        }
        DebugLog.log("CRASH", "uncaught in thread " + t.getName() + ": " + e);
        if (mDefaultHandler != null) {
            mDefaultHandler.uncaughtException(t, e);
        } else {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private void saveCrashInfoToFile(Thread t, Throwable ex) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        Throwable cause = ex.getCause();
        while (cause != null) {
            cause.printStackTrace(pw);
            cause = cause.getCause();
        }
        pw.close();

        File dir = new File(mContext.getExternalFilesDir(null), "crash");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        FileOutputStream fos = new FileOutputStream(new File(dir, name));
        fos.write(("Thread: " + t.getName() + "\n\n" + sw).getBytes());
        fos.close();
    }
}
