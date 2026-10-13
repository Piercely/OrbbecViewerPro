package com.orbbec.orbbecsdkexamples.utils;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaActionSound;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.bean.FrameSnapshot;

/**
 * Bridges the shutter button on a viewer page with the latest frame snapshot
 * and saves it as a JPEG into the gallery.
 */
public class CaptureController {

    private final Activity mActivity;
    private final String mPrefix;
    private final TextView mWaitingView;
    private final MediaActionSound mShutterSound;

    private final Object mLock = new Object();
    private FrameSnapshot mLatestSnapshot;
    private boolean mSaving;

    public CaptureController(Activity activity, View shutterButton, TextView waitingView, String prefix) {
        mActivity = activity;
        mPrefix = prefix;
        mWaitingView = waitingView;
        MediaActionSound sound = null;
        try {
            sound = new MediaActionSound();
            sound.load(MediaActionSound.SHUTTER_CLICK);
        } catch (Exception e) {
            sound = null;
        }
        mShutterSound = sound;

        shutterButton.setOnClickListener(v -> capture());
    }

    /** Called from the streaming thread for every received frame. */
    public void publish(FrameSnapshot snapshot) {
        synchronized (mLock) {
            mLatestSnapshot = snapshot;
        }
        if (mWaitingView != null && mWaitingView.getVisibility() != View.GONE) {
            mActivity.runOnUiThread(() -> mWaitingView.setVisibility(View.GONE));
        }
    }

    private void capture() {
        final FrameSnapshot snapshot;
        synchronized (mLock) {
            snapshot = mLatestSnapshot;
        }
        if (snapshot == null) {
            Toast.makeText(mActivity, R.string.photo_no_frame, Toast.LENGTH_SHORT).show();
            return;
        }
        synchronized (mLock) {
            if (mSaving) {
                return;
            }
            mSaving = true;
        }

        new Thread(() -> {
            String savedPath = null;
            try {
                Bitmap bitmap = FrameBitmapFactory.toBitmap(snapshot);
                if (bitmap != null) {
                    savedPath = PhotoSaver.save(mActivity.getApplicationContext(), bitmap, mPrefix);
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                final String result = savedPath;
                mActivity.runOnUiThread(() -> {
                    if (result != null) {
                        if (mShutterSound != null) {
                            try {
                                mShutterSound.play(MediaActionSound.SHUTTER_CLICK);
                            } catch (Exception ignored) {
                            }
                        }
                        Toast.makeText(mActivity,
                                mActivity.getString(R.string.photo_saved, result),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(mActivity, R.string.photo_failed, Toast.LENGTH_SHORT).show();
                    }
                    synchronized (mLock) {
                        mSaving = false;
                    }
                });
            }
        }, "photo-capture").start();
    }

    public void release() {
        if (mShutterSound != null) {
            try {
                mShutterSound.release();
            } catch (Exception ignored) {
            }
        }
    }
}
