package com.orbbec.orbbecsdkexamples.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.DepthFrame;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.DeviceProperty;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.PermissionType;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.Sensor;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfile;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;
import com.orbbec.orbbecsdkexamples.utils.CaptureController;
import com.orbbec.orbbecsdkexamples.utils.DebugLog;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

/**
 * Depth Viewer
 */
public class DepthViewerActivity extends AppCompatActivity {
    private static final String TAG = "DepthViewerActivity";

    private OBContext mOBContext;
    private Pipeline mPipeline;

    private Thread mStreamThread;
    private volatile boolean mIsStreamRunning;

    private OBGLView mDepthView;
    private Device mDevice;
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
        // 1.Initialize the SDK Context and listen device changes
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                DebugLog.log(TAG, "onDeviceAttach");
                try {
                    if (null == mPipeline) {
                        // 2.Create Device and initialize Pipeline through Device
                        mDevice = deviceList.getDevice(0);

                        Sensor depthSensor = mDevice.getSensor(SensorType.DEPTH);
                        if (null == depthSensor) {
                            showToast(getString(R.string.device_not_support_depth));
                            return;
                        }

                        mPipeline = new Pipeline(mDevice);

                        // 3.Create Pipeline configuration
                        Config config = new Config();

                        // 4.Obtain the depth stream configuration and configure it to Config.
                        // Here, matching is based on the width and frame rate. The matching meets
                        // the configuration of a width of 640 and a frame rate of 30fps.
                        StreamProfileList depthProfileList = mPipeline.getStreamProfileList(SensorType.DEPTH);
                        StreamProfile streamProfile = getVideoStreamProfile(depthProfileList, 640, 0, Format.UNKNOWN, 30);
                        if (null == streamProfile) {
                            streamProfile = getVideoStreamProfile(depthProfileList, 0, 0, Format.UNKNOWN, 30);
                        }
                        if (null != depthProfileList) {
                            depthProfileList.close();
                        }

                        // 5.Enable deep streaming configuration
                        if (null != streamProfile) {
                            printStreamProfile(streamProfile.as(StreamType.VIDEO));
                            config.enableStream(streamProfile);
                            streamProfile.close();
                        } else {
                            Log.w(TAG, "No target stream profile!");
                            mPipeline.close();
                            mPipeline = null;

                            config.close();
                            return;
                        }

                        // 6.Start sensor stream
                        mPipeline.start(config);
                        DebugLog.log(TAG, "pipeline started");

                        // 7.release config
                        config.close();

                        // 8.Create a thread to obtain Pipeline data
                        start();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 9.Release device list resources
                    deviceList.close();
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

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(DepthViewerActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private void printStreamProfile(VideoStreamProfile vsp) {
        Log.i(TAG, "printStreamProfile: "
                + vsp.getWidth() + "×" + vsp.getHeight()
                + "@" + vsp.getFps() + "fps " + vsp.getFormat());
    }

    private VideoStreamProfile getVideoStreamProfile(StreamProfileList profileList,
                                                     int width, int height, Format format, int fps) {
        VideoStreamProfile vsp = null;
        try {
            vsp = profileList.getVideoStreamProfile(width, height, format, fps);
        } catch (Exception e) {
            Log.w(TAG, "getVideoStreamProfile: " + e.getMessage());
        }
        return vsp;
    }

    private void start() {
        mIsStreamRunning = true;
        mFirstFrameLogged = false;
        mErrorToasted = false;
        if (null == mStreamThread) {
            mStreamThread = new Thread(mStreamRunnable);
            mStreamThread.setUncaughtExceptionHandler((t, e) -> {
                DebugLog.log(TAG, "stream thread crashed: " + e);
                showToast("流线程崩溃: " + e);
            });
            mStreamThread.start();
        }
    }

    private void stop() {
        mIsStreamRunning = false;
        if (null != mStreamThread) {
            try {
                mStreamThread.join(300);
            } catch (InterruptedException e) {
            }
            mStreamThread = null;
        }
    }

    private Runnable mStreamRunnable = () -> {
        while (mIsStreamRunning) {
            FrameSet frameSet = null;
            DepthFrame frame = null;
            try {
                // Obtain the data set in blocking mode. If it cannot be obtained after waiting for 100ms, it will time out.
                frameSet = mPipeline.waitForFrameSet(100);

                if (null == frameSet) {
                    continue;
                }

                // Get depth flow data
                frame = frameSet.getDepthFrame();
                if (frame != null) {
                    // Get data and render
                    byte[] frameData = new byte[frame.getDataSize()];
                    frame.getData(frameData);
                    float scale = frame.getValueScale();
                    mDepthView.update(frame.getWidth(), frame.getHeight(), StreamType.DEPTH, frame.getFormat(), frameData, scale);

                    if (!mFirstFrameLogged) {
                        mFirstFrameLogged = true;
                        DebugLog.log(TAG, "first depth frame: " + frame.getWidth() + "x"
                                + frame.getHeight() + " " + frame.getFormat());
                        showToast("已收到首帧画面");
                    }

                    // Publish the same frame for photo capture (isolated so a
                    // capture failure can never break the stream loop)
                    try {
                        if (mCaptureController != null) {
                            mCaptureController.publish(new FrameSnapshot(frame.getWidth(),
                                    frame.getHeight(), StreamType.DEPTH, frame.getFormat(),
                                    scale, frameData));
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "capture publish failed: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                DebugLog.log(TAG, "stream loop error: " + e);
                if (!mErrorToasted) {
                    mErrorToasted = true;
                    showToast("流循环异常: " + e);
                }
            } finally {
                // Always release frames, otherwise the pipeline stalls after a few frames
                try {
                    if (frame != null) {
                        frame.close();
                    }
                } catch (Exception ignored) {
                }
                try {
                    if (frameSet != null) {
                        frameSet.close();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCaptureController != null) {
            mCaptureController.release();
        }
        try {
            //Stop getting Pipeline data
            stop();

            // Stop the Pipeline and release
            if (null != mPipeline) {
                mPipeline.stop();
                mPipeline.close();
            }

            // Release Device
            if (mDevice != null) {
                mDevice.close();
            }

            // Release SDK Context
            if (null != mOBContext) {
                mOBContext.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}