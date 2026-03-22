package ps.reso.instaeclipse.mods.ui.utils;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.setupHooks;

import android.app.Activity;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.media.MediaDownloadButtonHook;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;

public class BottomSheetHookUtil {

    public static void hookBottomSheetNavigator(DexKitBridge bridge) {
        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(
                                    MethodMatcher.create()
                                            .usingStrings("BottomSheetConstants")
                            )
            );

            if (methods.isEmpty()) return;

            for (MethodData method : methods) {
                if (!method.getClassName().equals("com.instagram.mainactivity.InstagramMainActivity"))
                    continue;

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                int modifiers = reflectMethod.getModifiers();
                String returnType = String.valueOf(method.getReturnType());
                ClassDataList paramTypes = method.getParamTypes();

                if (!Modifier.isStatic(modifiers)
                        && Modifier.isFinal(modifiers)
                        && !returnType.contains("void")
                        && paramTypes.size() == 0) {

                    XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            final Activity activity = getCurrentActivity();
                            if (activity == null) return;
                            activity.runOnUiThread(() -> {
                                try {
                                    setupHooks(activity);
                                    addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                                    // Inject Download into the bottom sheet
                                    MediaDownloadButtonHook.injectIntoBottomSheet(activity);
                                } catch (Exception ignored) {}
                            });
                        }
                    });

                    XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked: "
                            + method.getClassName() + "." + method.getName());
                    break;
                }
            }

            // Also hook all BottomSheetDialogFragment.onViewCreated to catch
            // post menus that open outside of InstagramMainActivity
            hookAllBottomSheetDialogs(bridge);

        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ DexKit exception: " + e.getMessage());
        }
    }

    /**
     * Hooks BottomSheetDialogFragment.onViewCreated to inject Download row
     * into any post/reel action sheet that opens.
     */
    private static void hookAllBottomSheetDialogs(DexKitBridge bridge) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
                    Module.hostClassLoader,
                    "onViewCreated",
                    android.view.View.class,
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            Activity activity = getCurrentActivity();
                            if (activity == null) return;
                            activity.runOnUiThread(() ->
                                    MediaDownloadButtonHook.injectIntoBottomSheet(activity));
                        }
                    });
        } catch (Throwable ignored) {}

        // Also hook androidx BottomSheetDialog.setContentView
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.material.bottomsheet.BottomSheetDialog",
                    Module.hostClassLoader,
                    "setContentView",
                    android.view.View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            Activity activity = getCurrentActivity();
                            if (activity == null) return;
                            activity.runOnUiThread(() ->
                                    MediaDownloadButtonHook.injectIntoBottomSheet(activity));
                        }
                    });
        } catch (Throwable ignored) {}
    }
}