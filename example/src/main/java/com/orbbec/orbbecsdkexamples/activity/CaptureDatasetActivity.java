package com.orbbec.orbbecsdkexamples.activity;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.orbbec.obsensor.AlignMode;
import com.orbbec.obsensor.CameraParam;
import com.orbbec.obsensor.ColorFrame;
import com.orbbec.obsensor.Config;
import com.orbbec.obsensor.DepthFrame;
import com.orbbec.obsensor.Device;
import com.orbbec.obsensor.DeviceChangedCallback;
import com.orbbec.obsensor.DeviceList;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.FormatConvertFilter;
import com.orbbec.obsensor.FormatConvertType;
import com.orbbec.obsensor.Frame;
import com.orbbec.obsensor.FrameSet;
import com.orbbec.obsensor.FrameSetCallback;
import com.orbbec.obsensor.FrameType;
import com.orbbec.obsensor.OBContext;
import com.orbbec.obsensor.Pipeline;
import com.orbbec.obsensor.SensorType;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoStreamProfile;
import com.orbbec.obsensor.datatype.CameraDistortion;
import com.orbbec.obsensor.datatype.CameraIntrinsic;
import com.orbbec.obsensor.datatype.D2CTransform;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.utils.DepthColorizer;
import com.orbbec.orbbecsdkexamples.utils.DepthPngEncoder;
import com.orbbec.orbbecsdkexamples.utils.ImageUtils;
import com.orbbec.orbbecsdkexamples.utils.LocalUtils;
import com.orbbec.orbbecsdkexamples.view.OBGLView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 数据集采集 Activity：对齐的 RGB + 16-bit 深度 PNG 对，供模型训练使用。
 * <p>
 * - 使用 D2C（深度对齐到彩色）保证 RGB 与深度像素一一对应
 * - 保存路径：Pictures/OrbbecViewer/，文件名 rgb_<时间戳>.png / depth_<时间戳>.png / meta_<时间戳>.json
 * - 深度为 16-bit 灰度 PNG，单位 mm，训练侧可直接读取为 uint16
 * - 功能：单拍 / 长按连拍 / 深度伪彩叠加预览 / 内参+位姿元数据 JSON
 */
public class CaptureDatasetActivity extends AppCompatActivity {

    private static final String TAG = "CaptureDatasetActivity";
    private static final String SAVE_DIR = "Pictures/OrbbecViewer";
    /** 连拍间隔 ms */
    private static final long BURST_INTERVAL_MS = 200;

    private OBContext mOBContext;
    private Device mDevice;
    private Pipeline mPipeline;
    private Config mConfig;
    private FormatConvertFilter mFormatConvertFilter;

    private OBGLView mColorView;
    private TextView mStatusText;
    private TextView mCountText;
    private Button mCaptureBtn;
    private Button mOverlayBtn;

    private volatile boolean mIsRunning;
    private Thread mRenderThread;

    /** 是否在预览上叠加深度伪彩 */
    private volatile boolean mShowDepthOverlay = false;

    // 连拍控制
    private volatile boolean mBurstRunning = false;
    private final Runnable mBurstTick = this::captureOnce;

    // 最新一帧对齐数据（回调线程写入，渲染/保存线程读取）
    private volatile FramePair mLatestPair;

    // 相机内参/外参（D2C 对齐后）
    private volatile CameraParam mCameraParam;

    private final ExecutorService mSaveExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private int mSavedCount = 0;

    /** 一帧对齐的 RGB-D 数据快照 */
    private static final class FramePair {
        final int colorW, colorH;
        final byte[] colorRgb;      // RGB888
        final int depthW, depthH;
        final byte[] depthData;     // Y16 uint16 LE
        final float depthScale;
        final long timestamp;

        FramePair(int colorW, int colorH, byte[] colorRgb,
                  int depthW, int depthH, byte[] depthData, float depthScale, long timestamp) {
            this.colorW = colorW;
            this.colorH = colorH;
            this.colorRgb = colorRgb;
            this.depthW = depthW;
            this.depthH = depthH;
            this.depthData = depthData;
            this.depthScale = depthScale;
            this.timestamp = timestamp;
        }

        FramePair copy() {
            return new FramePair(colorW, colorH, colorRgb.clone(),
                    depthW, depthH, depthData.clone(), depthScale, timestamp);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.feat_capture);
        setContentView(R.layout.activity_capture_dataset);

        mColorView = findViewById(R.id.capture_color_view);
        mStatusText = findViewById(R.id.capture_status);
        mCountText = findViewById(R.id.capture_count);
        mCaptureBtn = findViewById(R.id.btn_capture);
        mOverlayBtn = findViewById(R.id.btn_overlay);

        setupCaptureButton();
        setupOverlayButton();

        initOBContext();
    }

    private void setupCaptureButton() {
        mCaptureBtn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mCaptureBtn.setPressed(true);
                    startBurst();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mCaptureBtn.setPressed(false);
                    stopBurst();
                    return true;
            }
            return false;
        });
    }

    private void startBurst() {
        if (mBurstRunning) return;
        mBurstRunning = true;
        captureOnce(); // 立即拍一张
        scheduleBurst();
    }

    private void scheduleBurst() {
        if (mBurstRunning) {
            mMainHandler.postDelayed(() -> {
                if (mBurstRunning) {
                    captureOnce();
                    scheduleBurst();
                }
            }, BURST_INTERVAL_MS);
        }
    }

    private void stopBurst() {
        mBurstRunning = false;
    }

    private void setupOverlayButton() {
        mOverlayBtn.setOnClickListener(v -> {
            mShowDepthOverlay = !mShowDepthOverlay;
            mOverlayBtn.setText(mShowDepthOverlay ? R.string.capture_overlay_on : R.string.capture_overlay_off);
            showToast(mShowDepthOverlay ? "已开启深度伪彩叠加" : "已关闭深度伪彩叠加");
        });
    }

    private void initOBContext() {
        mOBContext = new OBContext(getApplicationContext(), new DeviceChangedCallback() {
            @Override
            public void onDeviceAttach(DeviceList deviceList) {
                try {
                    if (mPipeline == null) {
                        startPipeline(deviceList);
                    }
                } finally {
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
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void startPipeline(DeviceList deviceList) {
        try {
            Device device = deviceList.getDevice(0);
            mDevice = device;

            if (device.getSensor(SensorType.DEPTH) == null) {
                showToast(getString(R.string.device_not_support_depth));
                releaseDeviceQuietly();
                return;
            }
            if (device.getSensor(SensorType.COLOR) == null) {
                showToast(getString(R.string.device_not_support_color));
                releaseDeviceQuietly();
                return;
            }

            Pipeline pipeline = new Pipeline(device);
            Config config = new Config();
            config.setDepthScaleRequire(true);

            // 彩色流：640@30 RGB888
            VideoStreamProfile colorProfile = null;
            StreamProfileList colorList = device.getSensor(SensorType.COLOR).getStreamProfileList();
            if (colorList != null) {
                colorProfile = LocalUtils.pickVideoProfile(colorList, 640, 30, Format.RGB888);
                colorList.close();
            }
            if (colorProfile == null) {
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                showToast("未找到彩色流配置");
                return;
            }

            // 深度流：优先 D2C 兼容配置（HW→SW→普通回退）
            VideoStreamProfile depthProfile = null;
            AlignMode alignMode = pickAlignMode(pipeline, colorProfile);
            config.setAlignMode(alignMode);
            if (alignMode != AlignMode.ALIGN_D2C_DISABLE) {
                StreamProfileList d2cList = pipeline.getD2CDepthProfileList(colorProfile, alignMode);
                if (d2cList != null) {
                    depthProfile = LocalUtils.pickVideoProfile(d2cList, 640, 30, Format.UNKNOWN);
                    d2cList.close();
                }
            }
            if (depthProfile == null) {
                StreamProfileList depthList = device.getSensor(SensorType.DEPTH).getStreamProfileList();
                if (depthList != null) {
                    depthProfile = LocalUtils.pickVideoProfile(depthList, 640, 30, Format.UNKNOWN);
                    depthList.close();
                }
            }
            if (depthProfile == null) {
                colorProfile.close();
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                showToast("未找到深度流配置");
                return;
            }

            config.enableStream(colorProfile);
            config.enableStream(depthProfile);
            colorProfile.close();
            depthProfile.close();

            mFormatConvertFilter = new FormatConvertFilter();

            try {
                pipeline.start(config, mFrameSetCallback);
            } catch (Exception e) {
                Log.e(TAG, "start pipeline failed: " + e.getMessage(), e);
                config.close();
                pipeline.close();
                releaseDeviceQuietly();
                showToast("开启流失败: " + e.getMessage());
                return;
            }

            // 获取 D2C 对齐后的相机内参/外参
            try {
                mCameraParam = pipeline.getCameraParam();
            } catch (Exception e) {
                Log.w(TAG, "getCameraParam failed: " + e.getMessage());
            }

            mPipeline = pipeline;
            mConfig = config;
            mIsRunning = true;
            startRenderThread();
            setStatus("已连接：点按拍照，长按连拍，伪彩可叠加");
            Log.i(TAG, "pipeline started, align=" + alignMode);
        } catch (Exception e) {
            Log.e(TAG, "startPipeline error: " + e.getMessage(), e);
            releaseDeviceQuietly();
            showToast("初始化失败: " + e.getMessage());
        }
    }

    /** 依次尝试 HW D2C → SW D2C → 禁用 */
    private AlignMode pickAlignMode(Pipeline pipeline, VideoStreamProfile colorProfile) {
        AlignMode[] modes = {AlignMode.ALIGN_D2C_HW_ENABLE, AlignMode.ALIGN_D2C_SW_ENABLE};
        for (AlignMode mode : modes) {
            try {
                StreamProfileList list = pipeline.getD2CDepthProfileList(colorProfile, mode);
                if (list != null && list.getStreamProfileCount() > 0) {
                    list.close();
                    return mode;
                }
                if (list != null) {
                    list.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "D2C mode " + mode + " not supported: " + e.getMessage());
            }
        }
        return AlignMode.ALIGN_D2C_DISABLE;
    }

    private final FrameSetCallback mFrameSetCallback = this::handleFrameSet;

    private void handleFrameSet(FrameSet frameSet) {
        try {
            ColorFrame colorFrame = frameSet.getColorFrame();
            DepthFrame depthFrame = frameSet.getDepthFrame();
            if (colorFrame == null || depthFrame == null) {
                return;
            }

            // 彩色转 RGB888
            byte[] colorRgb = toRgb888(colorFrame);
            if (colorRgb == null) {
                colorFrame.close();
                depthFrame.close();
                return;
            }

            int depthW = depthFrame.getWidth();
            int depthH = depthFrame.getHeight();
            byte[] depthData = new byte[depthFrame.getDataSize()];
            depthFrame.getData(depthData);
            float scale = depthFrame.getValueScale();
            long ts = colorFrame.getTimeStamp();

            FramePair pair = new FramePair(
                    colorFrame.getWidth(), colorFrame.getHeight(), colorRgb,
                    depthW, depthH, depthData, scale, ts);
            mLatestPair = pair;

            colorFrame.close();
            depthFrame.close();
        } catch (Exception e) {
            Log.w(TAG, "handleFrameSet error: " + e.getMessage());
        } finally {
            try {
                frameSet.close();
            } catch (Exception ignored) {
            }
        }
    }

    private byte[] toRgb888(ColorFrame colorFrame) {
        Format fmt = colorFrame.getFormat();
        if (fmt == Format.RGB888) {
            byte[] data = new byte[colorFrame.getDataSize()];
            colorFrame.getData(data);
            return data;
        }
        Frame rgbFrame = null;
        try {
            switch (fmt) {
                case MJPG:
                    mFormatConvertFilter.setFormatType(FormatConvertType.FORMAT_MJPEG_TO_RGB888);
                    rgbFrame = mFormatConvertFilter.process(colorFrame);
                    break;
                case YUYV:
                    mFormatConvertFilter.setFormatType(FormatConvertType.FORMAT_YUYV_TO_RGB888);
                    rgbFrame = mFormatConvertFilter.process(colorFrame);
                    break;
                default:
                    Log.w(TAG, "unsupported color format: " + fmt);
                    return null;
            }
            if (rgbFrame != null) {
                Frame video = rgbFrame.as(FrameType.VIDEO);
                byte[] data = new byte[video.getDataSize()];
                video.getData(data);
                return data;
            }
        } catch (Exception e) {
            Log.w(TAG, "color convert error: " + e.getMessage());
        } finally {
            if (rgbFrame != null) {
                try {
                    rgbFrame.close();
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private void startRenderThread() {
        mRenderThread = new Thread(() -> {
            while (mIsRunning) {
                try {
                    FramePair pair = mLatestPair;
                    if (pair != null) {
                        byte[] display = pair.colorRgb;
                        if (mShowDepthOverlay) {
                            // 拷贝一份避免修改原始数据（保存需要原图）
                            display = pair.colorRgb.clone();
                            DepthColorizer.blendDepth(display, pair.depthData, pair.colorW, pair.colorH,
                                    pair.depthScale, 0.4f);
                        }
                        mColorView.update(pair.colorW, pair.colorH, StreamType.COLOR,
                                Format.RGB888, display, 1.0f);
                    }
                    Thread.sleep(66); // ~15fps 预览
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "render error: " + e.getMessage());
                }
            }
        }, "capture-render");
        mRenderThread.start();
    }

    /** 单次采集（连拍会反复调用） */
    private void captureOnce() {
        FramePair pair = mLatestPair;
        if (pair == null) {
            if (!mBurstRunning) showToast("等待画面数据...");
            return;
        }
        final FramePair snapshot = pair.copy();
        final CameraParam camParam = mCameraParam;

        mSaveExecutor.execute(() -> {
            try {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new Date());
                String rgbName = "rgb_" + ts + ".png";
                String depthName = "depth_" + ts + ".png";
                String metaName = "meta_" + ts + ".json";

                // RGB PNG
                Bitmap bitmap = ImageUtils.createBitmap(snapshot.colorRgb, snapshot.colorW, snapshot.colorH);
                if (bitmap != null) {
                    saveViaMediaStore(rgbName, "image/png", os ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, os));
                    bitmap.recycle();
                }

                // Depth 16-bit PNG (mm)
                byte[] depthPng = DepthPngEncoder.encode(
                        snapshot.depthData, snapshot.depthW, snapshot.depthH, snapshot.depthScale);
                saveViaMediaStore(depthName, "image/png", os -> os.write(depthPng));

                // 内参/位姿 JSON
                String meta = buildMetaJson(ts, rgbName, depthName, snapshot, camParam);
                saveViaMediaStore(metaName, "application/json",
                        os -> os.write(meta.getBytes(StandardCharsets.UTF_8)));

                mSavedCount++;
                mMainHandler.post(() -> {
                    mCountText.setText(getString(R.string.capture_count_fmt, mSavedCount));
                    if (!mBurstRunning) {
                        showToast("已保存: " + rgbName);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "save failed: " + e.getMessage(), e);
                mMainHandler.post(() -> showToast("保存失败: " + e.getMessage()));
            }
        });
    }

    /** 构建相机内参/外参 JSON 元数据 */
    private String buildMetaJson(String ts, String rgbName, String depthName,
                                 FramePair pair, CameraParam cam) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"timestamp\": \"").append(ts).append("\",\n");
        sb.append("  \"rgb_file\": \"").append(rgbName).append("\",\n");
        sb.append("  \"depth_file\": \"").append(depthName).append("\",\n");
        sb.append("  \"depth_unit\": \"mm\",\n");
        sb.append("  \"depth_scale\": ").append(pair.depthScale).append(",\n");
        sb.append("  \"width\": ").append(pair.colorW).append(",\n");
        sb.append("  \"height\": ").append(pair.colorH).append(",\n");
        if (cam != null) {
            CameraIntrinsic ci = cam.getColorIntrinsic();
            if (ci != null) {
                sb.append("  \"color_intrinsic\": {\n");
                sb.append("    \"fx\": ").append(ci.getFx()).append(",\n");
                sb.append("    \"fy\": ").append(ci.getFy()).append(",\n");
                sb.append("    \"cx\": ").append(ci.getCx()).append(",\n");
                sb.append("    \"cy\": ").append(ci.getCy()).append(",\n");
                sb.append("    \"width\": ").append(ci.getWidth()).append(",\n");
                sb.append("    \"height\": ").append(ci.getHeight()).append("\n");
                sb.append("  },\n");
            }
            CameraIntrinsic di = cam.getDepthIntrinsic();
            if (di != null) {
                sb.append("  \"depth_intrinsic\": {\n");
                sb.append("    \"fx\": ").append(di.getFx()).append(",\n");
                sb.append("    \"fy\": ").append(di.getFy()).append(",\n");
                sb.append("    \"cx\": ").append(di.getCx()).append(",\n");
                sb.append("    \"cy\": ").append(di.getCy()).append(",\n");
                sb.append("    \"width\": ").append(di.getWidth()).append(",\n");
                sb.append("    \"height\": ").append(di.getHeight()).append("\n");
                sb.append("  },\n");
            }
            CameraDistortion cd = cam.getColorDistortion();
            if (cd != null) {
                sb.append("  \"color_distortion\": [")
                        .append(cd.getK1()).append(", ").append(cd.getK2()).append(", ")
                        .append(cd.getP1()).append(", ").append(cd.getP2()).append(", ")
                        .append(cd.getK3()).append(", ").append(cd.getK4()).append(", ")
                        .append(cd.getK5()).append(", ").append(cd.getK6()).append("],\n");
            }
            D2CTransform tf = cam.getD2CTransform();
            if (tf != null) {
                float[] rot = tf.getRot();
                float[] trans = tf.getTrans();
                sb.append("  \"d2c_rot\": [");
                for (int i = 0; i < rot.length; i++) {
                    sb.append(rot[i]);
                    if (i < rot.length - 1) sb.append(", ");
                }
                sb.append("],\n");
                sb.append("  \"d2c_trans\": [");
                for (int i = 0; i < trans.length; i++) {
                    sb.append(trans[i]);
                    if (i < trans.length - 1) sb.append(", ");
                }
                sb.append("],\n");
                sb.append("  \"is_mirrored\": ").append(cam.isMirrored()).append(",\n");
            }
        }
        // 去掉最后一个逗号+换行
        int idx = sb.lastIndexOf(",\n");
        if (idx == sb.length() - 2) {
            sb.delete(idx, sb.length());
            sb.append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private void saveViaMediaStore(String displayName, String mimeType, IOConsumer writer) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, SAVE_DIR);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new Exception("MediaStore insert failed");
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                throw new Exception("openOutputStream failed");
            }
            writer.write(os);
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, values, null, null);
    }

    @FunctionalInterface
    private interface IOConsumer {
        void write(OutputStream os) throws Exception;
    }

    private void setStatus(String msg) {
        mMainHandler.post(() -> mStatusText.setText(msg));
    }

    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void releaseDeviceQuietly() {
        if (mDevice != null) {
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
        mIsRunning = false;
        mBurstRunning = false;
        mMainHandler.removeCallbacksAndMessages(null);
        if (mRenderThread != null) {
            mRenderThread.interrupt();
            mRenderThread = null;
        }
        mSaveExecutor.shutdownNow();
        try {
            if (mPipeline != null) {
                mPipeline.stop();
                mPipeline.close();
                mPipeline = null;
            }
            if (mConfig != null) {
                mConfig.close();
                mConfig = null;
            }
            if (mFormatConvertFilter != null) {
                mFormatConvertFilter.close();
                mFormatConvertFilter = null;
            }
            releaseDeviceQuietly();
            if (mOBContext != null) {
                mOBContext.close();
                mOBContext = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "release error: " + e.getMessage());
        }
    }
}
