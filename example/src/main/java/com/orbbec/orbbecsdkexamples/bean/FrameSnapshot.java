package com.orbbec.orbbecsdkexamples.bean;

import com.orbbec.obsensor.Format;
import com.orbbec.obsensor.StreamType;

/**
 * Immutable snapshot of the latest frame, used by the photo capture feature.
 * The {@link #data} array must not be modified after publishing the snapshot.
 */
public class FrameSnapshot {
    public final int width;
    public final int height;
    public final StreamType streamType;
    public final Format format;
    public final float valueScale;
    public final byte[] data;

    public FrameSnapshot(int width, int height, StreamType streamType, Format format,
                         float valueScale, byte[] data) {
        this.width = width;
        this.height = height;
        this.streamType = streamType;
        this.format = format;
        this.valueScale = valueScale;
        this.data = data;
    }
}
