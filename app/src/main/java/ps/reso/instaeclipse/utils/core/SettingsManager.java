package ps.reso.instaeclipse.utils.core;

import android.content.Context;
import android.content.SharedPreferences;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureManager;

public class SettingsManager {
    private static final String PREF_NAME = "instaeclipse_prefs";
    private static SharedPreferences prefs;

    /**
     * Writable prefs for the InstaEclipse app only. When hooks run inside Instagram,
     * {@code context.getSharedPreferences} would read/write the host app's data dir, so we only
     * cache prefs from our own package here.
     */
    public static void init(Context context) {
        if (prefs != null) {
            return;
        }
        if (CommonUtils.MY_PACKAGE_NAME.equals(context.getPackageName())) {
            prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    /**
     * Opens the module's preferences while running inside another app (e.g. Instagram under Xposed).
     * Uses reflection so the InstaEclipse APK does not need to embed the Xposed API at runtime.
     */
    private static SharedPreferences readablePrefsForLoad(Context context) {
        if (CommonUtils.MY_PACKAGE_NAME.equals(context.getPackageName())) {
            if (prefs == null) {
                init(context);
            }
            return prefs;
        }
        try {
            Class<?> xspClass = Class.forName("de.robv.android.xposed.XSharedPreferences");
            Object xp = xspClass.getConstructor(String.class, String.class)
                    .newInstance(CommonUtils.MY_PACKAGE_NAME, PREF_NAME);
            xspClass.getMethod("reload").invoke(xp);
            return (SharedPreferences) xp;
        } catch (Throwable ignored) {
        }
        try {
            Context moduleCtx = context.createPackageContext(
                    CommonUtils.MY_PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            return moduleCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
        }
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static void ensureWritablePrefsForSave() {
        if (prefs != null) {
            return;
        }
        try {
            Class<?> helper = Class.forName("de.robv.android.xposed.AndroidAppHelper");
            Context app = (Context) helper.getMethod("currentApplication").invoke(null);
            Context moduleCtx = app.createPackageContext(
                    CommonUtils.MY_PACKAGE_NAME,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            prefs = moduleCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
        }
    }

    public static void saveAllFlags() {
        ensureWritablePrefsForSave();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();

        editor.putBoolean("isDevEnabled", FeatureFlags.isDevEnabled);

        // Ghost Mode
        editor.putBoolean("isGhostModeEnabled", FeatureFlags.isGhostModeEnabled);
        editor.putBoolean("isGhostSeen", FeatureFlags.isGhostSeen);
        editor.putBoolean("isGhostTyping", FeatureFlags.isGhostTyping);
        editor.putBoolean("isGhostScreenshot", FeatureFlags.isGhostScreenshot);
        editor.putBoolean("isGhostViewOnce", FeatureFlags.isGhostViewOnce);
        editor.putBoolean("isGhostStory", FeatureFlags.isGhostStory);
        editor.putBoolean("isGhostLive", FeatureFlags.isGhostLive);

        // Quick Toggles
        editor.putBoolean("quickToggleSeen", FeatureFlags.quickToggleSeen);
        editor.putBoolean("quickToggleTyping", FeatureFlags.quickToggleTyping);
        editor.putBoolean("quickToggleScreenshot", FeatureFlags.quickToggleScreenshot);
        editor.putBoolean("quickToggleViewOnce", FeatureFlags.quickToggleViewOnce);
        editor.putBoolean("quickToggleStory", FeatureFlags.quickToggleStory);
        editor.putBoolean("quickToggleLive", FeatureFlags.quickToggleLive);

        // Distraction Free
        editor.putBoolean("isExtremeMode", FeatureFlags.isExtremeMode);
        editor.putBoolean("isDistractionFree", FeatureFlags.isDistractionFree);
        editor.putBoolean("disableStories", FeatureFlags.disableStories);
        editor.putBoolean("disableFeed", FeatureFlags.disableFeed);
        editor.putBoolean("disableReels", FeatureFlags.disableReels);
        editor.putBoolean("disableReelsExceptDM", FeatureFlags.disableReelsExceptDM);
        editor.putBoolean("disableExplore", FeatureFlags.disableExplore);
        editor.putBoolean("disableComments", FeatureFlags.disableComments);

        // Ads
        editor.putBoolean("isAdBlockEnabled", FeatureFlags.isAdBlockEnabled);
        editor.putBoolean("isAnalyticsBlocked", FeatureFlags.isAnalyticsBlocked);
        editor.putBoolean("disableTrackingLinks", FeatureFlags.disableTrackingLinks);

        // Misc
        editor.putBoolean("isMiscEnabled", FeatureFlags.isMiscEnabled);
        editor.putBoolean("disableStoryFlipping", FeatureFlags.disableStoryFlipping);
        editor.putBoolean("disableVideoAutoPlay", FeatureFlags.disableVideoAutoPlay);
        editor.putBoolean("disableRepost", FeatureFlags.disableRepost);
        editor.putBoolean("enableMediaDownload", FeatureFlags.enableMediaDownload);
        editor.putBoolean("showFollowerToast", FeatureFlags.showFollowerToast);
        editor.putBoolean("showFeatureToasts", FeatureFlags.showFeatureToasts);

        editor.apply();

        FeatureManager.refreshFeatureStatus();
    }

    public static void loadAllFlags(Context context) {
        SharedPreferences sp = readablePrefsForLoad(context);

        FeatureFlags.isDevEnabled = sp.getBoolean("isDevEnabled", false);

        // Ghost Mode
        FeatureFlags.isGhostModeEnabled = sp.getBoolean("isGhostModeEnabled", false);
        FeatureFlags.isGhostSeen = sp.getBoolean("isGhostSeen", false);
        FeatureFlags.isGhostTyping = sp.getBoolean("isGhostTyping", false);
        FeatureFlags.isGhostScreenshot = sp.getBoolean("isGhostScreenshot", false);
        FeatureFlags.isGhostViewOnce = sp.getBoolean("isGhostViewOnce", false);
        FeatureFlags.isGhostStory = sp.getBoolean("isGhostStory", false);
        FeatureFlags.isGhostLive = sp.getBoolean("isGhostLive", false);

        // Quick Toggles
        FeatureFlags.quickToggleSeen = sp.getBoolean("quickToggleSeen", false);
        FeatureFlags.quickToggleTyping = sp.getBoolean("quickToggleTyping", false);
        FeatureFlags.quickToggleScreenshot = sp.getBoolean("quickToggleScreenshot", false);
        FeatureFlags.quickToggleViewOnce = sp.getBoolean("quickToggleViewOnce", false);
        FeatureFlags.quickToggleStory = sp.getBoolean("quickToggleStory", false);
        FeatureFlags.quickToggleLive = sp.getBoolean("quickToggleLive", false);

        // Distraction Free
        FeatureFlags.isExtremeMode = sp.getBoolean("isExtremeMode", false);
        FeatureFlags.isDistractionFree = sp.getBoolean("isDistractionFree", false);
        FeatureFlags.disableStories = sp.getBoolean("disableStories", false);
        FeatureFlags.disableFeed = sp.getBoolean("disableFeed", false);
        FeatureFlags.disableReels = sp.getBoolean("disableReels", false);
        FeatureFlags.disableReelsExceptDM = sp.getBoolean("disableReelsExceptDM", false);
        FeatureFlags.disableExplore = sp.getBoolean("disableExplore", false);
        FeatureFlags.disableComments = sp.getBoolean("disableComments", false);

        // Ads
        FeatureFlags.isAdBlockEnabled = sp.getBoolean("isAdBlockEnabled", false);
        FeatureFlags.isAnalyticsBlocked = sp.getBoolean("isAnalyticsBlocked", false);
        FeatureFlags.disableTrackingLinks = sp.getBoolean("disableTrackingLinks", false);

        // Misc
        FeatureFlags.isMiscEnabled = sp.getBoolean("isMiscEnabled", false);
        FeatureFlags.disableStoryFlipping = sp.getBoolean("disableStoryFlipping", false);
        FeatureFlags.disableVideoAutoPlay = sp.getBoolean("disableVideoAutoPlay", false);
        FeatureFlags.disableRepost = sp.getBoolean("disableRepost", false);
        FeatureFlags.enableMediaDownload = sp.getBoolean("enableMediaDownload", false);
        FeatureFlags.showFollowerToast = sp.getBoolean("showFollowerToast", false);
        FeatureFlags.showFeatureToasts = sp.getBoolean("showFeatureToasts", false);

        FeatureManager.refreshFeatureStatus();
    }
}
