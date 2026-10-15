package com.orbbec.orbbecsdkexamples.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.adapter.FeatureAdapter;
import com.orbbec.orbbecsdkexamples.bean.Feature;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    static {
        System.loadLibrary("orbbecsdkexamples");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestPermission();
        initFeatureGrid();
    }

    private void requestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE},
                    999);
        }
    }

    private void initFeatureGrid() {
        RecyclerView recyclerView = findViewById(R.id.rv_features);
        GridLayoutManager layoutManager = new GridLayoutManager(this, 2);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setNestedScrollingEnabled(false);
        recyclerView.setHasFixedSize(false);

        int space = (int) getResources().getDimension(R.dimen.home_grid_space);
        recyclerView.addItemDecoration(new GridSpaceDecoration(space));

        List<Feature> features = new ArrayList<>();
        features.add(new Feature(R.string.feat_hello, R.string.feat_hello_desc,
                R.drawable.ic_feat_hello, R.color.cat_violet, R.color.cat_violet_bg,
                HelloOrbbecActivity.class));
        features.add(new Feature(R.string.feat_color, R.string.feat_color_desc,
                R.drawable.ic_feat_color, R.color.cat_blue, R.color.cat_blue_bg,
                ColorViewerActivity.class));
        features.add(new Feature(R.string.feat_depth, R.string.feat_depth_desc,
                R.drawable.ic_feat_depth, R.color.cat_cyan, R.color.cat_cyan_bg,
                DepthViewerActivity.class));
        features.add(new Feature(R.string.feat_ir, R.string.feat_ir_desc,
                R.drawable.ic_feat_ir, R.color.cat_teal, R.color.cat_teal_bg,
                InfraredViewerActivity.class));
        features.add(new Feature(R.string.feat_align, R.string.feat_align_desc,
                R.drawable.ic_feat_align, R.color.cat_teal, R.color.cat_teal_bg,
                SyncAlignViewerActivity.class));
        features.add(new Feature(R.string.feat_capture, R.string.feat_capture_desc,
                R.drawable.ic_feat_save, R.color.cat_amber, R.color.cat_amber_bg,
                CaptureDatasetActivity.class));
        features.add(new Feature(R.string.feat_pointcloud, R.string.feat_pointcloud_desc,
                R.drawable.ic_feat_pointcloud, R.color.cat_violet, R.color.cat_violet_bg,
                PointCloudActivity.class));
        features.add(new Feature(R.string.feat_imu, R.string.feat_imu_desc,
                R.drawable.ic_feat_imu, R.color.cat_amber, R.color.cat_amber_bg,
                ImuActivity.class));
        features.add(new Feature(R.string.feat_mode, R.string.feat_mode_desc,
                R.drawable.ic_feat_mode, R.color.cat_rose, R.color.cat_rose_bg,
                DepthModeActivity.class));
        features.add(new Feature(R.string.feat_multi, R.string.feat_multi_desc,
                R.drawable.ic_feat_multi, R.color.cat_blue, R.color.cat_blue_bg,
                MultiDeviceActivity.class));
        features.add(new Feature(R.string.feat_control, R.string.feat_control_desc,
                R.drawable.ic_feat_control, R.color.cat_amber, R.color.cat_amber_bg,
                SensorControlActivity.class));
        features.add(new Feature(R.string.feat_record, R.string.feat_record_desc,
                R.drawable.ic_feat_record, R.color.cat_rose, R.color.cat_rose_bg,
                RecordPlaybackActivity.class));
        features.add(new Feature(R.string.feat_save, R.string.feat_save_desc,
                R.drawable.ic_feat_save, R.color.cat_cyan, R.color.cat_cyan_bg,
                SaveToDiskActivity.class));
        features.add(new Feature(R.string.feat_hot, R.string.feat_hot_desc,
                R.drawable.ic_feat_hot, R.color.cat_amber, R.color.cat_amber_bg,
                HotPluginActivity.class));

        recyclerView.setAdapter(new FeatureAdapter(features));
    }

    /** Evenly spaces the two-column feature grid. */
    private static class GridSpaceDecoration extends RecyclerView.ItemDecoration {
        private final int mSpace;

        GridSpaceDecoration(int space) {
            mSpace = space;
        }

        @Override
        public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                   @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
            outRect.bottom = mSpace;
            int position = parent.getChildAdapterPosition(view);
            if (position % 2 == 0) {
                outRect.right = mSpace / 2;
            } else {
                outRect.left = mSpace / 2;
            }
        }
    }
}
