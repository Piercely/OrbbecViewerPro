package com.orbbec.orbbecsdkexamples.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.libyuv.util.YuvUtil;
import com.orbbec.obsensor.AlignMode;
import com.orbbec.obsensor.ColorFrame;
import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.DepthFrame;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.FrameSetCallback;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.utils.DebugLog;
import com.orbbec.orbbecsdkexamples.utils.ImageUtils;
import com.orbbec.orbbecsdkexamples.utils.LocalUtils;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SyncAlignViewer
 * <p>
 * 对齐官方 App 的 D2C 取流方式：Pipeline 回调式开流
 * （Config + setAlignMode + setDepthScaleRequire + Pipeline.start(config, FrameSetCallback)），
 * 取代原先 waitForFrameSet 轮询（该方式在部分设备上取不到帧导致页面黑屏）。
 * <p>
 * In this example, the depth map and color image mirroring status may be inconsistent because the
 * depth or color sensor does not support mirroring, resulting in the depth map and color map displaying
 * opposite images. If this situation is encountered, set the mirroring interface. Just keep the two
 * mirroring states consistent. In addition, the resolution obtained by some devices may not support
 * the D2C function. Therefore, the D2C function is subject to the actual supported D2C resolution.
 * <p>
 * For example: The resolution of D2C supported by DaBai DCW is 640x360, but the actual resolution obtained
 * by this example may be 640x480. In this case, the user can obtain the corresponding 640x360 resolution
 * according to the actual module situation.
 */
public class SyncAlignViewerActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener, View.OnClickListener {
    private static final String TAG = "SyncAlignViewerActivity";

    private OBContext mOBContext;
    private Device mDevice;
    private Pipeline mPipeline;
    private Config mConfig;

    // 帧对队列：回调线程拷贝原始数据入队，消费线程解码/叠加/渲染
    private final LinkedBlockingQueue<PairSnapshot> mFrameQueue = new LinkedBlockingQueue<>(4);

    private Thread mConsumeThread;
    private volatile boolean mIsStreamRunning;

    // 看门狗回退：回调路径无首帧时自动切换 waitForFrameSet 轮询
    private volatile boolean mUsePolling;
    private volatile boolean mFirstFrameSeen;
    private boolean mWatchdogStarted;
    private Thread mPollThread;

    private OBGLView mColorView;
    private CheckBox mSyncCb;
    private CheckBox mHardwareD2CCb;
    private CheckBox mSoftwareD2CCb;

    private TextView tvTransparency;
    private SeekBar sbTransparency;
    private float mAlpha = 0.5f;

    private volatile boolean mFirstFrameLogged;
    private volatile boolean mErrorToasted;

    private ByteBuffer mDepthSrcBuffer;
    private ByteBuffer mDepthDstBuffer;

    private ByteBuffer mColorSrcBuffer;
    private ByteBuffer mColorDstBuffer;

    /**
     * 一帧同步数据的不可变快照（深度+彩色原始数据）
     */
    private static final class PairSnapshot {
        final int depthW, depthH;
        final Format depthFormat;
        final float depthValueScale;
        final byte[] depthData;
        final int colorW, colorH;
        final Format colorFormat;
        final byte[] colorData;

        PairSnapshot(int depthW, int depthH, Format depthFormat, float depthValueScale, byte[] depthData,
                     int colorW, int colorH, Format colorFormat, byte[] colorData) {
            this.depthW = depthW;
            this.depthH = depthH;
            this.depthFormat = depthFormat;
            this.depthValueScale = depthValueScale;
            this.depthData = depthData;
            this.colorW = colorW;
            this.colorH = colorH;
            this.colorFormat = colorFormat;
            this.colorData = colorData;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("SyncAlignViewer");
        setContentView(R.layout.activity_sync_align_viewer);
        initView();

        initOBContext();
    }

    private void initOBContext() {
        // 1. 初始化 SDK Context 并监听设备变化
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                DebugLog.log(TAG, "onDeviceAttach");
                try {
                    if (null == mPipeline) {
                        startPipeline(deviceList);
                    }
                } finally {
                    // 释放 DeviceList
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
     * 创建 Device/Pipeline 并以回调方式开启双流（对齐官方 genD2CStreamConfig）
     */
    private void startPipeline(DeviceList deviceList) {
        try {
            // 2. 创建 Device，检查深度/彩色传感器
            Device device = deviceList.getDevice(0);
            mDevice = device;

            if (null == device.getSensor(SensorType.DEPTH)) {
                showToast(getString(R.string.device_not_support_depth));
                releaseDeviceQuietly();
                return;
            }
            if (null == device.getSensor(SensorType.COLOR)) {
                showToast(getString(R.string.device_not_support_color));
                releaseDeviceQuietly();
                return;
            }

            Pipeline pipeline = new Pipeline(device);
            Config config = new Config();

            // 3. D2C 默认关闭；深度缩放对齐官方设置
            config.setAlignMode(AlignMode.ALIGN_D2C_DISABLE);
            config.setDepthScaleRequire(true);

            // 4. 从传感器真实列表选择彩色流配置（期望 640@30 RGB888）
            VideoStreamProfile colorProfile = null;
            StreamProfileList colorProfileList = device.getSensor(SensorType.COLOR).getStreamProfileList();
            if (null != colorProfileList) {
                colorProfile = LocalUtils.pickVideoProfile(colorProfileList, 640, 30, Format.RGB888);
                colorProfileList.close();
            }
            if (null == colorProfile) {
                Log.w(TAG, "startPipeline: no color stream profile!");
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                showToast("未找到可用的彩色流配置");
                return;
            }

            // 5. 选择支持 D2C 的深度流配置（官方做法：按彩色分辨率查询 D2C 兼容列表）
            VideoStreamProfile depthProfile = null;
            StreamProfileList d2cDepthList = null;
            try {
                d2cDepthList = pipeline.getD2CDepthProfileList(colorProfile, AlignMode.ALIGN_D2C_DISABLE);
            } catch (Exception e) {
                Log.w(TAG, "getD2CDepthProfileList failed: " + e.getMessage());
            }
            if (null != d2cDepthList) {
                depthProfile = LocalUtils.pickVideoProfile(d2cDepthList, 640, 30, Format.UNKNOWN);
                d2cDepthList.close();
            }
            if (null == depthProfile) {
                // 回退：从传感器真实列表选择
                StreamProfileList depthProfileList = device.getSensor(SensorType.DEPTH).getStreamProfileList();
                if (null != depthProfileList) {
                    depthProfile = LocalUtils.pickVideoProfile(depthProfileList, 640, 30, Format.UNKNOWN);
                    depthProfileList.close();
                }
            }
            if (null == depthProfile) {
                Log.w(TAG, "startPipeline: no depth stream profile!");
                colorProfile.close();
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                showToast("未找到可用的深度流配置");
                return;
            }
            Log.i(TAG, "color profile: " + colorProfile.getWidth() + "×" + colorProfile.getHeight()
                    + "@" + colorProfile.getFps() + "fps " + colorProfile.getFormat()
                    + " | depth profile: " + depthProfile.getWidth() + "×" + depthProfile.getHeight()
                    + "@" + depthProfile.getFps() + "fps " + depthProfile.getFormat());

            // 6. 使能双流配置
            config.enableStream(colorProfile);
            config.enableStream(depthProfile);
            colorProfile.close();
            depthProfile.close();

            // 7. 回调方式开流；失败时明确提示（原先轮询无帧时页面永远黑屏）
            try {
                pipeline.start(config, mFrameSetCallback);
            } catch (Exception e) {
                Log.e(TAG, "start pipeline failed: " + e.getMessage(), e);
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                if (!mErrorToasted) {
                    mErrorToasted = true;
                    showToast("开启同步流失败: " + e.getMessage());
                }
                return;
            }

            // 8. 开流成功：记录 pipeline/config（供 D2C 切换与释放），启动消费线程
            mPipeline = pipeline;
            mConfig = config;
            mFirstFrameLogged = false;
            mErrorToasted = false;
            startConsumeThread();
            startFirstFrameWatchdog();
            Log.i(TAG, "pipeline started with callback");
        } catch (Exception e) {
            Log.e(TAG, "startPipeline error: " + e.getMessage(), e);
            releaseDeviceQuietly();
            if (!mErrorToasted) {
                mErrorToasted = true;
                showToast("初始化失败: " + e.getMessage());
            }
        }
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(SyncAlignViewerActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    /**
     * 首帧看门狗：5 秒内未收到任何帧集则自动切换到 waitForFrameSet 轮询路径
     */
    private void startFirstFrameWatchdog() {
        if (mWatchdogStarted) {
            return;
        }
        mWatchdogStarted = true;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
            if (!mFirstFrameSeen && mPipeline != null && !mUsePolling) {
                switchToPolling();
            }
        }, "sync-watchdog");
        t.start();
    }

    private void switchToPolling() {
        try {
            if (mPipeline == null || mConfig == null) {
                return;
            }
            DebugLog.log(TAG, "no first frameset, switch to polling");
            showToast("正在切换备用取流方式...");
            try {
                mPipeline.stop();
            } catch (Exception ignored) {
            }
            try {
                mPipeline.start(mConfig);
            } catch (Exception e) {
                DebugLog.log(TAG, "poll restart failed: " + e);
                showToast("备用取流方式启动失败: " + e.getMessage());
                return;
            }
            mUsePolling = true;
            mPollThread = new Thread(mPollRunnable, "sync-poll");
            mPollThread.start();
        } catch (Exception e) {
            DebugLog.log(TAG, "switchToPolling error: " + e);
        }
    }

    /**
     * 轮询回退线程：持续从管线拉取帧集（与回调共用 handleFrameSet 处理）
     */
    private final Runnable mPollRunnable = () -> {
        DebugLog.log(TAG, "polling loop started");
        while (mIsStreamRunning) {
            FrameSet fs = null;
            try {
                fs = mPipeline.waitForFrameSet(100);
            } catch (Exception e) {
                continue;
            }
            if (fs == null) {
                continue;
            }
            handleFrameSet(fs);
        }
    };

    /**
     * 处理一帧帧集（回调线程或轮询线程）：只拷贝深度/彩色原始数据并入队，尽快返回
     */
    private void handleFrameSet(FrameSet frameSet) {
        DepthFrame depthFrame = null;
        ColorFrame colorFrame = null;
        try {
            depthFrame = frameSet.getDepthFrame();
            colorFrame = frameSet.getColorFrame();
            if (null == depthFrame && null == colorFrame) {
                return;
            }

            int depthW = 0, depthH = 0, colorW = 0, colorH = 0;
            Format depthFormat = Format.UNKNOWN, colorFormat = Format.UNKNOWN;
            float scale = 1.0f;
            byte[] depthData = null, colorData = null;

            if (null != depthFrame) {
                depthW = depthFrame.getWidth();
                depthH = depthFrame.getHeight();
                depthFormat = depthFrame.getFormat();
                scale = depthFrame.getValueScale();
                depthData = new byte[depthFrame.getDataSize()];
                depthFrame.getData(depthData);
            }
            if (null != colorFrame) {
                colorW = colorFrame.getWidth();
                colorH = colorFrame.getHeight();
                colorFormat = colorFrame.getFormat();
                colorData = new byte[colorFrame.getDataSize()];
                colorFrame.getData(colorData);
            }

            PairSnapshot pair = new PairSnapshot(depthW, depthH, depthFormat, scale, depthData,
                    colorW, colorH, colorFormat, colorData);
            // 队列满时丢弃最旧帧，保留最新帧
            if (!mFrameQueue.offer(pair)) {
                mFrameQueue.poll();
                mFrameQueue.offer(pair);
            }
            mFirstFrameSeen = true;
        } catch (Exception e) {
            Log.w(TAG, "frameset callback error: " + e);
        } finally {
            // 帧与帧集必须在回调内释放，否则管线会卡死
            try {
                if (null != depthFrame) {
                    depthFrame.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (null != colorFrame) {
                    colorFrame.close();
                }
            } catch (Exception ignored) {
            }
            try {
                frameSet.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 帧集回调（运行在 SDK 回调线程）：与轮询回退共用 handleFrameSet 处理
     */
    private final FrameSetCallback mFrameSetCallback = this::handleFrameSet;

    private void initView() {
        mColorView = findViewById(R.id.colorview_id);
        sbTransparency = findViewById(R.id.sb_transparency);
        tvTransparency = findViewById(R.id.tv_transparency);
        mSyncCb = findViewById(R.id.cb_sync);
        mHardwareD2CCb = findViewById(R.id.cb_hardware_align_to_color);
        mSoftwareD2CCb = findViewById(R.id.cb_software_align_to_color);

        mSyncCb.setOnClickListener(this);
        mHardwareD2CCb.setOnClickListener(this);
        mSoftwareD2CCb.setOnClickListener(this);
        sbTransparency.setOnSeekBarChangeListener(this);

        tvTransparency.setText(String.format("%.2f", sbTransparency.getProgress() / 100f));
    }

    private void startConsumeThread() {
        mIsStreamRunning = true;
        if (null == mConsumeThread) {
            mConsumeThread = new Thread(mConsumeRunnable, "sync-consume");
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

    /**
     * 消费线程：解码深度/彩色数据并叠加渲染
     */
    private final Runnable mConsumeRunnable = () -> {
        while (mIsStreamRunning) {
            PairSnapshot pair;
            try {
                pair = mFrameQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (null == pair) {
                continue;
            }
            try {
                renderPair(pair);

                if (!mFirstFrameLogged) {
                    mFirstFrameLogged = true;
                    Log.i(TAG, "first frameset: depth " + pair.depthW + "x" + pair.depthH
                            + " " + pair.depthFormat + ", color " + pair.colorW + "x" + pair.colorH
                            + " " + pair.colorFormat);
                    showToast("已收到首帧画面");
                }
            } catch (Exception e) {
                Log.w(TAG, "sync render error: " + e);
            }
        }
    };

    /**
     * 渲染一帧：双流都有则叠加，缺一则渲染存在的流（避免长时间黑屏）
     */
    private void renderPair(PairSnapshot pair) {
        if (pair.depthData != null && pair.colorData != null) {
            // Convert depth data to RGB888 format
            decodeDepthFrame(pair.depthW, pair.depthH, pair.depthData, pair.depthValueScale);
            // Convert color data to RGB888 format
            decodeColorFrame(pair.colorW, pair.colorH, pair.colorFormat, pair.colorData);

            if (mDepthDstBuffer != null && mColorDstBuffer != null) {
                byte[] depthColorData = ImageUtils.depthAlignToColor(mColorDstBuffer, mDepthDstBuffer,
                        pair.colorW, pair.colorH, pair.depthW, pair.depthH, mAlpha);
                mColorView.update(pair.colorW, pair.colorH, StreamType.COLOR, Format.RGB888, depthColorData, 1.0f);
            }
        } else if (pair.depthData != null) {
            decodeDepthFrame(pair.depthW, pair.depthH, pair.depthData, pair.depthValueScale);
            if (mDepthDstBuffer != null) {
                mDepthDstBuffer.rewind();
                byte[] depthRgb = new byte[pair.depthW * pair.depthH * 3];
                mDepthDstBuffer.get(depthRgb);
                mColorView.update(pair.depthW, pair.depthH, StreamType.COLOR, Format.RGB888, depthRgb, 1.0f);
            }
        } else if (pair.colorData != null) {
            decodeColorFrame(pair.colorW, pair.colorH, pair.colorFormat, pair.colorData);
            if (mColorDstBuffer != null) {
                mColorDstBuffer.rewind();
                byte[] colorRgb = new byte[pair.colorW * pair.colorH * 3];
                mColorDstBuffer.get(colorRgb);
                mColorView.update(pair.colorW, pair.colorH, StreamType.COLOR, Format.RGB888, colorRgb, 1.0f);
            }
        }
    }

    private void decodeColorFrame(int colorW, int colorH, Format format, byte[] colorData) {
        if (null == colorData) {
            return;
        }
        if (null == mColorSrcBuffer || mColorSrcBuffer.capacity() != colorData.length) {
            mColorSrcBuffer = ByteBuffer.allocateDirect(colorData.length);
        }
        mColorSrcBuffer.clear();
        mColorSrcBuffer.put(colorData);
        mColorSrcBuffer.flip();

        int colorDstSize = colorW * colorH * 3;
        if (null == mColorDstBuffer || mColorDstBuffer.capacity() != colorDstSize) {
            mColorDstBuffer = ByteBuffer.allocateDirect(colorDstSize);
        }
        mColorDstBuffer.clear();

        switch (format) {
            case RGB888:
                mColorDstBuffer.put(mColorSrcBuffer);
                mColorDstBuffer.flip();
                break;
            case YUYV:
                YuvUtil.yuyv2Rgb888(mColorSrcBuffer, mColorDstBuffer, mColorDstBuffer.capacity());
                break;
            case UYVY:
                ImageUtils.uyvyToRgb(mColorSrcBuffer, mColorDstBuffer, colorW, colorH);
                break;
            default:
                Log.w(TAG, "decodeColorFrame: unsupported format " + format);
                break;
        }
    }

    private void decodeDepthFrame(int depthW, int depthH, byte[] depthData, float valueScale) {
        if (null == depthData) {
            return;
        }
        // Depth frame data to RGB888
        if (null == mDepthSrcBuffer || mDepthSrcBuffer.capacity() != depthData.length) {
            mDepthSrcBuffer = ByteBuffer.allocateDirect(depthData.length);
        }
        mDepthSrcBuffer.clear();
        mDepthSrcBuffer.put(depthData);
        mDepthSrcBuffer.flip();

        int depthDstSize = depthW * depthH * 3;
        if (null == mDepthDstBuffer || mDepthDstBuffer.capacity() != depthDstSize) {
            mDepthDstBuffer = ByteBuffer.allocateDirect(depthDstSize);
        }
        mDepthDstBuffer.clear();

        ImageUtils.nScalePrecisionToDepthPixel(mDepthSrcBuffer, depthW, depthH, depthData.length, valueScale);
        ImageUtils.depthToRgb(mDepthSrcBuffer, mDepthDstBuffer);
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            // 0. 先停轮询回退线程
            mIsStreamRunning = false;
            if (null != mPollThread) {
                try {
                    mPollThread.join(300);
                } catch (Exception ignored) {
                }
                mPollThread = null;
            }
            // 1. 先停止取流，再停止消费线程
            if (null != mPipeline) {
                try {
                    mPipeline.stop();
                } catch (Exception e) {
                    Log.w(TAG, "pipeline stop failed: " + e.getMessage());
                }
            }
            stopConsumeThread();

            // 2. 释放 Pipeline 与 Config
            if (null != mPipeline) {
                try {
                    mPipeline.close();
                } catch (Exception ignored) {
                }
                mPipeline = null;
            }
            if (null != mConfig) {
                try {
                    mConfig.close();
                } catch (Exception ignored) {
                }
                mConfig = null;
            }

            // 3. 释放 Device 资源
            releaseDeviceQuietly();

            // 4. 释放 SDK Context
            if (null != mOBContext) {
                mOBContext.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.cb_sync) {
            setSync(mSyncCb.isChecked());
        } else if (id == R.id.cb_hardware_align_to_color) {
            mSoftwareD2CCb.setChecked(false);
            setAlignToColor(mHardwareD2CCb.isChecked(), true);
        } else if (id == R.id.cb_software_align_to_color) {
            mHardwareD2CCb.setChecked(false);
            setAlignToColor(mSoftwareD2CCb.isChecked(), false);
        }
    }

    // set frame sync
    private void setSync(boolean isChecked) {
        try {
            if (null == mPipeline) {
                return;
            }
            if (isChecked) {
                mPipeline.enableFrameSync();
            } else {
                mPipeline.disableFrameSync();
            }
        } catch (Exception e) {
            Log.w(TAG, "setSync: " + e.getMessage());
        }
    }

    // Config D2C Align
    private void setAlignToColor(boolean isChecked, boolean isHardware) {
        try {
            if (mPipeline == null || mConfig == null) {
                return;
            }
            try {
                if (isChecked) {
                    mConfig.setAlignMode((isHardware ? AlignMode.ALIGN_D2C_HW_ENABLE : AlignMode.ALIGN_D2C_SW_ENABLE));
                } else {
                    mConfig.setAlignMode(AlignMode.ALIGN_D2C_DISABLE);
                }
                mPipeline.switchConfig(mConfig);
            } catch (Exception e) {
                Log.w(TAG, "setAlignToColor: " + e.getMessage());
                showToast("D2C 切换失败: " + e.getMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar.getId() == R.id.sb_transparency) {
            float progressF = progress / 100f;
            tvTransparency.setText(String.format("%.2f", progressF));
            mAlpha = progressF;
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {

    }
}
