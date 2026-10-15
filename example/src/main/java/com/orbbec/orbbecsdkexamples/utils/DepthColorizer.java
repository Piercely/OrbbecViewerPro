package com.orbbec.orbbecsdkexamples.utils;

/**
 * 深度伪彩工具：将 Y16 深度数据转换为彩色，并与 RGB 图像做 alpha 混合叠加。
 * 无效深度（0）像素不参与叠加。
 */
public class DepthColorizer {

    /** 最小深度范围 mm，小于该值的视为 0 距离颜色 */
    public static final float MIN_MM = 200f;
    /** 最大深度范围 mm，超出该值的视为最远颜色 */
    public static final float MAX_MM = 3000f;

    /**
     * 将深度伪彩叠加到 RGB888 图像上（原地修改 colorRgb）。
     *
     * @param colorRgb   RGB888 图像数据（会被原地修改）
     * @param depthData  Y16 uint16 LE 深度数据
     * @param w          图像宽
     * @param h          图像高
     * @param depthScale 深度数值缩放（mm = raw * depthScale）
     * @param alpha      叠加透明度 0~1
     */
    public static void blendDepth(byte[] colorRgb, byte[] depthData, int w, int h,
                                  float depthScale, float alpha) {
        if (colorRgb == null || depthData == null || w <= 0 || h <= 0) {
            return;
        }
        int pixels = Math.min(w * h, Math.min(colorRgb.length / 3, depthData.length / 2));
        float invRange = 1.0f / (MAX_MM - MIN_MM);
        for (int i = 0; i < pixels; i++) {
            int lo = depthData[i * 2] & 0xFF;
            int hi = depthData[i * 2 + 1] & 0xFF;
            int raw = (hi << 8) | lo;
            if (raw == 0) {
                continue; // 无效深度不叠加
            }
            float mm = raw * depthScale;
            float t = (mm - MIN_MM) * invRange;
            if (t < 0f) t = 0f;
            else if (t > 1f) t = 1f;

            // HSV: hue 从 2/3 (蓝) 到 0 (红)
            float hue = (1f - t) * (2f / 3f);
            int c = hsvToRgb(hue, 1f, 1f);
            int dr = (c >> 16) & 0xFF;
            int dg = (c >> 8) & 0xFF;
            int db = c & 0xFF;

            int off = i * 3;
            int r = colorRgb[off] & 0xFF;
            int g = colorRgb[off + 1] & 0xFF;
            int b = colorRgb[off + 2] & 0xFF;

            colorRgb[off] = (byte) ((r * (1f - alpha) + dr * alpha) + 0.5f);
            colorRgb[off + 1] = (byte) ((g * (1f - alpha) + dg * alpha) + 0.5f);
            colorRgb[off + 2] = (byte) ((b * (1f - alpha) + db * alpha) + 0.5f);
        }
    }

    /** HSV (h:0..1, s:0..1, v:0..1) -> 0xRRGGBB */
    private static int hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1f - Math.abs((h * 6f) % 2f - 1f));
        float m = v - c;
        float r1, g1, b1;
        int sector = (int) (h * 6f);
        switch (sector) {
            case 0: r1 = c; g1 = x; b1 = 0; break;
            case 1: r1 = x; g1 = c; b1 = 0; break;
            case 2: r1 = 0; g1 = c; b1 = x; break;
            case 3: r1 = 0; g1 = x; b1 = c; break;
            case 4: r1 = x; g1 = 0; b1 = c; break;
            default: r1 = c; g1 = 0; b1 = x; break;
        }
        int r = (int) ((r1 + m) * 255f + 0.5f);
        int g = (int) ((g1 + m) * 255f + 0.5f);
        int b = (int) ((b1 + m) * 255f + 0.5f);
        return (r << 16) | (g << 8) | b;
    }
}
