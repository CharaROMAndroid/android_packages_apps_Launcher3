package com.android.launcher3.customization;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;

import static android.content.pm.PackageManager.GET_META_DATA;

public class MetadataExtractor {
    private final Context mContext;
    private final PackageManager mPm;

    private String mSourcePkg = "";
    private Intent mMarketIntent;

    private long mLastUpdate = 0;
    private String mVersionName = "Unknown";
    private long mVersionCode = 0;

    public MetadataExtractor(Context context, ComponentName cn) {
        mContext = context;
        mPm = context.getPackageManager();
        String pkg = cn.getPackageName();

        try {
            mSourcePkg = mPm.getInstallerPackageName(pkg);
            if (!TextUtils.isEmpty(mSourcePkg)) {
                mMarketIntent = new Intent(Intent.ACTION_VIEW)
                        .setData(Uri.parse("market://details?id=" + pkg))
                        .setPackage(mSourcePkg)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        } catch (IllegalArgumentException | SecurityException e) {
            // Unknown package or hidden by package visibility: no installer info.
        }

        try {
            PackageInfo pi = mPm.getPackageInfo(pkg, GET_META_DATA);
            mLastUpdate = pi.lastUpdateTime;
            mVersionName = pi.versionName != null ? pi.versionName : mVersionName;
            mVersionCode = pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
            // Uninstalled mid-load or not visible: keep defaults.
        }
    }

    public CharSequence getSource() {
        if (TextUtils.isEmpty(mSourcePkg)) {
            return mContext.getString(R.string.app_info_source_system);
        }
        try {
            ApplicationInfo pi = mPm.getApplicationInfo(mSourcePkg, 0);
            return pi.loadLabel(mPm);
        } catch (PackageManager.NameNotFoundException | SecurityException e) {
        }
        return mSourcePkg;
    }

    public String getLastUpdate() {
        if (mLastUpdate <= 0) {
            return "";
        }
        return DateUtils.formatDateTime(mContext, mLastUpdate,
                DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_SHOW_DATE);
    }

    public String getVersionName() {
        return mVersionName;
    }

    public long getVersionCode() {
        return mVersionCode;
    }

    public Intent getMarketIntent() {
        return mMarketIntent;
    }

}
