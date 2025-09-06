/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.icons;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.util.crdroid.Utils;
import com.android.launcher3.R;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.dagger.ApplicationContext;
import com.android.launcher3.dagger.LauncherAppSingleton;
import com.android.launcher3.graphics.ShapeDelegate;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.SafeCloseable;

import org.xmlpull.v1.XmlPullParser;

import java.util.Collections;
import java.util.Map;

import javax.inject.Inject;

/**
 * Extension of {@link IconProvider} with support for overriding theme icons
 */
@LauncherAppSingleton
public class LauncherIconProvider extends IconProvider {

    private static final String TAG_ICON = "icon";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_DRAWABLE = "drawable";

    private static final String TAG = "LIconProvider";
    private static final Map<String, ThemeData> DISABLED_MAP = Collections.emptyMap();

    private static final String LAWNICONS_PACKAGE = "app.lawnchair.lawnicons";
    private static final String LAWNICONS_ICON_MAP_XML = "grayscale_icon_map";

    private Map<String, ThemeData> mThemedIconMap;

    private final ApiWrapper mApiWrapper;
    private final ThemeManager mThemeManager;
    private final Context mContext;

    @Inject
    public LauncherIconProvider(
            @ApplicationContext Context context,
            ThemeManager themeManager,
            ApiWrapper apiWrapper) {
        super(context);
        mContext = context;
        mThemeManager = themeManager;
        mApiWrapper = apiWrapper;
        setIconThemeSupported(mThemeManager.isMonoThemeEnabled());
    }

    /**
     * Enables or disables icon theme support
     */
    public void setIconThemeSupported(boolean isSupported) {
        mThemedIconMap = isSupported && FeatureFlags.USE_LOCAL_ICON_OVERRIDES.get()
                ? null : DISABLED_MAP;
    }

    @Override
    protected ThemeData getThemeDataForPackage(String packageName) {
        return getThemedIconMap().get(packageName);
    }

    @Override
    public void updateSystemState() {
        super.updateSystemState();
        mSystemState += "," + mThemeManager.getIconState().toUniqueId()
            + Build.VERSION.INCREMENTAL;
    }

    @Override
    protected String getApplicationInfoHash(@NonNull ApplicationInfo appInfo) {
        return mApiWrapper.getApplicationInfoHash(appInfo);
    }

    @Nullable
    @Override
    protected Drawable loadAppInfoIcon(ApplicationInfo info, Resources resources, int density) {
        // Tries to load the round icon res, if the app defines it as an adaptive icon
        if (mThemeManager.getIconShape() instanceof ShapeDelegate.Circle) {
            int roundIconRes = mApiWrapper.getRoundIconRes(info);
            if (roundIconRes != 0 && roundIconRes != info.icon) {
                try {
                    Drawable d = resources.getDrawableForDensity(roundIconRes, density);
                    if (d instanceof AdaptiveIconDrawable) {
                        return d;
                    }
                } catch (Resources.NotFoundException exc) { }
            }
        }
        return super.loadAppInfoIcon(info, resources, density);
    }

    private Map<String, ThemeData> getThemedIconMap() {
        if (mThemedIconMap != null) {
            return mThemedIconMap;
        }
        ArrayMap<String, ThemeData> map = loadIconMapFromResource(mContext.getResources(), R.xml.grayscale_icon_map);
        if (Utils.isPackageInstalled(mContext, LAWNICONS_PACKAGE)
                && Utils.isPackageEnabled(mContext, LAWNICONS_PACKAGE)) {
            Map<String, ThemeData> m = loadExternalIcons();
            if (m != null) {
                map.putAll(m);
            }
        }
        mThemedIconMap = map;
        return mThemedIconMap;
    }

    private Map<String, ThemeData> loadExternalIcons() {
        try {
            Resources res = mContext.getPackageManager().getResourcesForApplication(LAWNICONS_PACKAGE);
            int resId = res.getIdentifier(LAWNICONS_ICON_MAP_XML, "xml", LAWNICONS_PACKAGE);
            if (resId == 0) {
                return null;
            }
            return loadIconMapFromResource(res, resId);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private ArrayMap<String, ThemeData> loadIconMapFromResource(Resources res, int xmlResId) {
        ArrayMap<String, ThemeData> map = new ArrayMap<>();
        try (XmlResourceParser parser = res.getXml(xmlResId)) {
            final int depth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT);

            while (((type = parser.next()) != XmlPullParser.END_TAG
                    || parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                if (TAG_ICON.equals(parser.getName())) {
                    String pkg = parser.getAttributeValue(null, ATTR_PACKAGE);
                    int iconId = parser.getAttributeResourceValue(null, ATTR_DRAWABLE, 0);
                    if (iconId != 0 && pkg != null && !pkg.isEmpty()) {
                        map.put(pkg, new ThemeData(res, iconId));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to parse icon map", e);
        }
        return map;
    }

    public SafeCloseable registerPackageChangeListener() {
        return new PackageChangeReceiver();
    }

    private class PackageChangeReceiver extends BroadcastReceiver implements SafeCloseable {

        PackageChangeReceiver() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            filter.addDataScheme("package");
            mContext.registerReceiver(this, filter, null, MAIN_EXECUTOR.getHandler());
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
            if (pkg == null) return;
            if ((Intent.ACTION_PACKAGE_ADDED.equals(action) || Intent.ACTION_PACKAGE_REMOVED.equals(action))
                    && LAWNICONS_PACKAGE.equals(pkg)) {
                mThemedIconMap = null;
                if (mThemeManager.isMonoThemeEnabled()) {
                    mThemeManager.setMonoThemeEnabled(false);
                    mThemeManager.setMonoThemeEnabled(true);
                }
            }
        }

        @Override
        public void close() {
            try {
                mContext.unregisterReceiver(this);
                Log.d(TAG, "PackageChangeReceiver closed");
            } catch (Exception ignored) { }
        }
    }
}
