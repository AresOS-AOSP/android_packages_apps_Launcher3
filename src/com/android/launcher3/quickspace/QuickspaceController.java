/*
 * Copyright (C) 2021-2026 crDroid Android Project
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
package com.android.launcher3.quickspace;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.View.OnClickListener;

import com.android.internal.util.crdroid.OmniJawsClient;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.util.MediaSessionManagerHelper;

import io.chaldeaprjkt.seraphixgoogle.DataProviderListener;
import io.chaldeaprjkt.seraphixgoogle.SeraphixDataProvider;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class QuickspaceController implements OmniJawsClient.OmniJawsObserver,
        MediaSessionManagerHelper.MediaMetadataListener {

    private static final String TAG = "Launcher3:QuickspaceController";

    private final List<WeakReference<OnDataListener>> mListeners =
        Collections.synchronizedList(new ArrayList<>());
    private final Context mAppContext;
    private final Map<String, Integer> mConditionMap;
    private QuickEventsController mEventsController;
    private OmniJawsClient mWeatherClient;
    private volatile OmniJawsClient.WeatherInfo mWeatherInfo;
    private volatile Drawable mConditionImage;
    private volatile boolean mOmniJawsEnabled;
    private boolean mOmniRegistered = false;
    private boolean mMediaRegistered = false;

    private static final long PSA_UPDATE_DELAY_MS = 3 * 60 * 1000;

    private final Handler mHandler = MAIN_EXECUTOR.getHandler();
    private final Handler mBgHandler = UI_HELPER_EXECUTOR.getHandler();

    private enum WeatherProvider { OMNIJAWS, SERAPHIX }
    private WeatherProvider mProvider;

    private SeraphixDataProvider mSeraphix;
    private String mSeraphixText;
    private Icon mSeraphixIcon;
    private int mLastBmpHash;
    private boolean mDestroyed = false;

    private final MediaSessionManagerHelper mMediaSessionHelper;

    private final Runnable mOnDataUpdatedRunnable = new Runnable() {
            @Override
            public void run() {
                if (mDestroyed) return;
                for (OnDataListener listener : getListenersSnapshot()) {
                    try {
                        listener.onDataUpdated();
                    } catch (Exception e) {
                        Log.e(TAG, "Error notifying listener", e);
                    }
                }
            }
        };

    private final Runnable mWeatherRunnable = new Runnable() {
            @Override
            public void run() {
                if (mDestroyed) return;
                try {
                    final OmniJawsClient client = mWeatherClient;
                    if (client == null) return;
                    final boolean enabled = client.isOmniJawsEnabled(mAppContext);
                    mOmniJawsEnabled = enabled;
                    if (!enabled) {
                        mWeatherInfo = null;
                        mConditionImage = null;
                        notifyListeners();
                        return;
                    }
                    client.queryWeather(mAppContext);
                    final OmniJawsClient.WeatherInfo info = client.getWeatherInfo();
                    mWeatherInfo = info;
                    if (info != null) {
                        mConditionImage = client.getWeatherConditionImage(mAppContext, info.conditionCode);
                    } else {
                        mConditionImage = null;
                    }
                    notifyListeners();
                } catch (Exception e) {
                    Log.w(TAG, "weather update failed", e);
                }
            }
        };

    private Runnable mPsaRunnable = new Runnable() {
            @Override
            public void run() {
                if (mDestroyed) return;
                mHandler.removeCallbacks(this);
                if (mEventsController == null) return;
                mEventsController.updatePsonality();
                mHandler.postDelayed(this, PSA_UPDATE_DELAY_MS);
                notifyListeners();
            }
        };

    public interface OnDataListener {
        void onDataUpdated();
    }

    public QuickspaceController(Context context) {
        mAppContext = context.getApplicationContext();
        mConditionMap = initializeConditionMap();
        mEventsController = new QuickEventsController(mAppContext);
        mMediaSessionHelper = MediaSessionManagerHelper.Companion.getInstance(mAppContext);
    }

    private void decideWeatherProvider() {
        if (mDestroyed) return;
        String pref = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_PROVIDER.get(mAppContext);
        WeatherProvider target = WeatherProvider.SERAPHIX;
        if ("seraphix".equals(pref)) {
            target = WeatherProvider.SERAPHIX;
        } else if ("auto".equals(pref)) {
            // Try seraphix first; if bind fails, fall back to OmniJaws
            if (tryBindSeraphix(true)) {
                target = WeatherProvider.SERAPHIX;
            } else {
                target = WeatherProvider.OMNIJAWS;
            }
        } else if ("omnijaws".equals(pref)) {
            target = WeatherProvider.OMNIJAWS;
        }
        switchProvider(target);
    }

    private void switchProvider(WeatherProvider target) {
        if (mDestroyed) return;
        if (mProvider == target) {
            // Ensure the chosen provider is actually set up
            if (target == WeatherProvider.SERAPHIX) {
                tryBindSeraphix(false);
            } else {
                addOmniJawsIfEnabled();
            }
            return;
        }

        // Tear down old
        if (mProvider == WeatherProvider.SERAPHIX) {
            unbindSeraphix();
        } else if (mProvider == WeatherProvider.OMNIJAWS) {
            removeOmniIfRegistered();
        }

        mProvider = target;

        // Bring up new
        if (mProvider == WeatherProvider.SERAPHIX) {
            if (!tryBindSeraphix(false)) {
                // fallback if bind fails at runtime
                mProvider = WeatherProvider.OMNIJAWS;
                addOmniJawsIfEnabled();
            }
        } else {
            addOmniJawsIfEnabled();
        }

        notifyListeners();
    }

    private void addOmniJawsIfEnabled() {
        if (mDestroyed || !LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(mAppContext)) return;
        try {
            if (mWeatherClient == null) mWeatherClient = OmniJawsClient.get();
            if (!mOmniRegistered && mWeatherClient != null) {
                mWeatherClient.addObserver(mAppContext, this);
                mOmniRegistered = true;
            }
            queryAndUpdateWeather();
        } catch (Exception e) {
            Log.e(TAG, "Error adding OmniJaws observer", e);
        }
    }

    private boolean tryBindSeraphix(boolean silent) {
        if (mDestroyed) return false;
        try {
            if (mSeraphix == null) {
                mSeraphix = new SeraphixDataProvider(mAppContext, 1022,
                    LauncherPrefs.SERAPHIX_HOLDER_ID.get(mAppContext));
                mSeraphix.setOnDataUpdated(mSeraphixListener);
            }
            mSeraphix.bind(id -> {
                if (!mDestroyed) {
                    LauncherPrefs.get(mAppContext).put(LauncherPrefs.SERAPHIX_HOLDER_ID, id);
                }
            });
            return true;
        } catch (Throwable t) {
            if (!silent) Log.w(TAG, "Seraphix bind failed, falling back", t);
            unbindSeraphix();
            return false;
        }
    }

    private void unbindSeraphix() {
        try {
            if (mSeraphix != null) {
                mSeraphix.setOnDataUpdated(null);
                mSeraphix.unbind();
            }
        } catch (Throwable ignored) {}
        mSeraphix = null;
        mSeraphixText = null;
        mSeraphixIcon = null;
    }

    private final DataProviderListener mSeraphixListener = card -> {
        if (mDestroyed) return;
        try {
            updateWeatherData(card.getText(), card.getImage());
        } catch (Exception e) {
            Log.e(TAG, "Seraphix update error", e);
        }
    };

    private void updateWeatherData(String text, Bitmap image) {
        if (mDestroyed) return;
        int hash = (image == null) ? 0 : image.getGenerationId();
        if (TextUtils.equals(text, mSeraphixText) && hash == mLastBmpHash) {
            return;
        }
        mLastBmpHash = hash;
        mSeraphixText = text;
        mSeraphixIcon = image == null ? null : Icon.createWithBitmap(image);
        notifyListeners();
    }

    public void addListener(OnDataListener listener) {
        if (listener == null || mDestroyed) return;
        boolean shouldStart = false;
        synchronized (mListeners) {
            pruneListenersLocked();
            boolean alreadyRegistered = false;
            for (WeakReference<OnDataListener> reference : mListeners) {
                if (reference.get() == listener) {
                    alreadyRegistered = true;
                    break;
                }
            }
            shouldStart = mListeners.isEmpty();
            if (!alreadyRegistered) {
                mListeners.add(new WeakReference<>(listener));
            }
        }
        if (shouldStart) {
            decideWeatherProvider();
            registerMediaController();
            if (mEventsController != null) {
                mEventsController.initQuickEvents();
            }
            updatePSAevent();
        }
        try {
            listener.onDataUpdated();
        } catch (Exception e) {
            Log.e(TAG, "Error in initial listener notification", e);
        }
    }

    private void removeOmniIfRegistered() {
        try {
            if (mOmniRegistered && mWeatherClient != null) {
                mWeatherClient.removeObserver(mAppContext, this);
                mOmniRegistered = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing OmniJaws observer", e);
        }
        mBgHandler.removeCallbacks(mWeatherRunnable);
        mWeatherClient = null;
        mWeatherInfo = null;
        mConditionImage = null;
        mOmniJawsEnabled = false;
    }

    public void removeListener(OnDataListener listener) {
        if (listener == null) return;
        boolean shouldCleanup = false;
        synchronized (mListeners) {
            mListeners.removeIf(reference -> {
                OnDataListener current = reference.get();
                return current == null || current == listener;
            });
            shouldCleanup = mListeners.isEmpty() && !mDestroyed;
        }
        if (shouldCleanup) {
            cleanupWhenEmpty();
        }
    }

    private void cleanupWhenEmpty() {
        if (mProvider == WeatherProvider.OMNIJAWS) {
            removeOmniIfRegistered();
        } else {
            unbindSeraphix();
        }
        unregisterMediaController();
        mHandler.removeCallbacks(mPsaRunnable);
        mBgHandler.removeCallbacks(mWeatherRunnable);
        mHandler.removeCallbacks(mOnDataUpdatedRunnable);
    }

    private void pruneListenersLocked() {
        mListeners.removeIf(reference -> reference.get() == null);
    }

    private List<OnDataListener> getListenersSnapshot() {
        List<OnDataListener> listeners = new ArrayList<>();
        synchronized (mListeners) {
            pruneListenersLocked();
            for (WeakReference<OnDataListener> reference : mListeners) {
                OnDataListener listener = reference.get();
                if (listener != null) {
                    listeners.add(listener);
                }
            }
        }
        return listeners;
    }

    public boolean isQuickEvent() {
        return !mDestroyed && mEventsController != null && mEventsController.isQuickEvent();
    }

    public QuickEventsController getEventController() {
        return mDestroyed ? null : mEventsController;
    }

    public boolean isWeatherAvailable() {
        if (mDestroyed || !LauncherPrefs.SHOW_QUICKSPACE_WEATHER.get(mAppContext)) return false;
        if (mProvider == WeatherProvider.SERAPHIX) {
            return !TextUtils.isEmpty(mSeraphixText) || mSeraphixIcon != null;
        } else {
            return mWeatherClient != null && mOmniJawsEnabled;
        }
    }

    public Drawable getWeatherIcon() {
        if (mDestroyed) return null;
        if (mProvider == WeatherProvider.SERAPHIX) {
            return mSeraphixIcon != null ? mSeraphixIcon.loadDrawable(mAppContext) : null;
        } else {
            return mConditionImage;
        }
    }

    public String getWeatherTemp() {
        if (mDestroyed) return null;
        if (mProvider == WeatherProvider.SERAPHIX) {
            return mSeraphixText;
        } else {
            final OmniJawsClient.WeatherInfo info = mWeatherInfo;
            if (info == null) return null;

            boolean shouldShowCity = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_CITY.get(mAppContext);
            boolean showWeatherText = LauncherPrefs.SHOW_QUICKSPACE_WEATHER_TEXT.get(mAppContext);

            StringBuilder weatherTemp = new StringBuilder();
            if (shouldShowCity) {
                weatherTemp.append(info.city).append(" ");
            }
            weatherTemp.append(info.temp)
                       .append(info.tempUnits);

            if (showWeatherText) {
                weatherTemp.append(" • ").append(getConditionText(info.condition));
            }

            return weatherTemp.toString();
        }
    }

    private String getConditionText(String input) {
        if (input == null || input.isEmpty()) return "";

        Locale locale = mAppContext.getResources().getConfiguration().getLocales().get(0);
        boolean isEnglish = locale.getLanguage().toLowerCase(Locale.ROOT).startsWith("en");
        String lowerCaseInput = input.toLowerCase(Locale.ROOT);

        if (!isEnglish) {
            for (Map.Entry<String, Integer> entry : mConditionMap.entrySet()) {
                if (lowerCaseInput.contains(entry.getKey())) {
                    return mAppContext.getResources().getString(entry.getValue());
                }
            }
        }
        return capitalizeWords(lowerCaseInput);
    }

    private Map<String, Integer> initializeConditionMap() {
        Map<String, Integer> map = new HashMap<>();
        map.put("clouds", R.string.quick_event_weather_clouds);
        map.put("rain", R.string.quick_event_weather_rain);
        map.put("clear", R.string.quick_event_weather_clear);
        map.put("storm", R.string.quick_event_weather_storm);
        map.put("snow", R.string.quick_event_weather_snow);
        map.put("wind", R.string.quick_event_weather_wind);
        map.put("mist", R.string.quick_event_weather_mist);
        return map;
    }

    private String capitalizeWords(String input) {
        if (input == null || input.isEmpty()) return input;

        String[] words = input.split("\\s+");
        StringBuilder capitalized = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                capitalized.append(Character.toUpperCase(word.charAt(0)))
                           .append(word.substring(1).toLowerCase())
                           .append(" ");
            }
        }
        return capitalized.toString().trim();
    }

    public void onPause() {
        if (mDestroyed) return;
        unregisterMediaController();
        mHandler.removeCallbacks(mPsaRunnable);
        mBgHandler.removeCallbacks(mWeatherRunnable);
        mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        if (mProvider == WeatherProvider.SERAPHIX && mSeraphix != null) {
            mSeraphix.pauseListening();
        }
    }

    public void onResume() {
        if (mDestroyed) return;
        registerMediaController();
        updateMediaController();
        decideWeatherProvider();
        if (mProvider == WeatherProvider.SERAPHIX && mSeraphix != null) {
            mSeraphix.resumeListening();
        }
        updatePSAevent();
        notifyListeners();
    }

    public void onDestroy() {
        if (mDestroyed) return;
        mDestroyed = true;
        unregisterMediaController();
        mHandler.removeCallbacks(mPsaRunnable);
        mBgHandler.removeCallbacks(mWeatherRunnable);
        mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        if (mProvider == WeatherProvider.SERAPHIX) {
            unbindSeraphix();
        } else {
            removeOmniIfRegistered();
        }
        if (mEventsController != null) {
            mEventsController.destroy();
            mEventsController = null;
        }
        synchronized (mListeners) {
            mListeners.clear();
        }
        mWeatherInfo = null;
        mConditionImage = null;
        mSeraphixText = null;
        mSeraphixIcon = null;
    }

    @Override
    public void weatherUpdated() {
        if (mDestroyed) return;
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        if (mDestroyed) return;
        Log.d(TAG, "weatherError " + errorReason);
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            notifyListeners();
        }
    }

    @Override
    public void updateSettings() {
        if (mDestroyed) return;
        Log.i(TAG, "updateSettings");
        queryAndUpdateWeather();
    }

    private void updatePSAevent() {
        if (mDestroyed) return;
        mHandler.removeCallbacks(mPsaRunnable);
        mHandler.post(mPsaRunnable);
    }

    private void queryAndUpdateWeather() {
        if (mDestroyed) return;
        mBgHandler.removeCallbacks(mWeatherRunnable);
        mBgHandler.post(mWeatherRunnable);
    }

    public void notifyListeners() {
        if (mDestroyed) return;
        mHandler.removeCallbacks(mOnDataUpdatedRunnable);
        mHandler.post(mOnDataUpdatedRunnable);
    }

    private void registerMediaController() {
        if (mDestroyed || mMediaRegistered) return;
        mMediaSessionHelper.addMediaMetadataListener(this);
        mMediaRegistered = true;
    }

    private void unregisterMediaController() {
        if (!mMediaRegistered) return;
        mMediaSessionHelper.removeMediaMetadataListener(this);
        mMediaRegistered = false;
    }

    private boolean updateMediaController() {
        if (mDestroyed || mEventsController == null
                || !LauncherPrefs.SHOW_QUICKSPACE_NOWPLAYING.get(mAppContext)) {
            return false;
        }
        MediaMetadata mediaMetadata = mMediaSessionHelper.getCurrentMediaMetadata();
        boolean isPlaying = mMediaSessionHelper.isMediaPlaying();
        String trackArtist = isPlaying && mediaMetadata != null ?
                mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : "";
        String trackTitle = isPlaying && mediaMetadata != null ?
                mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE) : "";
        Drawable mediaIcon = mMediaSessionHelper.getMediaAppIcon();
        OnClickListener launchMediaApp =
                view -> mMediaSessionHelper.launchMediaApp();
        mEventsController.setMediaInfo(trackTitle, trackArtist, isPlaying,
                mediaIcon, launchMediaApp);
        mEventsController.updateQuickEvents();
        return true;
    }

    @Override
    public void onMediaMetadataChanged() {
        if (mDestroyed) return;
        if (updateMediaController()) notifyListeners();
    }

    @Override
    public void onPlaybackStateChanged() {
        if (mDestroyed) return;
        if (updateMediaController()) notifyListeners();
    }
}
