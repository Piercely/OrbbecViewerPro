package com.orbbec.orbbecsdkexamples.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.Frame;
import com.orbbec.obsensor.FrameCallback;
import com.orbbec.obsensor.FrameType;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoFrame;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;
import com.orbbec.orbbecsdkexamples.utils.CaptureController;
import com.orbbec.orbbecsdkexamples.utils.DebugLog;
import com.orbbec.orbbecsdkexamples.utils.LocalUtils;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 红外渲染示例
 * <p>
 * 对齐官方 App 的取流方式：使用 Sensor 级回调开流
 * （Device.getSensor -> sensor.start(profile, callback)），
 * 取代原先 Pipeline + waitForFrameSet 轮询（该方式在部分设备上红外流不出帧）。
 * 回调线程内只做数据拷贝与入队，渲染与拍照统一在消费线程完成。
 */
public class InfraredViewerActivity extends AppCompatActivity {
    private static final String TAG = "InfraredViewerActivity";

    private OBContext mOBContext;
    private Device mDevice;
    private Sensor mIrSensor;
    private VideoStreamProfile mIrProfile;

    // 帧队列：回调线程生产，消费线程渲染；满时丢弃最旧帧
    private final LinkedBlockingQueue<FrameSnapshot> mFrameQueue = new LinkedBlockingQueue<>(4);

    private Thread mConsumeThread;
    private volatile boolean mIsStreamRunning;

    private OBGLView mIrView;
    private CaptureController mCaptureController;
    private volatile boolean mFirstFrameLogged;
    private volatile boolean mErrorToasted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_infrared_viewer);
        DebugLog.log(TAG, "onCreate");
        mIrView = findViewById(R.id.irview_id);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        try {
            mCaptureController = new CaptureController(this,
                    findViewById(R.id.btn_shutter), findViewById(R.id.tv_waiting), "IR");
        } catch (Exception e) {
            Log.e(TAG, "init capture failed: " + e.getMessage(), e);
        }
        // 1. 初始化 SDK Context 并监听设备变化
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                DebugLog.log(TAG, "onDeviceAttach");
                try {
                    if (null == mIrSensor) {
                        startIrStream(deviceList);
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

    /**
     * 创建 Device 并以 Sensor 级回调方式开启红外流（对齐官方 App）
     */
    private void startIrStream(DeviceList deviceList) {
        try {
            // 2. 创建 Device 并获取红外 Sensor
            mDevice = deviceList.getDevice(0);

            Sensor irSensor = mDevice.getSensor(SensorType.IR);
            if (null == irSensor) {
                releaseDeviceQuietly();
                showToast(getString(R.string.device_not_support_ir));
                return;
            }

            // 3. 从传感器真实支持的配置列表中选择最合适的配置（期望 640@30，不限格式）
            VideoStreamProfile profile = null;
            StreamProfileList profileList = irSensor.getStreamProfileList();
            if (null != profileList) {
                profile = LocalUtils.pickVideoProfile(profileList, 640, 30, Format.UNKNOWN);
                profileList.close();
            }
            if (null == profile) {
                releaseDeviceQuietly();
                showToast("未找到可用的红外流配置");
                return;
            }
            Log.i(TAG, "ir profile: " + profile.getWidth() + "×" + profile.getHeight()
                    + "@" + profile.getFps() + "fps " + profile.getFormat());

            // 4. 回调方式开流；失败时明确提示（原先异常被静默吞掉导致页面永远等待）
            try {
                irSensor.start(profile, mFrameCallback);
            } catch (Exception e) {
                Log.e(TAG, "start ir sensor failed: " + e.getMessage(), e);
                profile.close();
                releaseDeviceQuietly();
                if (!mErrorToasted) {
                    mErrorToasted = true;
                    showToast("开启红外流失败: " + e.getMessage());
                }
                return;
            }

            // 5. 开流成功：记录 sensor/profile（退出时统一释放），启动消费线程
            mIrSensor = irSensor;
            mIrProfile = profile;
            mFirstFrameLogged = false;
            mErrorToasted = false;
            startConsumeThread();
            DebugLog.log(TAG, "ir sensor started");
        } catch (Exception e) {
            Log.e(TAG, "startIrStream error: " + e.getMessage(), e);
            releaseDeviceQuietly();
            if (!mErrorToasted) {
                mErrorToasted = true;
                showToast("初始化失败: " + e.getMessage());
            }
        }
    }

    /**
     * 帧回调（运行在 SDK 回调线程）：只拷贝数据并入队，尽快返回，绝不阻塞
     */
    private final FrameCallback mFrameCallback = frame -> {
        VideoFrame videoFrame = null;
        try {
            // as() 创建的包装与 frame 共享同一 native 句柄，finally 中只 close 一次
            videoFrame = frame.as(FrameType.VIDEO);
            byte[] data = new byte[videoFrame.getDataSize()];
            videoFrame.getData(data);
            FrameSnapshot snapshot = new FrameSnapshot(videoFrame.getWidth(), videoFrame.getHeight(),
                    StreamType.IR, videoFrame.getFormat(), 1.0f, data);
            // 队列满时丢弃最旧帧，保留最新帧
            if (!mFrameQueue.offer(snapshot)) {
                mFrameQueue.poll();
                mFrameQueue.offer(snapshot);
            }
        } catch (Exception e) {
            Log.w(TAG, "ir frame callback error: " + e);
        } finally {
            try {
                if (null != videoFrame) {
                    videoFrame.close();
                } else {
                    frame.close();
                }
            } catch (Exception ignored) {
            }
        }
    };

    /**
     * 消费线程：取最新帧渲染并发布给拍照模块
     */
    private final Runnable mConsumeRunnable = () -> {
        while (mIsStreamRunning) {
            FrameSnapshot snapshot;
            try {
                snapshot = mFrameQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (null == snapshot) {
                continue;
            }
            try {
                mIrView.update(snapshot.width, snapshot.height, StreamType.IR,
                        snapshot.format, snapshot.data, snapshot.valueScale);

                if (!mFirstFrameLogged) {
                    mFirstFrameLogged = true;
                    DebugLog.log(TAG, "first ir frame: " + snapshot.width + "x"
                            + snapshot.height + " " + snapshot.format);
                    showToast("已收到首帧画面");
                }

                // 拍照模块独立隔离，失败不影响渲染
                if (null != mCaptureController) {
                    mCaptureController.publish(snapshot);
                }
            } catch (Exception e) {
                DebugLog.log(TAG, "ir render error: " + e);
            }
        }
    };

    private void startConsumeThread() {
        mIsStreamRunning = true;
        if (null == mConsumeThread) {
            mConsumeThread = new Thread(mConsumeRunnable, "ir-consume");
            mConsumeThread.setUncaughtExceptionHandler((t, e) -> {
                DebugLog.log(TAG, "consume thread crashed: " + e);
                showToast("渲染线程崩溃: " + e);
            });
            mConsumeThread.start();
        }
    }

    private void stopConsumeThread() {
        mIsStreamRunning = false;
        if (null != mConsumeThread) {
            try {
                mConsumeThread.join(300);
            } catch (InterruptedException e) {
            }
            mConsumeThread = null;
        }
    }

    private void releaseDeviceQuietly() {
        if (null != mDevice) {
            try {
                mDevice.close();
            } catch (Exception ignored) {
            }
            mDevice = null;
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(InfraredViewerActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCaptureController != null) {
            mCaptureController.release();
        }
        try {
            // 1. 先停止传感器取流，再停止消费线程
            if (null != mIrSensor) {
                try {
                    mIrSensor.stop();
                } catch (Exception e) {
                    Log.w(TAG, "ir sensor stop failed: " + e.getMessage());
                }
            }
            stopConsumeThread();

            // 2. 释放流配置
            if (null != mIrProfile) {
                try {
                    mIrProfile.close();
                } catch (Exception ignored) {
                }
                mIrProfile = null;
            }

            // 3. Device.close() 会连带释放其内部缓存的 Sensor（无需手动 sensor.close()）
            releaseDeviceQuietly();

            // 4. 释放 SDK Context
            if (null != mOBContext) {
                mOBContext.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
