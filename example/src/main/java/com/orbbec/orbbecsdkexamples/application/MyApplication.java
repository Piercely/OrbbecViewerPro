package com.orbbec.orbbecsdkexamples.application;

import android.app.Application;

import com.orbbec.orbbecsdkexamples.utils.DebugLog;
import com.orbbec.orbbecsdkexamples.utils.MyCrashHandler;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DebugLog.init(this);
        Thread.setDefaultUncaughtExceptionHandler(new MyCrashHandler(this));
    }
}
