package com.orbbec.orbbecsdkexamples.bean;

import androidx.annotation.DrawableRes;
import androidx.annotation.ColorRes;
import androidx.annotation.StringRes;

/**
 * Home page feature card model.
 */
public class Feature {

    @StringRes
    public final int titleRes;
    @StringRes
    public final int descRes;
    @DrawableRes
    public final int iconRes;
    @ColorRes
    public final int iconColorRes;
    @ColorRes
    public final int iconBgRes;
    public final Class<?> targetActivity;

    public Feature(@StringRes int titleRes,
                   @StringRes int descRes,
                   @DrawableRes int iconRes,
                   @ColorRes int iconColorRes,
                   @ColorRes int iconBgRes,
                   Class<?> targetActivity) {
        this.titleRes = titleRes;
        this.descRes = descRes;
        this.iconRes = iconRes;
        this.iconColorRes = iconColorRes;
        this.iconBgRes = iconBgRes;
        this.targetActivity = targetActivity;
    }
}
