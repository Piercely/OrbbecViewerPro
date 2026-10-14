package com.orbbec.orbbecsdkexamples.utils;

import android.content.Context;

/**
 * Lightweight file logger for field diagnostics. Writes timestamped lines to
 * <code>Android/data/&lt;pkg&gt;/files/logs/orbbec_debug.log</code> which is
 * readable via MTP without any storage permission.
 * <p>
 * 实际实现委托给 obsensor_jni 模块的 {@link com.orbbec.internal.OBLog}，
 * 使枚举链路（Enumerator/DeviceWatcher/OBContext）与应用层共用同一个日志文件。
 */
public final class DebugLog {

    private DebugLog() {
    }

    public static synchronized void init(Context context) {
        com.orbbec.internal.OBLog.init(context, true);
    }

    public static synchronized void log(String tag, String msg) {
        com.orbbec.internal.OBLog.log(tag, msg);
    }

    public static String getLogFilePath() {
        return com.orbbec.internal.OBLog.getLogFilePath();
    }
}
