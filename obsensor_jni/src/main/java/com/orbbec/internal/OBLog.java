package com.orbbec.internal;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 内部文件日志（诊断桥）。
 * <p>
 * USB 枚举链路（Enumerator/DeviceWatcher/OBContext）原本只输出到 logcat，
 * 设备端通过 MTP 拉取 orbbec_debug.log 时完全看不到枚举环节，排查困难。
 * 本类把关键节点同步写入与 DebugLog 相同的日志文件
 * （Android/data/&lt;pkg&gt;/files/logs/orbbec_debug.log）。
 * <p>
 * example 模块的 DebugLog 会委托本类初始化与写文件，全 App 共用一个写入器。
 */
public final class OBLog {

    private static final long MAX_SIZE = 512 * 1024;
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File sFile;

    private OBLog() {
    }

    /**
     * 初始化日志文件。由 example 的 DebugLog.init 调用（writeStartMarker=true），
     * 避免与 DebugLog 各写一份产生重复标记。
     */
    public static synchronized void init(Context context, boolean writeStartMarker) {
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
            if (writeStartMarker) {
                log("DebugLog", "===== app start =====");
            }
        } catch (Throwable ignored) {
        }
    }

    /** 写一行日志：logcat + 文件双写；文件写入失败静默忽略 */
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
