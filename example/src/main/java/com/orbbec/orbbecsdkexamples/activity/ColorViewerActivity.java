package com.orbbec.orbbecsdkexamples.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.StreamType;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;
import com.orbbec.orbbecsdkexamples.utils.CaptureController;
import com.orbbec.orbbecsdkexamples.utils.DebugLog;
import com.orbbec.orbbecsdkexamples.utils.SingleStreamSource;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

/**
 * 彩色画面页
 * <p>
 * 取流统一交给 {@link SingleStreamSource}：Sensor 级回调优先（对齐官方 App），
 * 首帧超时自动回退 Pipeline 轮询路径（原先验证可用的方式），保证彩色画面始终可用。
 */
public class ColorViewerActivity extends AppCompatActivity {
    private static final String TAG = "ColorViewerActivity";

    private OBContext mOBContext;
    private Device mDevice;
    private SingleStreamSource mSource;

    private OBGLView mColorView;
    private CaptureController mCaptureController;
    private volatile boolean mFirstFrameLogged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_color_viewer);
        DebugLog.log(TAG, "onCreate");
        mColorView = findViewById(R.id.colorview_id);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        try {
            mCaptureController = new CaptureController(this,
                    findViewById(R.id.btn_shutter), findViewById(R.id.tv_waiting), "COLOR");
        } catch (Exception e) {
            Log.e(TAG, "init capture failed: " + e.getMessage(), e);
        }
        // 1. 初始化 SDK Context 并监听设备变化
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                DebugLog.log(TAG, "onDeviceAttach");
                try {
                    if (mSource == null) {
                        startStream(deviceList);
                    }
                } finally {
                    // 释放 device list 资源
                    try {
                        deviceList.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            @Override
            public void onDeviceDetach(DeviceList deviceList) {
                try {
                    deviceList.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void startStream(DeviceList deviceList) {
        try {
            mDevice = deviceList.getDevice(0);
            if (mDevice == null) {
                showToast("设备创建失败");
                return;
            }
            mSource = new SingleStreamSource(mDevice, StreamType.COLOR, 640, 30, Format.RGB888,
                    new SingleStreamSource.Listener() {
                        @Override
                        public void onFrame(FrameSnapshot snapshot) {
                            try {
                                mColorView.update(snapshot.width, snapshot.height, StreamType.COLOR,
                                        snapshot.format, snapshot.data, snapshot.valueScale);
                                if (mCaptureController != null) {
                                    mCaptureController.publish(snapshot);
                                }
                                if (!mFirstFrameLogged) {
                                    mFirstFrameLogged = true;
                                    DebugLog.log(TAG, "first color frame: " + snapshot.width + "x"
                                            + snapshot.height + " " + snapshot.format);
                                    showToast("已收到首帧画面");
                                }
                            } catch (Exception e) {
                                DebugLog.log(TAG, "color render error: " + e);
                            }
                        }

                        @Override
                        public void onInfo(String msg) {
                            showToast(msg);
                        }
                    });
            mSource.start();
        } catch (Exception e) {
            Log.e(TAG, "startStream error: " + e.getMessage(), e);
            DebugLog.log(TAG, "startStream error: " + e);
            showToast("初始化失败: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(ColorViewerActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCaptureController != null) {
            mCaptureController.release();
        }
        try {
            // 1. 停止取流并释放 sensor/pipeline 资源
            if (mSource != null) {
                mSource.stop();
                mSource = null;
            }
            // 2. 释放设备（连带释放内部缓存的 Sensor）
            if (mDevice != null) {
                try {
                    mDevice.close();
                } catch (Exception ignored) {
                }
                mDevice = null;
            }
            // 3. 释放 SDK Context
            if (mOBContext != null) {
                mOBContext.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
