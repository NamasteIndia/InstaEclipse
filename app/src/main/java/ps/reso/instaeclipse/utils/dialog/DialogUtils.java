package ps.reso.instaeclipse.utils.dialog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;

public class DialogUtils {

    private static AlertDialog currentDialog;

    @SuppressLint("UseCompatLoadingForDrawables")
    public static void showEclipseOptionsDialog(Context context) {
        SettingsManager.init(context);
        Context themedContext = new ContextThemeWrapper(context, android.R.style.Theme_Material_Dialog_Alert);

        LinearLayout mainLayout = buildMainMenuLayout(themedContext);
        ScrollView scrollView = new ScrollView(themedContext);
        scrollView.addView(mainLayout);

        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }

        currentDialog = new AlertDialog.Builder(themedContext).setView(scrollView).setTitle(null).setCancelable(true).create();

        Objects.requireNonNull(currentDialog.getWindow()).setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        currentDialog.show();
    }

    public static void showSimpleDialog(Context context, String title, String message) {
        try {
            new AlertDialog.Builder(context).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
        } catch (Exception e) {
            // handle UI crash fallback
        }
    }

    @SuppressLint("SetTextI18n")
    private static LinearLayout buildMainMenuLayout(Context context) {
        LinearLayout mainLayout = new LinearLayout(context);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 40, 40, 20);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#262626"));
        background.setCornerRadius(32);
        mainLayout.setBackground(background);

        // Title
        TextView title = new TextView(context);
        title.setText("InstaEclipse 🌘");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 20);
        mainLayout.addView(title);

        mainLayout.addView(createDivider(context));

        // Now building menu manually

        // 0 - Developer Options => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "🎛 Developer Options", () -> showDevOptions(context)));

        // 1 - Ghost Mode Settings => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "👻 Ghost Mode Settings", () -> showGhostOptions(context)));

        // 2 - Ad/Analytics Block => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "🛡 Ad/Analytics Block", () -> showAdOptions(context)));

        // 3 - Distraction-Free Instagram => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "🧘 Distraction-Free Instagram", () -> showDistractionOptions(context)));

        // 4 - Misc Features => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "⚙ Misc Features", () -> showMiscOptions(context)));

        // 5 - About => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "ℹ️ About", () -> showAboutDialog(context)));

        // 6 - Restart Instagram => OPEN PAGE
        mainLayout.addView(createClickableSection(context, "🔁 Restart App", () -> showRestartSection(context)));

        mainLayout.addView(createDivider(context));

        // Footer Credit
        TextView footer = new TextView(context);
        footer.setText("@reso7200");
        footer.setTextColor(Color.GRAY);
        footer.setTextSize(14);
        footer.setPadding(0, 30, 0, 10);
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        mainLayout.addView(footer);

        // Embedded Close Button
        TextView closeButton = new TextView(context);
        closeButton.setText("❌ Close");
        closeButton.setTextColor(Color.WHITE);
        closeButton.setTextSize(16);
        closeButton.setPadding(20, 30, 20, 30);
        closeButton.setGravity(Gravity.CENTER);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#40FFFFFF")));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        closeButton.setBackground(states);

        closeButton.setOnClickListener(v -> {
            if (currentDialog != null) currentDialog.dismiss();
        });

        mainLayout.addView(createDivider(context)); // Divider above close button
        mainLayout.addView(closeButton);

        SettingsManager.saveAllFlags();

        Activity activity = UIHookManager.getCurrentActivity();
        if (activity != null) {
            GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
        }

        return mainLayout;
    }


    private static void showGhostQuickToggleOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches for customizing what gets toggled
        Switch[] toggleSwitches = new Switch[]{createSwitch(context, "Include Hide Seen", FeatureFlags.quickToggleSeen), createSwitch(context, "Include Hide Typing", FeatureFlags.quickToggleTyping), createSwitch(context, "Include Disable Screenshot Detection", FeatureFlags.quickToggleScreenshot), createSwitch(context, "Include Hide View Once", FeatureFlags.quickToggleViewOnce), createSwitch(context, "Include Hide Story Seen", FeatureFlags.quickToggleStory), createSwitch(context, "Include Hide Live Seen", FeatureFlags.quickToggleLive)};

        // Create Enable/Disable All switch
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch = createSwitch(context, "Enable/Disable All", areAllEnabled(toggleSwitches));

        // Master listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : toggleSwitches) {
                s.setChecked(isChecked);
            }
        });

        // Individual switch listeners (update master switch automatically)
        for (int i = 0; i < toggleSwitches.length; i++) {
            final int index = i;
            toggleSwitches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(toggleSwitches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (Switch s2 : toggleSwitches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update corresponding FeatureFlag instantly
                switch (index) {
                    case 0:
                        FeatureFlags.quickToggleSeen = isChecked;
                        break;
                    case 1:
                        FeatureFlags.quickToggleTyping = isChecked;
                        break;
                    case 2:
                        FeatureFlags.quickToggleScreenshot = isChecked;
                        break;
                    case 3:
                        FeatureFlags.quickToggleViewOnce = isChecked;
                        break;
                    case 4:
                        FeatureFlags.quickToggleStory = isChecked;
                        break;
                    case 5:
                        FeatureFlags.quickToggleLive = isChecked;
                        break;
                }

                // Save immediately
                SettingsManager.saveAllFlags();

                // Update ghost emoji immediately
                Activity activity = UIHookManager.getCurrentActivity();
                if (activity != null) {
                    GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                }
            });
        }


        // Add views to layout
        layout.addView(createDivider(context)); // Divider above
        layout.addView(createEnableAllSwitch(context, enableAllSwitch)); // Styled enable all switch
        layout.addView(createDivider(context)); // Divider below

        for (Switch s : toggleSwitches) {
            layout.addView(s);
        }

        // Show dialog
        showSectionDialog(context, "Customize Quick Toggle 🛠️", layout, () -> {
        });

    }


    private static View createDivider(Context context) {
        View divider = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2);
        params.setMargins(0, 20, 0, 20);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(Color.DKGRAY);
        return divider;
    }

    /**
     * Clears the application's cache and restarts it.
     * Works for any package name this module is running in.
     *
     * @param context The application context.
     */
    private static void restartApp(Context context) {
        try {
            String packageName = context.getPackageName();
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);

            if (intent != null) {
                clearAppCache(context); // Clear cache first
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } else {
                Toast.makeText(context, "Could not find the app to restart.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            String packageName = context.getPackageName();
            XposedBridge.log("InstaEclipse: Restart failed for " + packageName + " - " + e.getMessage());
            Toast.makeText(context, "Restart failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Clears the cache directory for the current application.
     *
     * @param context The application context.
     */
    private static void clearAppCache(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.isDirectory()) {
                deleteRecursive(cacheDir);
                XposedBridge.log("InstaEclipse: Cache cleared for " + context.getPackageName());
            } else {
                XposedBridge.log("InstaEclipse: Cache directory not found for " + context.getPackageName());
            }
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse: Failed to clear cache for " + context.getPackageName() + " - " + e.getMessage());
        }
    }

    /**
     * Recursively deletes a file or directory.
     *
     * @param fileOrDirectory The file or directory to delete.
     */
    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        // A direct result for a file or an empty directory
        fileOrDirectory.delete();
    }


    // ==== SECTIONS ====

    @SuppressLint("SetTextI18n")
    private static void showDevOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Developer Mode Switch
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch devModeSwitch = createSwitch(context, "Enable Developer Mode", FeatureFlags.isDevEnabled);
        devModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FeatureFlags.isDevEnabled = isChecked;
            SettingsManager.saveAllFlags();
        });

        layout.addView(devModeSwitch);
        layout.addView(createDivider(context));

        // 📥 Import Dev Config Button
        Button importButton = new Button(context);
        importButton.setText("📥 Import Dev Config");
        importButton.setOnClickListener(v -> {
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                FeatureFlags.isImportingConfig = true;

                Intent importIntent = new Intent();
                importIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
                importIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    instagramActivity.startActivity(importIntent);
                } catch (Exception e) {
                    XposedBridge.log("InstaEclipse | ❌ Failed to start JsonImportActivity: " + e.getMessage());
                    showSimpleDialog(context, "Error", "Unable to open InstaEclipse UI.");
                }

            } else {
                showSimpleDialog(context, "Error", "Instagram is not open or ready.");
            }
        });

        layout.addView(importButton);


        // 📤 Export Dev Config Button
        Button exportButton = new Button(context);
        exportButton.setText("📤 Export Dev Config");
        exportButton.setOnClickListener(v -> {
            FeatureFlags.isExportingConfig = true;
            Activity instagramActivity = UIHookManager.getCurrentActivity();
            if (instagramActivity != null && !instagramActivity.isFinishing()) {
                ConfigManager.exportCurrentDevConfig(instagramActivity);

                // Launch InstaEclipse export screen
                Intent exportIntent = new Intent();
                exportIntent.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                exportIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                try {
                    instagramActivity.startActivity(exportIntent);
                } catch (Exception e) {
                    showSimpleDialog(context, "Error", "Unable to open InstaEclipse UI.");
                }

            } else {
                showSimpleDialog(context, "Error", "Instagram is not open or ready.");
            }
        });

        layout.addView(exportButton);

        // Save current dev mode flag when dialog is closed
        showSectionDialog(context, "Developer Options 🎛", layout, SettingsManager::saveAllFlags);
    }

    private static void showGhostOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        Switch[] switches = new Switch[]{createSwitch(context, "Hide Seen", FeatureFlags.isGhostSeen), createSwitch(context, "Hide Typing", FeatureFlags.isGhostTyping), createSwitch(context, "Disable Screenshot Detection", FeatureFlags.isGhostScreenshot), createSwitch(context, "Hide View Once", FeatureFlags.isGhostViewOnce), createSwitch(context, "Hide Story Seen", FeatureFlags.isGhostStory), createSwitch(context, "Hide Live Seen", FeatureFlags.isGhostLive)};

        layout.addView(createClickableSection(context, "🛠 Customize Quick Toggle", () -> showGhostQuickToggleOptions(context)));

        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch = createSwitch(context, "Enable/Disable All", areAllEnabled(switches));

        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : switches) {
                s.setChecked(isChecked);
            }
        });

        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (Switch s2 : switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Set FeatureFlag immediately
                switch (index) {
                    case 0:
                        FeatureFlags.isGhostSeen = isChecked;
                        break;
                    case 1:
                        FeatureFlags.isGhostTyping = isChecked;
                        break;
                    case 2:
                        FeatureFlags.isGhostScreenshot = isChecked;
                        break;
                    case 3:
                        FeatureFlags.isGhostViewOnce = isChecked;
                        break;
                    case 4:
                        FeatureFlags.isGhostStory = isChecked;
                        break;
                    case 5:
                        FeatureFlags.isGhostLive = isChecked;
                        break;
                }

                // Save immediately
                SettingsManager.saveAllFlags();

                // Update ghost emoji immediately
                Activity activity = UIHookManager.getCurrentActivity();
                if (activity != null) {
                    GhostEmojiManager.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                }
            });
        }

        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (Switch s : switches) {
            layout.addView(s);
        }

        showSectionDialog(context, "Ghost Mode 👻", layout, () -> {
            // No need to set FeatureFlags here anymore because handled instantly
        });
    }


    private static void showAdOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create switches
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch adBlock = createSwitch(context, "Block Ads", FeatureFlags.isAdBlockEnabled);

        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch analytics = createSwitch(context, "Block Analytics", FeatureFlags.isAnalyticsBlocked);

        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch trackingLinks = createSwitch(context, "Disable Tracking Links", FeatureFlags.disableTrackingLinks);

        Switch[] switches = new Switch[]{adBlock, analytics, trackingLinks};

        // Create Enable/Disable All switch
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch = createSwitch(context, "Enable/Disable All", areAllEnabled(switches));

        // Master listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : switches) {
                s.setChecked(isChecked);
            }
        });

        // Individual switch listeners
        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (Switch s2 : switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update FeatureFlag immediately
                if (index == 0) FeatureFlags.isAdBlockEnabled = isChecked;
                if (index == 1) FeatureFlags.isAnalyticsBlocked = isChecked;
                if (index == 2) FeatureFlags.disableTrackingLinks = isChecked;

                // Save immediately
                SettingsManager.saveAllFlags();
            });
        }


        // Add views
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (Switch s : switches) {
            layout.addView(s);
        }

        // Show the dialog
        showSectionDialog(context, "Ad/Analytics Block 🛡️", layout, () -> {
        });
    }


    private static void showDistractionOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Child switches
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch extremeModeSwitch = createSwitch(context, "Extreme Mode 🔒 (Irreversible until reinstall)", FeatureFlags.isExtremeMode);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableStoriesSwitch = createSwitch(context, "Disable Stories", FeatureFlags.disableStories);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableFeedSwitch = createSwitch(context, "Disable Feed", FeatureFlags.disableFeed);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableReelsSwitch = createSwitch(context, "Disable Reels", FeatureFlags.disableReels);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch onlyInDMSwitch = createSwitch(context, "Disable Reels Except in DMs", FeatureFlags.disableReelsExceptDM);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableExploreSwitch = createSwitch(context, "Disable Explore", FeatureFlags.disableExplore);
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableCommentsSwitch = createSwitch(context, "Disable Comments", FeatureFlags.disableComments);

        Switch[] switches = new Switch[]{disableStoriesSwitch, disableFeedSwitch, disableReelsSwitch, onlyInDMSwitch, disableExploreSwitch, disableCommentsSwitch};


        // Enable/Disable All
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch = createSwitch(context, "Enable/Disable All", areAllEnabled(switches));

        if (FeatureFlags.isExtremeMode) {
            disableAllSwitches(switches, enableAllSwitch, onlyInDMSwitch);
            extremeModeSwitch.setChecked(true);
            extremeModeSwitch.setEnabled(false);
        }

        extremeModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("Activate Extreme Mode?");
                builder.setMessage("Once activated, you cannot disable Distraction-Free Mode until you reinstall the app. Continue?");
                builder.setPositiveButton("Yes", (dialog, which) -> {
                    FeatureFlags.isExtremeMode = true;
                    FeatureFlags.isDistractionFree = true;

                    // Save user’s current selections before freezing them
                    FeatureFlags.disableStories = disableStoriesSwitch.isChecked();
                    FeatureFlags.disableFeed = disableFeedSwitch.isChecked();
                    FeatureFlags.disableReels = disableReelsSwitch.isChecked();
                    FeatureFlags.disableReelsExceptDM = onlyInDMSwitch.isChecked();
                    FeatureFlags.disableExplore = disableExploreSwitch.isChecked();
                    FeatureFlags.disableComments = disableCommentsSwitch.isChecked();
                    SettingsManager.saveAllFlags();

                    // Disable all UI switches to lock them
                    disableAllSwitches(switches, enableAllSwitch, onlyInDMSwitch);
                    extremeModeSwitch.setEnabled(false);
                });
                builder.setNegativeButton("Cancel", (dialog, which) -> extremeModeSwitch.setChecked(false));
                builder.show();
            }
        });


        // Master switch listener
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : switches) {
                s.setChecked(isChecked);
                s.setEnabled(true);
            }
            if (!isChecked) {
                onlyInDMSwitch.setChecked(false);
                onlyInDMSwitch.setEnabled(false);
            }
        });

        // Parent-child logic for Reels
        disableReelsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            onlyInDMSwitch.setEnabled(isChecked);
            if (!isChecked) {
                onlyInDMSwitch.setChecked(false); // turn off child immediately
                onlyInDMSwitch.setEnabled(false);
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            SettingsManager.saveAllFlags();
        });

        // Child logic for "Except in DMs"
        onlyInDMSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !disableReelsSwitch.isChecked()) {
                // Auto-enable parent if user enables child
                disableReelsSwitch.setChecked(true);
            }
            updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
            SettingsManager.saveAllFlags();
        });

        // All other switches
        for (Switch s : new Switch[]{disableStoriesSwitch, disableFeedSwitch, disableExploreSwitch, disableCommentsSwitch}) {
            s.setOnCheckedChangeListener((buttonView, isChecked) -> {
                updateMasterSwitch(enableAllSwitch, switches, disableReelsSwitch, onlyInDMSwitch);
                SettingsManager.saveAllFlags();
            });
        }

        // Init "Except in DMs" state
        onlyInDMSwitch.setEnabled(disableReelsSwitch.isChecked());

        // Layout building
        layout.addView(extremeModeSwitch);
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (Switch s : switches) {
            layout.addView(s);
        }

        showSectionDialog(context, "Distraction-Free Instagram 🧘", layout, () -> {
            FeatureFlags.disableStories = disableStoriesSwitch.isChecked();
            FeatureFlags.disableFeed = disableFeedSwitch.isChecked();
            FeatureFlags.disableReels = disableReelsSwitch.isChecked();
            FeatureFlags.disableReelsExceptDM = onlyInDMSwitch.isChecked();
            FeatureFlags.disableExplore = disableExploreSwitch.isChecked();
            FeatureFlags.disableComments = disableCommentsSwitch.isChecked();
        });

        SettingsManager.saveAllFlags();
    }

    private static void disableAllSwitches(Switch[] switches, @SuppressLint("UseSwitchCompatOrMaterialCode") Switch master, @SuppressLint("UseSwitchCompatOrMaterialCode") Switch onlyInDMSwitch) {

        for (Switch s : switches) {
            if (s == onlyInDMSwitch) {
                // Special rule for onlyInDM
                s.setEnabled(s.isChecked()); // editable only if it was checked
            } else {
                // Normal switches: lock if checked, editable if unchecked
                s.setEnabled(!s.isChecked());
            }
        }

        // Master switch always frozen ON
        master.setEnabled(false);
    }


    private static void updateMasterSwitch(@SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch, Switch[] switches, @SuppressLint("UseSwitchCompatOrMaterialCode") Switch disableReelsSwitch, @SuppressLint("UseSwitchCompatOrMaterialCode") Switch onlyInDMSwitch) {
        enableAllSwitch.setOnCheckedChangeListener(null);
        enableAllSwitch.setChecked(areAllEnabled(switches));
        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : switches) {
                s.setChecked(isChecked);
            }
            onlyInDMSwitch.setEnabled(disableReelsSwitch.isChecked());
        });
    }


    private static void showMiscOptions(Context context) {
        LinearLayout layout = createSwitchLayout(context);

        // Create all child switches
        Switch[] switches = new Switch[]{
                createSwitch(context, "Disable Story Auto-Swipe", FeatureFlags.disableStoryFlipping),
                createSwitch(context, "Disable Video Autoplay", FeatureFlags.disableVideoAutoPlay),
                createSwitch(context, "Disable Repost", FeatureFlags.disableRepost),
                createSwitch(context, "Show Follower Toast", FeatureFlags.showFollowerToast),
                createSwitch(context, "Show Feature Toasts", FeatureFlags.showFeatureToasts),
                createSwitch(context, "Media Download Button", FeatureFlags.enableMediaDownload)
        };

        // Create Enable/Disable All switch
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch = createSwitch(context, "Enable/Disable All", areAllEnabled(switches));

        enableAllSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (Switch s : switches) {
                s.setChecked(isChecked);
            }
        });

        for (int i = 0; i < switches.length; i++) {
            final int index = i;
            switches[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                enableAllSwitch.setOnCheckedChangeListener(null);
                enableAllSwitch.setChecked(areAllEnabled(switches));
                enableAllSwitch.setOnCheckedChangeListener((buttonView2, isChecked2) -> {
                    for (Switch s2 : switches) {
                        s2.setChecked(isChecked2);
                    }
                });

                // Update FeatureFlags
                switch (index) {
                    case 0:
                        FeatureFlags.disableStoryFlipping = isChecked;
                        break;
                    case 1:
                        FeatureFlags.disableVideoAutoPlay = isChecked;
                        break;
                    case 2:
                        FeatureFlags.disableRepost = isChecked;
                        break;
                    case 3:
                        FeatureFlags.showFollowerToast = isChecked;
                        break;
                    case 4:
                        FeatureFlags.showFeatureToasts = isChecked;
                        break;
                    case 5:
                        FeatureFlags.enableMediaDownload = isChecked;
                        break;
                }

                SettingsManager.saveAllFlags();
            });
        }

        // Add views to layout
        layout.addView(createDivider(context));
        layout.addView(createEnableAllSwitch(context, enableAllSwitch));
        layout.addView(createDivider(context));

        for (Switch s : switches) {
            layout.addView(s);
        }

        // Show dialog
        showSectionDialog(context, "Miscellaneous ⚙️", layout, () -> {
        });
    }


    @SuppressLint("SetTextI18n")
    private static void showAboutDialog(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 20);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(context);
        title.setText("InstaEclipse 🌘");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20f);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 20);

        TextView creator = new TextView(context);
        creator.setText("Created by @reso7200");
        creator.setTextColor(Color.LTGRAY);
        creator.setTextSize(16f);
        creator.setGravity(Gravity.CENTER);
        creator.setPadding(0, 0, 0, 30);

        Button githubButton = new Button(context);
        githubButton.setText("🌐 GitHub Repo");
        githubButton.setTextColor(Color.WHITE);
        githubButton.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#3F51B5")));
        githubButton.setPadding(40, 20, 40, 20);

        LinearLayout.LayoutParams githubParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        githubParams.gravity = Gravity.CENTER_HORIZONTAL;
        githubButton.setLayoutParams(githubParams);


        githubButton.setOnClickListener(v -> {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ReSo7200/InstaEclipse"));
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(browserIntent);
        });

        layout.addView(title);
        layout.addView(creator);
        layout.addView(githubButton);

        showSectionDialog(context, "About", layout, () -> {
        });
    }

    @SuppressLint("SetTextI18n")
    private static void showRestartSection(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 40);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView message = new TextView(context);
        message.setText("⚠️ Clear app cache and restart?");
        message.setTextColor(Color.WHITE);
        message.setTextSize(18f);
        message.setGravity(Gravity.CENTER);
        message.setPadding(0, 0, 0, 30);

        Button restartButton = new Button(context);
        restartButton.setText("🔁 Restart Now");
        restartButton.setTextColor(Color.WHITE);
        restartButton.setPadding(40, 20, 40, 20);

        restartButton.setOnClickListener(v -> restartApp(context));

        layout.addView(message);
        layout.addView(restartButton);

        showSectionDialog(context, "Restart App", layout, () -> {
        });
    }


    // ==== HELPERS ====

    @SuppressLint("SetTextI18n")
    private static void showSectionDialog(Context context, String title, LinearLayout contentLayout, Runnable onSave) {
        if (currentDialog != null) currentDialog.dismiss();

        // Wrap in a card-style layout
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(40, 40, 40, 20);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#262626"));
        background.setCornerRadius(32);
        container.setBackground(background);

        // Title
        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, 30);
        container.addView(titleView);

        container.addView(createDivider(context));
        container.addView(contentLayout);
        container.addView(createDivider(context));

        // Footer button
        TextView backBtn = new TextView(context);
        backBtn.setText("← Back");
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTextSize(16);
        backBtn.setGravity(Gravity.CENTER);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#40FFFFFF")));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        backBtn.setBackground(states);

        backBtn.setPadding(0, 30, 0, 10);
        backBtn.setOnClickListener(v -> {
            onSave.run();
            SettingsManager.saveAllFlags();
            showEclipseOptionsDialog(context);
        });

        container.addView(backBtn);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);

        currentDialog = new AlertDialog.Builder(context).setView(scrollView).setCancelable(true).create();

        Objects.requireNonNull(currentDialog.getWindow()).setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        currentDialog.show();
    }


    private static LinearLayout createSwitchLayout(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 30);
        layout.setDividerDrawable(new ColorDrawable(Color.DKGRAY));
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        layout.setDividerPadding(20);

        return layout;
    }

    private static Switch createSwitch(Context context, String label, boolean defaultState) {
        @SuppressLint("UseSwitchCompatOrMaterialCode") Switch toggle = new Switch(context);
        toggle.setText(label);
        toggle.setChecked(defaultState);
        toggle.setPadding(30, 20, 30, 20);
        toggle.setTextColor(Color.WHITE);
        toggle.setThumbTintList(createThumbColor());
        toggle.setTrackTintList(createTrackColor());
        toggle.setTextSize(16);
        return toggle;
    }

    private static ColorStateList createThumbColor() {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},          // Disabled
                new int[]{android.R.attr.state_checked},           // Checked
                new int[]{-android.R.attr.state_checked}           // Unchecked
        }, new int[]{Color.parseColor("#555555"),  // Disabled
                Color.parseColor("#448AFF"),  // ON
                Color.parseColor("#FFFFFF")   // OFF
        });
    }

    private static ColorStateList createTrackColor() {
        return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},          // Disabled
                new int[]{android.R.attr.state_checked},           // Checked
                new int[]{-android.R.attr.state_checked}           // Unchecked
        }, new int[]{Color.parseColor("#777777"),  // Disabled
                Color.parseColor("#1C4C78"),  // ON
                Color.parseColor("#CFD8DC")   // OFF
        });
    }

    private static View createClickableSection(Context context, String label, Runnable onClick) {
        TextView section = new TextView(context);
        section.setText(label);
        section.setTextSize(18);
        section.setTextColor(Color.WHITE);
        section.setPadding(20, 24, 20, 24);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#40FFFFFF")));
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        section.setBackground(states);

        section.setOnClickListener(v -> onClick.run());
        return section;
    }


    private static LinearLayout createEnableAllSwitch(Context context, @SuppressLint("UseSwitchCompatOrMaterialCode") Switch enableAllSwitch) {
        // Customize the main Enable/Disable All switch style
        enableAllSwitch.setTextSize(18f);
        enableAllSwitch.setTextColor(Color.WHITE);
        enableAllSwitch.setTypeface(null, Typeface.BOLD);
        enableAllSwitch.setPadding(40, 40, 40, 40);

        // Create a container layout
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(20, 20, 20, 20);

        // Background with rounded corners
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#333333")); // Dark grey background
        background.setCornerRadius(24);
        container.setBackground(background);

        container.addView(enableAllSwitch);

        return container;
    }

    private static boolean areAllEnabled(Switch[] switches) {
        for (Switch s : switches) {
            if (!s.isChecked()) return false;
        }
        return true;
    }

}
