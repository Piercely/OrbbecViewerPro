package com.orbbec.orbbecsdkexamples.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.orbbec.obsensor.DepthFrame;
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
 * Depth Viewer
 * <p>
 * 对齐官方 App 的取流方式：使用 Sensor 级回调开流
 * （Device.getSensor -> sensor.start(profile, callback)），
 * 取代原先 Pipeline + waitForFrameSet 轮询（该方式在部分设备上深度流不出帧）。
 * 回调线程内只做数据拷贝与入队，渲染与拍照统一在消费线程完成。
 */
public class DepthViewerActivity extends AppCompatActivity {
    private static final String TAG = "DepthViewerActivity";

    private OBContext mOBContext;
    private Device mDevice;
    private Sensor mDepthSensor;
    private VideoStreamProfile mDepthProfile;

    // 帧队列：回调线程生产，消费线程渲染；满时丢弃最旧帧
    private final LinkedBlockingQueue<FrameSnapshot> mFrameQueue = new LinkedBlockingQueue<>(4);

    private Thread mConsumeThread;
    private volatile boolean mIsStreamRunning;

    private OBGLView mDepthView;
    private CaptureController mCaptureController;
    private volatile boolean mFirstFrameLogged;
    private volatile boolean mErrorToasted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_depth_viewer);
        DebugLog.log(TAG, "onCreate");
        mDepthView = findViewById(R.id.depthview_id);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        try {
            mCaptureController = new CaptureController(this,
                    findViewById(R.id.btn_shutter), findViewById(R.id.tv_waiting), "DEPTH");
        } catch (Exception e) {
            Log.e(TAG, "init capture failed: " + e.getMessage(), e);
        }
        // 1. 初始化 SDK Context 并监听设备变化
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                DebugLog.log(TAG, "onDeviceAttach");
                try {
                    if (null == mDepthSensor) {
                        startDepthStream(deviceList);
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
     * 创建 Device 并以 Sensor 级回调方式开启深度流（对齐官方 App）
     */
    private void startDepthStream(DeviceList deviceList) {
        try {
            // 2. 创建 Device 并获取深度 Sensor
            mDevice = deviceList.getDevice(0);

            Sensor depthSensor = mDevice.getSensor(SensorType.DEPTH);
            if (null == depthSensor) {
                releaseDeviceQuietly();
                showToast(getString(R.string.device_not_support_depth));
                return;
            }

            // 3. 从传感器真实支持的配置列表中选择最合适的配置（期望 640@30，不限格式）
            VideoStreamProfile profile = null;
            StreamProfileList profileList = depthSensor.getStreamProfileList();
            if (null != profileList) {
                profile = LocalUtils.pickVideoProfile(profileList, 640, 30, Format.UNKNOWN);
                profileList.close();
            }
            if (null == profile) {
                releaseDeviceQuietly();
                showToast("未找到可用的深度流配置");
                return;
            }
            Log.i(TAG, "depth profile: " + profile.getWidth() + "×" + profile.getHeight()
                    + "@" + profile.getFps() + "fps " + profile.getFormat());

            // 4. 回调方式开流；失败时明确提示（原先异常被静默吞掉导致页面永远等待）
            try {
                depthSensor.start(profile, mFrameCallback);
            } catch (Exception e) {
                Log.e(TAG, "start depth sensor failed: " + e.getMessage(), e);
                profile.close();
                releaseDeviceQuietly();
                if (!mErrorToasted) {
                    mErrorToasted = true;
                    showToast("开启深度流失败: " + e.getMessage());
                }
                return;
            }

            // 5. 开流成功：记录 sensor/profile（退出时统一释放），启动消费线程
            mDepthSensor = depthSensor;
            mDepthProfile = profile;
            mFirstFrameLogged = false;
            mErrorToasted = false;
            startConsumeThread();
            DebugLog.log(TAG, "depth sensor started");
        } catch (Exception e) {
            Log.e(TAG, "startDepthStream error: " + e.getMessage(), e);
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
        DepthFrame depthFrame = null;
        try {
            // as() 创建的包装与 frame 共享同一 native 句柄，finally 中只 close 一次
            depthFrame = frame.as(FrameType.DEPTH);
            byte[] data = new byte[depthFrame.getDataSize()];
            depthFrame.getData(data);
            float scale = depthFrame.getValueScale();
            FrameSnapshot snapshot = new FrameSnapshot(depthFrame.getWidth(), depthFrame.getHeight(),
                    StreamType.DEPTH, depthFrame.getFormat(), scale, data);
            // 队列满时丢弃最旧帧，保留最新帧
            if (!mFrameQueue.offer(snapshot)) {
                mFrameQueue.poll();
                mFrameQueue.offer(snapshot);
            }
        } catch (Exception e) {
            Log.w(TAG, "depth frame callback error: " + e);
        } finally {
            try {
                if (null != depthFrame) {
                    depthFrame.close();
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
                mDepthView.update(snapshot.width, snapshot.height, StreamType.DEPTH,
                        snapshot.format, snapshot.data, snapshot.valueScale);

                if (!mFirstFrameLogged) {
                    mFirstFrameLogged = true;
                    DebugLog.log(TAG, "first depth frame: " + snapshot.width + "x"
                            + snapshot.height + " " + snapshot.format);
                    showToast("已收到首帧画面");
                }

                // 拍照模块独立隔离，失败不影响渲染
                if (null != mCaptureController) {
                    mCaptureController.publish(snapshot);
                }
            } catch (Exception e) {
                DebugLog.log(TAG, "depth render error: " + e);
            }
        }
    };

    private void startConsumeThread() {
        mIsStreamRunning = true;
        if (null == mConsumeThread) {
            mConsumeThread = new Thread(mConsumeRunnable, "depth-consume");
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
        runOnUiThread(() -> Toast.makeText(DepthViewerActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCaptureController != null) {
            mCaptureController.release();
        }
        try {
            // 1. 先停止传感器取流，再停止消费线程
            if (null != mDepthSensor) {
                try {
                    mDepthSensor.stop();
                } catch (Exception e) {
                    Log.w(TAG, "depth sensor stop failed: " + e.getMessage());
                }
            }
            stopConsumeThread();

            // 2. 释放流配置
            if (null != mDepthProfile) {
                try {
                    mDepthProfile.close();
                } catch (Exception ignored) {
                }
                mDepthProfile = null;
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
