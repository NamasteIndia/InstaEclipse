package ps.reso.instaeclipse.utils.feature;

public class FeatureFlags {

    // Dev Options
    public static boolean isDevEnabled = false;
    public static boolean isImportingConfig = false;
    public static boolean isExportingConfig = false;

    // Ghost Mode
    public static boolean isGhostModeEnabled = false;
    public static boolean isGhostSeen = false;
    public static boolean isGhostTyping = false;
    public static boolean isGhostScreenshot = false;
    public static boolean isGhostViewOnce = false;
    public static boolean isGhostStory = false;
    public static boolean isGhostLive = false;

    // Which ghost mode features the quick toggle will control
    public static boolean quickToggleSeen = false;
    public static boolean quickToggleTyping = false;
    public static boolean quickToggleScreenshot = false;
    public static boolean quickToggleViewOnce = false;
    public static boolean quickToggleStory = false;
    public static boolean quickToggleLive = false;


    // Distraction Free
    public static boolean isExtremeMode = false; // Extreme Mode
    public static boolean isDistractionFree = false;
    public static boolean disableStories = false;
    public static boolean disableFeed = false;
    public static boolean disableReels = false;
    public static boolean disableReelsExceptDM = false;
    public static boolean disableExplore = false;
    public static boolean disableComments = false;

    // Ads and Analytics
    public static boolean isAdBlockEnabled = false;
    public static boolean isAnalyticsBlocked = false;
    public static boolean disableTrackingLinks = false;

    // Misc Options
    public static boolean isMiscEnabled = false;
    public static boolean disableStoryFlipping = false;
    public static boolean disableVideoAutoPlay = false;
    public static boolean showFollowerToast = false;
    public static boolean showFeatureToasts = false;
    public static boolean disableRepost = false;
    public static boolean enableMediaDownload = false;


}
