package com.orbbec.orbbecsdkexamples.adapter;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.orbbec.orbbecsdkexamples.R;
import com.orbbec.orbbecsdkexamples.bean.Feature;

import java.util.List;

/**
 * Mainstream card-grid adapter for the home feature list.
 */
public class FeatureAdapter extends RecyclerView.Adapter<FeatureAdapter.FeatureHolder> {

    private final List<Feature> mFeatures;

    public FeatureAdapter(List<Feature> features) {
        mFeatures = features;
    }

    @NonNull
    @Override
    public FeatureHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_feature_card, parent, false);
        return new FeatureHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FeatureHolder holder, int position) {
        Feature feature = mFeatures.get(position);
        Context context = holder.itemView.getContext();

        holder.title.setText(feature.titleRes);
        holder.desc.setText(feature.descRes);
        holder.icon.setImageResource(feature.iconRes);

        int iconColor = ContextCompat.getColor(context, feature.iconColorRes);
        int iconBg = ContextCompat.getColor(context, feature.iconBgRes);
        holder.icon.setColorFilter(iconColor);
        GradientDrawable tile = new GradientDrawable();
        tile.setShape(GradientDrawable.RECTANGLE);
        float radius = context.getResources().getDimension(R.dimen.home_icon_radius);
        tile.setCornerRadius(radius);
        tile.setColor(iconBg);
        holder.icon.setBackground(tile);

        holder.card.setOnClickListener(v -> {
            Intent intent = new Intent(context, feature.targetActivity);
            context.startActivity(intent);
        });
    }

    @Override
    public int getItemCount() {
        return mFeatures == null ? 0 : mFeatures.size();
    }

    static class FeatureHolder extends RecyclerView.ViewHolder {
        MaterialCardView card;
        ImageView icon;
        TextView title;
        TextView desc;

        FeatureHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card_feature);
            icon = itemView.findViewById(R.id.iv_feature_icon);
            title = itemView.findViewById(R.id.tv_feature_title);
            desc = itemView.findViewById(R.id.tv_feature_desc);
        }
    }
}
