package com.orbbec.orbbecsdkexamples.utils;

import android.util.Log;

import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.StreamProfile;
import com.orbbec.obsensor.StreamProfileList;
import com.orbbec.obsensor.StreamType;
import com.orbbec.obsensor.VideoStreamProfile;

public class LocalUtils {
    private static final String TAG = "LocalUtils";

    public static String formatHex04(int value) {
        return String.format("0x%04x", value);
    }

    /**
     * 从传感器实际支持的配置列表中选择最合适的视频流配置（对齐官方 OrbbecViewer 的做法：
     * 永远从设备真实列表推导，而不是硬编码分辨率）。
     * <p>
     * 选择优先级：完全匹配(宽+格式+帧率) > 宽+帧率匹配 > 最接近 30fps 且宽度最接近期望值的配置 > 列表第一项。
     *
     * @param list         传感器配置列表（不会被关闭）
     * @param preferWidth  期望宽度，如 640
     * @param preferFps    期望帧率，如 30
     * @param preferFormat 期望格式，无要求传 Format.UNKNOWN
     * @return 匹配到的配置，调用方负责 close()；列表为空时返回 null
     */
    public static VideoStreamProfile pickVideoProfile(StreamProfileList list,
                                                      int preferWidth, int preferFps, Format preferFormat) {
        if (list == null || list.getStreamProfileCount() <= 0) {
            return null;
        }

        // 1. 精确匹配（SDK 内部匹配失败会抛异常）
        try {
            return list.getVideoStreamProfile(preferWidth, 0, preferFormat, preferFps);
        } catch (Exception ignore) {
        }

        // 2. 宽度+帧率匹配（不限格式）
        if (preferFormat != Format.UNKNOWN) {
            try {
                return list.getVideoStreamProfile(preferWidth, 0, Format.UNKNOWN, preferFps);
            } catch (Exception ignore) {
            }
        }

        // 3. 遍历真实列表，选择：帧率最接近期望值，宽度最接近期望值
        int bestIndex = -1;
        int bestFps = 0, bestW = 0;
        int count = list.getStreamProfileCount();
        for (int i = 0; i < count; i++) {
            StreamProfile sp = null;
            try {
                sp = list.getStreamProfile(i);
                if (sp == null) {
                    continue;
                }
                VideoStreamProfile vsp = sp.as(StreamType.VIDEO);
                int w = vsp.getWidth();
                int fps = vsp.getFps();
                if (bestIndex < 0
                        || Math.abs(fps - preferFps) < Math.abs(bestFps - preferFps)
                        || (Math.abs(fps - preferFps) == Math.abs(bestFps - preferFps)
                            && Math.abs(w - preferWidth) < Math.abs(bestW - preferWidth))) {
                    bestIndex = i;
                    bestFps = fps;
                    bestW = w;
                }
            } catch (Exception e) {
                Log.w(TAG, "pickVideoProfile enumerate #" + i + " failed: " + e.getMessage());
            } finally {
                // as() 与 sp 共享同一 native 句柄，只 close 一次
                if (sp != null) {
                    try {
                        sp.close();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        if (bestIndex < 0) {
            return null;
        }
        VideoStreamProfile best = list.getStreamProfile(bestIndex).as(StreamType.VIDEO);
        Log.i(TAG, "pickVideoProfile fallback -> " + best.getWidth() + "x" + best.getHeight()
                + "@" + best.getFps() + "fps " + best.getFormat());
        return best;
    }
}
