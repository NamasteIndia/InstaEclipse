package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.view.View;

import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

/**
 * MediaDownloadButtonHook
 *
 * The download button is now enabled by hooking Instagram's native download
 * eligibility gate in DevOptionsEnable.java:
 *
 *   LX/515->A0A(UserSession, Media) -> Z
 *   Found via DexKit string "is_third_party_downloads_eligible"
 *
 * When this returns true, Instagram's own menu builder (X/6Dd, X/6Db) adds
 * MediaOption$Option.DOWNLOAD to the menu list automatically.
 * No view injection is needed.
 *
 * The actual download action is handled by Instagram's own built-in download
 * handler which triggers when the user taps the Download row.
 */
public class MediaDownloadButtonHook {

    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        FeatureStatusTracker.setHooked("MediaDownload");
    }

    // Kept for backward compat — no longer used
    public static void hookWithDexKit(
            org.luckypray.dexkit.DexKitBridge bridge,
            ClassLoader cl) {}

    public static void attachButtonIfNeeded(Activity activity) {}
    public static void ensureActivityObserver(Activity activity) {}
    public static void injectIntoBottomSheet(Activity activity) {}
    public static void injectIntoSheetView(View sheetRoot, Activity activity) {}
}