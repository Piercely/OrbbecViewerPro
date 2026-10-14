package com.orbbec.orbbecsdkexamples.utils;

import android.os.SystemClock;

import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.DepthFrame;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.Frame;
import com.orbbec.obsensor.FrameCallback;
import com.orbbec.obsensor.FrameType;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoFrame;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 单流取流组件（彩色/深度/红外共用）。
 * <p>
 * 对齐官方 App：优先使用 Sensor 级回调取流（sensor.start(profile, callback)）；
 * 若 {@link #FIRST_FRAME_TIMEOUT_MS} 内未收到首帧，自动回退到 Pipeline + waitForFrameSet
 * 轮询路径（原 examples 实现方式，在部分设备上验证可用），保证任何情况下尽力出画面。
 * <p>
 * 回调线程只拷贝数据入队（保新弃旧），消费线程负责渲染回调与拍照发布。
 */
public class SingleStreamSource {
    private static final String TAG = "SingleStreamSource";

    /** Sensor 回调路径等待首帧的超时时间，超时后自动切换 Pipeline 轮询路径 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 5000;

    public interface Listener {
        /** 收到一帧（消费线程回调，可直接渲染） */
        void onFrame(FrameSnapshot snapshot);

        /** 状态提示（如回退通知、失败原因），实现方需自行切主线程展示 */
        void onInfo(String msg);
    }

    private final Device mDevice;
    private final StreamType mStreamType;
    private final int mPreferWidth;
    private final int mPreferFps;
    private final Format mPreferFormat;
    private final Listener mListener;

    private final Object mLock = new Object();
    private final LinkedBlockingQueue<FrameSnapshot> mQueue = new LinkedBlockingQueue<>(4);

    // Sensor 回调路径资源
    private Sensor mSensor;
    private VideoStreamProfile mSensorProfile;
    private volatile boolean mSensorActive;

    // Pipeline 轮询回退路径资源
    private Pipeline mPipeline;
    private Config mConfig;
    private volatile boolean mPipelineActive;

    private volatile boolean mRunning;
    private volatile boolean mFirstFrameSeen;
    private volatile boolean mFallbackTried;
    private long mStartElapsed;

    private Thread mThread;

    public SingleStreamSource(Device device, StreamType streamType,
                              int preferWidth, int preferFps, Format preferFormat, Listener listener) {
        mDevice = device;
        mStreamType = streamType;
        mPreferWidth = preferWidth;
        mPreferFps = preferFps;
        mPreferFormat = preferFormat;
        mListener = listener;
    }

    /**
     * 开流：Sensor 回调优先，失败或首帧超时自动回退 Pipeline 轮询。
     */
    public void start() {
        if (mDevice == null) {
            notifyInfo("设备未就绪");
            return;
        }
        mStartElapsed = SystemClock.elapsedRealtime();
        boolean sensorOk = startSensorPath();
        if (!sensorOk && !startPipelinePath()) {
            notifyInfo("取流启动失败");
            return;
        }
        mRunning = true;
        mThread = new Thread(mConsumeRunnable, "stream-" + mStreamType.name().toLowerCase());
        mThread.setUncaughtExceptionHandler((t, e) -> {
            DebugLog.log(TAG, "consume thread crashed: " + e);
            notifyInfo("渲染线程异常: " + e);
        });
        mThread.start();
        DebugLog.log(TAG, "started for " + mStreamType + ", sensorPath=" + mSensorActive);
    }

    /**
     * 停止取流并释放全部资源（Sensor 由 Device 统一 close，此处只 stop）。
     */
    public void stop() {
        mRunning = false;
        Thread t = mThread;
        if (t != null) {
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
            }
            mThread = null;
        }
        releaseSensor();
        releasePipeline();
        mQueue.clear();
    }

    public boolean isUsingPipelinePath() {
        return mPipelineActive;
    }

    // ------------------------------------------------------------------
    // 路径一：Sensor 级回调（对齐官方 App OrbbecSource）
    // ------------------------------------------------------------------

    private boolean startSensorPath() {
        synchronized (mLock) {
            try {
                Sensor sensor = mDevice.getSensor(sensorTypeOf(mStreamType));
                if (sensor == null) {
                    notifyInfo("设备不支持 " + streamName(mStreamType));
                    return false;
                }
                VideoStreamProfile profile = null;
                StreamProfileList list = sensor.getStreamProfileList();
                if (list != null) {
                    profile = LocalUtils.pickVideoProfile(list, mPreferWidth, mPreferFps, mPreferFormat);
                    list.close();
                }
                if (profile == null) {
                    notifyInfo("未找到可用的" + streamName(mStreamType) + "流配置");
                    return false;
                }
                DebugLog.log(TAG, mStreamType + " sensor profile: " + profile.getWidth() + "x"
                        + profile.getHeight() + "@" + profile.getFps() + " " + profile.getFormat());
                try {
                    sensor.start(profile, mFrameCallback);
                } catch (Exception e) {
                    DebugLog.log(TAG, "sensor.start failed: " + e);
                    profile.close();
                    notifyInfo("开启" + streamName(mStreamType) + "流失败: " + e.getMessage());
                    return false;
                }
                mSensor = sensor;
                mSensorProfile = profile;
                mSensorActive = true;
                return true;
            } catch (Exception e) {
                DebugLog.log(TAG, "startSensorPath error: " + e);
                releaseSensor();
                return false;
            }
        }
    }

    private final FrameCallback mFrameCallback = frame -> {
        VideoFrame vf = null;
        try {
            // as() 包装与 frame 共享同一 native 句柄，finally 中只 close 一次
            vf = frame.as(mStreamType == StreamType.DEPTH ? FrameType.DEPTH : FrameType.VIDEO);
            byte[] data = new byte[vf.getDataSize()];
            vf.getData(data);
            float scale = 1.0f;
            if (vf instanceof DepthFrame) {
                scale = ((DepthFrame) vf).getValueScale();
            }
            FrameSnapshot snapshot = new FrameSnapshot(vf.getWidth(), vf.getHeight(),
                    mStreamType, vf.getFormat(), scale, data);
            // 队列满时丢弃最旧帧，保留最新帧
            if (!mQueue.offer(snapshot)) {
                mQueue.poll();
                mQueue.offer(snapshot);
            }
        } catch (Exception e) {
            DebugLog.log(TAG, mStreamType + " frame callback error: " + e);
        } finally {
            try {
                if (vf != null) {
                    vf.close();
                } else {
                    frame.close();
                }
            } catch (Exception ignored) {
            }
        }
    };

    // ------------------------------------------------------------------
    // 路径二：Pipeline + waitForFrameSet 轮询（原 examples 实现，回退用）
    // ------------------------------------------------------------------

    private boolean startPipelinePath() {
        synchronized (mLock) {
            if (mPipelineActive) {
                return true;
            }
            try {
                releaseSensor();
                Pipeline pipeline = new Pipeline(mDevice);
                Config config = new Config();
                VideoStreamProfile profile = null;
                StreamProfileList list = pipeline.getStreamProfileList(sensorTypeOf(mStreamType));
                if (list != null) {
                    profile = LocalUtils.pickVideoProfile(list, mPreferWidth, mPreferFps, mPreferFormat);
                    list.close();
                }
                if (profile == null) {
                    DebugLog.log(TAG, "pipeline fallback: no profile for " + mStreamType);
                    config.close();
                    pipeline.close();
                    return false;
                }
                DebugLog.log(TAG, "pipeline fallback profile: " + profile.getWidth() + "x"
                        + profile.getHeight() + "@" + profile.getFps() + " " + profile.getFormat());
                config.enableStream(profile);
                profile.close();
                pipeline.start(config);
                mPipeline = pipeline;
                mConfig = config;
                mPipelineActive = true;
                DebugLog.log(TAG, "pipeline fallback active for " + mStreamType);
                return true;
            } catch (Exception e) {
                DebugLog.log(TAG, "startPipelinePath error: " + e);
                releasePipeline();
                return false;
            }
        }
    }

    /**
     * 从 FrameSet 中提取一帧（消费线程内执行）
     */
    private void pumpPipelineOnce() {
        FrameSetHolder holder = new FrameSetHolder();
        try {
            holder.frameSet = mPipeline.waitForFrameSet(100);
        } catch (Exception e) {
            return;
        }
        if (holder.frameSet == null) {
            return;
        }
        VideoFrame vf = null;
        try {
            Frame frame;
            if (mStreamType == StreamType.DEPTH) {
                frame = holder.frameSet.getDepthFrame();
            } else if (mStreamType == StreamType.IR) {
                frame = holder.frameSet.getIrFrame();
            } else {
                frame = holder.frameSet.getColorFrame();
            }
            if (frame == null) {
                return;
            }
            vf = frame.as(FrameType.VIDEO);
            byte[] data = new byte[vf.getDataSize()];
            vf.getData(data);
            float scale = 1.0f;
            if (frame instanceof DepthFrame) {
                scale = ((DepthFrame) frame).getValueScale();
            }
            FrameSnapshot snapshot = new FrameSnapshot(vf.getWidth(), vf.getHeight(),
                    mStreamType, vf.getFormat(), scale, data);
            if (!mQueue.offer(snapshot)) {
                mQueue.poll();
                mQueue.offer(snapshot);
            }
        } catch (Exception e) {
            DebugLog.log(TAG, "pumpPipelineOnce error: " + e);
        } finally {
            try {
                if (vf != null) {
                    vf.close();
                }
            } catch (Exception ignored) {
            }
            try {
                holder.frameSet.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class FrameSetHolder {
        com.orbbec.obsensor.FrameSet frameSet;
    }

    // ------------------------------------------------------------------
    // 消费线程
    // ------------------------------------------------------------------

    private final Runnable mConsumeRunnable = () -> {
        while (mRunning) {
            if (mPipelineActive) {
                // 回退路径：在同一消费线程内轮询取帧
                pumpPipelineOnce();
                FrameSnapshot s;
                try {
                    s = mQueue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (s != null) {
                    mFirstFrameSeen = true;
                    mListener.onFrame(s);
                }
                continue;
            }
            FrameSnapshot s;
            try {
                s = mQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (!mFirstFrameSeen && mSensorActive
                    && SystemClock.elapsedRealtime() - mStartElapsed > FIRST_FRAME_TIMEOUT_MS) {
                // 首帧超时：自动回退 Pipeline 轮询路径
                if (!mFallbackTried) {
                    mFallbackTried = true;
                    DebugLog.log(TAG, mStreamType + " no first frame in "
                            + FIRST_FRAME_TIMEOUT_MS + "ms, switch to pipeline fallback");
                    notifyInfo("正在切换备用取流方式...");
                    if (!startPipelinePath()) {
                        notifyInfo(streamName(mStreamType) + "取流失败");
                        mRunning = false;
                        break;
                    }
                }
                continue;
            }
            if (s == null) {
                continue;
            }
            mFirstFrameSeen = true;
            mListener.onFrame(s);
        }
    };

    // ------------------------------------------------------------------
    // 资源释放
    // ------------------------------------------------------------------

    private void releaseSensor() {
        synchronized (mLock) {
            mSensorActive = false;
            if (mSensor != null) {
                try {
                    mSensor.stop();
                } catch (Exception ignored) {
                }
                mSensor = null;
            }
            if (mSensorProfile != null) {
                try {
                    mSensorProfile.close();
                } catch (Exception ignored) {
                }
                mSensorProfile = null;
            }
        }
    }

    private void releasePipeline() {
        synchronized (mLock) {
            mPipelineActive = false;
            if (mPipeline != null) {
                try {
                    mPipeline.stop();
                } catch (Exception ignored) {
                }
                try {
                    mPipeline.close();
                } catch (Exception ignored) {
                }
                mPipeline = null;
            }
            if (mConfig != null) {
                try {
                    mConfig.close();
                } catch (Exception ignored) {
                }
                mConfig = null;
            }
        }
    }

    private void notifyInfo(String msg) {
        DebugLog.log(TAG, msg);
        if (mListener != null) {
            mListener.onInfo(msg);
        }
    }

    private static SensorType sensorTypeOf(StreamType type) {
        switch (type) {
            case DEPTH:
                return SensorType.DEPTH;
            case IR:
                return SensorType.IR;
            default:
                return SensorType.COLOR;
        }
    }

    private static String streamName(StreamType type) {
        switch (type) {
            case DEPTH:
                return "深度";
            case IR:
                return "红外";
            default:
                return "彩色";
        }
    }
}
