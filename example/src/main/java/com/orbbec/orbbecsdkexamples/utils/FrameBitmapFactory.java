package com.orbbec.orbbecsdkexamples.utils;

import android.graphics.Bitmap;
import android.util.Log;

import com.libyuv.util.YuvUtil;
import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.StreamType;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;

import java.nio.ByteBuffer;

/**
 * Converts a cached raw frame (color / depth / IR) into an Android Bitmap,
 * reusing the same decode pipeline as the OpenGL renderer so the saved photo
 * matches the on-screen image.
 */
public final class FrameBitmapFactory {

    private static final String TAG = "FrameBitmapFactory";

    private FrameBitmapFactory() {
    }

    public static Bitmap toBitmap(FrameSnapshot snapshot) {
        if (snapshot == null || snapshot.data == null
                || snapshot.width <= 0 || snapshot.height <= 0) {
            return null;
        }
        try {
            int w = snapshot.width;
            int h = snapshot.height;
            if (snapshot.streamType == StreamType.COLOR) {
                return decodeRgb(snapshot.data, w, h);
            } else if (snapshot.streamType == StreamType.DEPTH) {
                return decodeDepth(snapshot, w, h);
            } else if (snapshot.streamType == StreamType.IR) {
                return decodeIr(snapshot, w, h);
            }
        } catch (Exception e) {
            Log.e(TAG, "toBitmap failed: " + e.getMessage(), e);
        }
        return null;
    }

    /** RGB888 packed data -> ARGB bitmap. */
    private static Bitmap decodeRgb(byte[] rgb, int w, int h) {
        int[] colors = ImageUtils.rgbToArgb(rgb);
        if (colors == null || colors.length < w * h) {
            return null;
        }
        return Bitmap.createBitmap(colors, w, h, Bitmap.Config.ARGB_8888);
    }

    /** Raw Y16 depth -> rainbow colorized bitmap (same mapping as live view). */
    private static Bitmap decodeDepth(FrameSnapshot snapshot, int w, int h) {
        ByteBuffer src = ByteBuffer.allocateDirect(snapshot.data.length);
        src.put(snapshot.data);
        src.flip();

        // Apply depth value scale exactly like the renderer does.
        ImageUtils.nScalePrecisionToDepthPixel(src, w, h, snapshot.data.length, snapshot.valueScale);

        ByteBuffer rgbBuffer = ByteBuffer.allocateDirect(w * h * 3);
        ImageUtils.depthToRgb(src, rgbBuffer);

        byte[] rgb = new byte[w * h * 3];
        rgbBuffer.get(rgb);
        return decodeRgb(rgb, w, h);
    }

    /** IR frame: Y8 expanded to grey RGB, other Y16-like formats decoded with libyuv. */
    private static Bitmap decodeIr(FrameSnapshot snapshot, int w, int h) {
        ByteBuffer src = ByteBuffer.allocateDirect(snapshot.data.length);
        src.put(snapshot.data);
        src.flip();

        ByteBuffer rgbBuffer = ByteBuffer.allocateDirect(w * h * 3);
        if (snapshot.format == Format.Y8) {
            ImageUtils.y8ToRgb(src, rgbBuffer, w, h);
        } else {
            YuvUtil.ir2RGB888(src, rgbBuffer, w, h, snapshot.data.length);
        }

        byte[] rgb = new byte[w * h * 3];
        rgbBuffer.get(rgb);
        return decodeRgb(rgb, w, h);
    }
}
