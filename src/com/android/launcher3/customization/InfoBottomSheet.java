/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Application info bottom sheet, ported from the A16 Shade-style sheet
 * (Amir Zaidi, DennySPb, Pranav Vashi) to the A17 slide-in view stack.
 */
package com.android.launcher3.customization;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR;

import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Process;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseActivity;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.Utilities;
import com.android.launcher3.R;
import com.android.launcher3.icons.pack.IconPackManager;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.util.AppReloader;
import com.android.launcher3.util.BlurBackgroundHelper;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.views.AbstractSlideInView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bottom sheet showing app metadata (version, last update, install source), a per-app icon pack
 * chooser, a route to the modern custom icon picker and a route to the system app info screen.
 */
public class InfoBottomSheet extends AbstractSlideInView<BaseActivity> {
    private static final int DEFAULT_CLOSE_DURATION = 200;

    private final BlurBackgroundHelper mBlurBackgroundHelper;

    private TextView mTitle;
    private LinearLayout mPackList;
    private View[] mMetadataRows;
    private View mVersionRow;
    private View mLastUpdateRow;
    private View mSourceRow;
    private View mCustomIconRow;
    private View mIconPackRow;
    private View mMoreRow;

    private ItemInfo mItemInfo;
    private ComponentKey mKey;
    private Rect mSourceBounds = new Rect();
    private boolean mShowingPackChooser;

    public InfoBottomSheet(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public InfoBottomSheet(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mBlurBackgroundHelper = mActivityContext.getActivityComponent().getBlurBackgroundHelper();
        setWillNotDraw(false);
    }

    /**
     * Whether the info sheet can describe this item. Items without an app component, and items
     * not backed by a BaseActivity, keep opening the system app info screen directly.
     */
    public static boolean canShowFor(Object activityContext, @Nullable ItemInfo itemInfo) {
        return activityContext instanceof BaseActivity
                && itemInfo != null
                && itemInfo.getTargetComponent() != null
                && itemInfo.getComponentKey() != null
                // PackageManager metadata and icon overrides here are for the launcher's own
                // user; work/private profile items keep the system app info screen.
                && itemInfo.user != null
                && itemInfo.user.equals(Process.myUserHandle());
    }

    /**
     * Opens the info sheet when it applies; returns false so the caller falls back to the system
     * app info screen. Recents/task surfaces, secondary displays, taskbar and non-app items always
     * use the system screen, so the sheet never races a Quickstep transition.
     */
    public static boolean tryShow(Object target, @Nullable ItemInfo itemInfo, View view) {
        if (!(target instanceof Launcher) || !canShowFor(target, itemInfo)
                || itemInfo.itemType != LauncherSettings.Favorites.ITEM_TYPE_APPLICATION) {
            return false;
        }
        Launcher launcher = (Launcher) target;
        if (launcher.getStateManager().getState().isRecentsViewVisible) {
            return false;
        }
        try {
            // Close the long-press popup and any other floating view before the sheet opens.
            AbstractFloatingView.closeOpenViews(launcher, true,
                    AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
            show(launcher, itemInfo, Utilities.getViewBounds(view));
            return true;
        } catch (InflateException e) {
            return false;
        }
    }

    /** Inflates and shows the sheet. Throws InflateException if it can't be inflated. */
    public static InfoBottomSheet show(BaseActivity activity, ItemInfo itemInfo,
            Rect sourceBounds) {
        InfoBottomSheet sheet = (InfoBottomSheet) activity.getLayoutInflater().inflate(
                R.layout.app_info_bottom_sheet, activity.getDragLayer(), false);
        sheet.populateAndShow(itemInfo, sourceBounds);
        return sheet;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mContent = findViewById(R.id.app_info_content);
        mTitle = findViewById(R.id.app_info_title);
        mPackList = findViewById(R.id.app_info_pack_list);
        mVersionRow = findViewById(R.id.app_info_version);
        mLastUpdateRow = findViewById(R.id.app_info_last_update);
        mSourceRow = findViewById(R.id.app_info_source);
        mCustomIconRow = findViewById(R.id.app_info_custom_icon);
        mIconPackRow = findViewById(R.id.app_info_icon_pack);
        mMoreRow = findViewById(R.id.app_info_more);
        mMetadataRows = new View[]{mVersionRow, mLastUpdateRow, mSourceRow, mCustomIconRow,
                mIconPackRow};
        mContent.setBackground(
                getContext().getDrawable(R.drawable.bg_rounded_corner_bottom_sheet).mutate());

        bindRow(mVersionRow, R.drawable.ic_app_info_version, R.string.app_info_version);
        bindRow(mLastUpdateRow, R.drawable.ic_app_info_last_update, R.string.app_info_last_update);
        bindRow(mSourceRow, R.drawable.ic_app_info_source, R.string.app_info_source);
        bindRow(mCustomIconRow, R.drawable.ic_palette, R.string.app_info_custom_icon_title);
        bindRow(mIconPackRow, R.drawable.ic_app_info_icon_pack, R.string.icon_pack_title);
        bindRow(mMoreRow, R.drawable.ic_info_no_shadow, R.string.app_info_more);
        // Static metadata rows are informational only.
        mVersionRow.setBackground(null);
        mLastUpdateRow.setBackground(null);
        mSourceRow.setBackground(null);
        mSourceRow.setClickable(false);
    }

    private static void bindRow(View row, int iconRes, int titleRes) {
        ((ImageView) row.findViewById(R.id.app_info_row_icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.app_info_row_title)).setText(titleRes);
    }

    private static void setSummary(View row, @Nullable CharSequence summary) {
        TextView tv = row.findViewById(R.id.app_info_row_summary);
        tv.setText(summary);
        tv.setVisibility(summary == null || summary.length() == 0 ? GONE : VISIBLE);
    }

    private void populateAndShow(ItemInfo itemInfo, Rect sourceBounds) {
        mItemInfo = itemInfo;
        mKey = itemInfo.getComponentKey();
        mSourceBounds = sourceBounds != null ? new Rect(sourceBounds) : new Rect();

        mTitle.setText(itemInfo.title == null || itemInfo.title.length() == 0
                ? getContext().getString(R.string.app_info_title) : itemInfo.title);

        // Placeholder summaries until metadata resolves off the main thread.
        setSummary(mVersionRow, null);
        setSummary(mLastUpdateRow, null);
        setSummary(mSourceRow, null);

        loadMetadata();
        refreshIconRows();

        mMoreRow.setOnClickListener(v -> openSystemInfo());

        attachToContainer();
        post(this::applySheetBlur);
        mIsOpen = false;
        animateOpenSelf();
    }

    private void loadMetadata() {
        final Context context = getContext();
        final ComponentName component = mItemInfo.getTargetComponent();
        final ComponentKey key = mKey;
        THREAD_POOL_EXECUTOR.execute(() -> {
            MetadataExtractor extractor = new MetadataExtractor(context, component);
            CharSequence source = extractor.getSource();
            CharSequence lastUpdate = extractor.getLastUpdate();
            CharSequence version = context.getString(R.string.app_info_version_value,
                    extractor.getVersionName(), extractor.getVersionCode());
            Intent marketIntent = extractor.getMarketIntent();
            MAIN_EXECUTOR.execute(() -> {
                // Sheet may have been closed or re-targeted while loading.
                if (!isAttachedToWindow() || !key.equals(mKey)) {
                    return;
                }
                setSummary(mVersionRow, version);
                setSummary(mLastUpdateRow, lastUpdate);
                setSummary(mSourceRow, source);
                if (marketIntent != null) {
                    TypedArray ta = getContext().obtainStyledAttributes(
                            new int[]{android.R.attr.selectableItemBackground});
                    try {
                        mSourceRow.setBackground(ta.getDrawable(0));
                    } finally {
                        ta.recycle();
                    }
                    mSourceRow.setOnClickListener(v -> startExternal(marketIntent));
                }
            });
        });
    }

    private void refreshIconRows() {
        boolean hasPacks = !IconPackManager.get(getContext()).getProviderNames().isEmpty();
        mIconPackRow.setVisibility(hasPacks ? VISIBLE : GONE);
        mCustomIconRow.setVisibility(hasPacks ? VISIBLE : GONE);
        setSummary(mCustomIconRow, getContext().getString(R.string.app_info_custom_icon_summary));
        if (!hasPacks) {
            return;
        }
        setSummary(mIconPackRow, currentPackLabel());
        mIconPackRow.setOnClickListener(v -> showPackChooser());
        mCustomIconRow.setOnClickListener(v -> {
            final ComponentKey key = mKey;
            final BaseActivity activity = mActivityContext;
            // Hand off to the A17 icon picker; the info sheet closes as it opens.
            close(false);
            IconPickerBottomSheet picker = (IconPickerBottomSheet) activity.getLayoutInflater()
                    .inflate(R.layout.icon_picker_bottom_sheet, activity.getDragLayer(), false);
            picker.show(key, () -> AppReloader.get(activity).reload(key));
        });
    }

    private CharSequence currentPackLabel() {
        String current = IconDatabase.getByComponent(getContext(), mKey);
        if (current == null || current.isEmpty()) {
            return getContext().getString(R.string.icon_pack_default_label);
        }
        CharSequence title = IconPackManager.get(getContext()).getProviderNames().get(current);
        return title != null ? title : current;
    }

    /**
     * Swaps the metadata rows for the list of icon packs usable for this app. The More row stays
     * visible and back (or tapping the title) returns to the metadata rows.
     */
    private void showPackChooser() {
        mShowingPackChooser = true;
        final Context context = getContext();
        final IconPackManager ipm = IconPackManager.get(context);
        final Map<String, CharSequence> all = ipm.getProviderNames();
        final String globalPack = IconDatabase.getGlobal(context);
        final String selected = IconDatabase.getByComponent(context, mKey);
        final ComponentName cn = mKey.componentName;

        THREAD_POOL_EXECUTOR.execute(() -> {
            // Packs with an icon for this app, plus the global pack and the current one.
            List<Map.Entry<String, CharSequence>> packs = new ArrayList<>();
            for (Map.Entry<String, CharSequence> e : all.entrySet()) {
                String pkg = e.getKey();
                if (e.getValue() != null && (pkg.equals(globalPack) || pkg.equals(selected)
                        || ipm.packContainsActivity(pkg, cn))) {
                    packs.add(e);
                }
            }
            packs.sort((x, y) -> x.getValue().toString().toLowerCase(Locale.ROOT)
                    .compareTo(y.getValue().toString().toLowerCase(Locale.ROOT)));
            MAIN_EXECUTOR.execute(() -> {
                if (!isAttachedToWindow() || !mShowingPackChooser) {
                    return;
                }
                renderPackChooser(packs, selected);
            });
        });
    }

    private void renderPackChooser(List<Map.Entry<String, CharSequence>> packs, String selected) {
        mPackList.removeAllViews();
        mTitle.setText(R.string.icon_pack_title);
        mTitle.setOnClickListener(v -> showMetadata());
        for (View row : mMetadataRows) {
            row.setVisibility(GONE);
        }
        mPackList.setVisibility(VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        // IconDatabase.getByComponent already falls back to the global pack, so the entry whose
        // value equals it is the effective one.
        addPackRow(inflater, getContext().getString(R.string.icon_pack_default_label),
                IconDatabase.VALUE_DEFAULT, IconDatabase.VALUE_DEFAULT.equals(selected));
        for (Map.Entry<String, CharSequence> e : packs) {
            addPackRow(inflater, e.getValue(), e.getKey(), e.getKey().equals(selected));
        }
    }

    private void showMetadata() {
        mShowingPackChooser = false;
        mPackList.setVisibility(GONE);
        mTitle.setOnClickListener(null);
        mTitle.setClickable(false);
        mTitle.setText(mItemInfo.title == null || mItemInfo.title.length() == 0
                ? getContext().getString(R.string.app_info_title) : mItemInfo.title);
        for (View row : mMetadataRows) {
            row.setVisibility(VISIBLE);
        }
        refreshIconRows();
    }

    @Override
    public void onBackInvoked() {
        if (mShowingPackChooser) {
            showMetadata();
        } else {
            super.onBackInvoked();
        }
    }

    private void addPackRow(LayoutInflater inflater, CharSequence label, String value,
            boolean checked) {
        View row = inflater.inflate(R.layout.app_info_row, mPackList, false);
        ImageView icon = row.findViewById(R.id.app_info_row_icon);
        if (checked) {
            icon.setImageResource(R.drawable.ic_app_info_icon_pack);
        } else {
            icon.setVisibility(INVISIBLE);
        }
        ((TextView) row.findViewById(R.id.app_info_row_title)).setText(label);
        row.setOnClickListener(v -> applyPack(value));
        row.setSelected(checked);
        mPackList.addView(row);
    }

    /** Mirrors the A16 chooser: choosing the global pack clears the per-app override. */
    private void applyPack(String value) {
        Context context = getContext();
        if (value.equals(IconDatabase.getGlobal(context))) {
            IconDatabase.resetForComponent(context, mKey);
        } else {
            IconDatabase.setForComponent(context, mKey, value);
        }
        AppReloader.get(context).reload(mKey);
        close(true);
    }

    private void openSystemInfo() {
        final Context context = getContext();
        final ItemInfo info = mItemInfo;
        final Rect bounds = mSourceBounds;
        close(true);
        PackageManagerHelper.startDetailsActivityForInfo(context, info, bounds,
                ActivityOptions.makeBasic().toBundle());
    }

    private void startExternal(Intent intent) {
        try {
            mActivityContext.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Installer without a market handler, nothing to open.
        }
    }

    private void applySheetBlur() {
        Drawable surface = mContent.getBackground();
        if (surface instanceof GradientDrawable) {
            GradientDrawable gd = (GradientDrawable) surface.mutate();
            if (gd.getColor() != null) {
                gd.setColor(mBlurBackgroundHelper.getPopupBlurSurfaceColor(
                        gd.getColor().getDefaultColor()));
            }
            mContent.setBackground(gd);
        }
        mBlurBackgroundHelper.applyPopupBlurBackground(mContent);
    }

    private void animateOpenSelf() {
        if (mIsOpen || mOpenCloseAnimation.getAnimationPlayer().isRunning()) {
            return;
        }
        mIsOpen = true;
        setUpDefaultOpenAnimation().start();
    }

    @Override
    protected void handleClose(boolean animate) {
        mShowingPackChooser = false;
        handleClose(animate, DEFAULT_CLOSE_DURATION);
    }

    @Override
    protected boolean isOfType(@AbstractFloatingView.FloatingViewType int type) {
        return (type & AbstractFloatingView.TYPE_APP_INFO_BOTTOM_SHEET) != 0;
    }

    @Override
    protected Pair<View, String> getAccessibilityTarget() {
        return Pair.create(mTitle, getContext().getString(R.string.app_info_title));
    }
}
