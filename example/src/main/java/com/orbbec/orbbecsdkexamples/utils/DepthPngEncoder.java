package com.orbbec.orbbecsdkexamples.utils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 16-bit 灰度 PNG 编码器（无第三方依赖）。
 * <p>
 * 用于将深度帧（uint16，单位 mm）无损保存为 16-bit PNG，
 * 训练侧可用 OpenCV/NumPy 直接读取为 uint16 数组。
 * <p>
 * PNG 规范：bit depth = 16，color type = 0（灰度），每个样本大端序。
 */
public final class DepthPngEncoder {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private DepthPngEncoder() {
    }

    /**
     * 将深度数据编码为 16-bit 灰度 PNG 字节流。
     *
     * @param depthData 原始深度字节（uint16 小端序，由 Orbbec DepthFrame.getData() 获得）
     * @param width     图像宽
     * @param height    图像高
     * @param valueScale 深度值缩放（mm = raw * valueScale）；通常为 1.0
     * @return PNG 字节数组
     */
    public static byte[] encode(byte[] depthData, int width, int height, float valueScale) {
        // 每像素 2 字节（uint16）
        int bytesPerRow = width * 2;
        // 原始图像数据：每行前置 1 字节 filter type（0 = None）
        byte[] raw = new byte[(bytesPerRow + 1) * height];

        for (int y = 0; y < height; y++) {
            int rowStart = y * (bytesPerRow + 1);
            raw[rowStart] = 0; // filter: None
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 2;
                if (idx + 1 >= depthData.length) {
                    break;
                }
                // Orbbec 深度帧为小端序 uint16
                int rawValue = (depthData[idx] & 0xFF) | ((depthData[idx + 1] & 0xFF) << 8);
                int mm = Math.round(rawValue * valueScale);
                if (mm < 0) mm = 0;
                if (mm > 0xFFFF) mm = 0xFFFF;
                // PNG 16-bit 样本为大端序
                int outIdx = rowStart + 1 + x * 2;
                raw[outIdx] = (byte) ((mm >> 8) & 0xFF);
                raw[outIdx + 1] = (byte) (mm & 0xFF);
            }
        }

        // zlib 压缩
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(raw);
        deflater.finish();
        byte[] compressed = new byte[raw.length];
        int compressedLen = deflater.deflate(compressed);
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(PNG_SIGNATURE);
            // IHDR
            byte[] ihdr = buildIHDR(width, height);
            writeChunk(out, "IHDR", ihdr);
            // IDAT
            byte[] idat = new byte[compressedLen];
            System.arraycopy(compressed, 0, idat, 0, compressedLen);
            writeChunk(out, "IDAT", idat);
            // IEND
            writeChunk(out, "IEND", new byte[0]);
        } catch (IOException e) {
            throw new RuntimeException("DepthPngEncoder encode failed", e);
        }
        return out.toByteArray();
    }

    private static byte[] buildIHDR(int width, int height) {
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 16;   // bit depth
        ihdr[9] = 0;    // color type: grayscale
        ihdr[10] = 0;   // compression
        ihdr[11] = 0;   // filter
        ihdr[12] = 0;   // interlace
        return ihdr;
    }

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes("US-ASCII");
        byte[] lenBytes = new byte[4];
        putInt(lenBytes, 0, data.length);
        out.write(lenBytes);
        out.write(typeBytes);
        out.write(data);

        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static void putInt(byte[] arr, int offset, int value) {
        arr[offset] = (byte) ((value >> 24) & 0xFF);
        arr[offset + 1] = (byte) ((value >> 16) & 0xFF);
        arr[offset + 2] = (byte) ((value >> 8) & 0xFF);
        arr[offset + 3] = (byte) (value & 0xFF);
    }
}
