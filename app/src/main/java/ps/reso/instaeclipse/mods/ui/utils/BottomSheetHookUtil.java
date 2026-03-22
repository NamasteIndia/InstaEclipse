package ps.reso.instaeclipse.mods.ui.utils;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.setupHooks;

import android.app.Activity;
import android.view.View;
import android.os.Bundle;

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
        // Original InstagramMainActivity hook (kept for setupHooks / ghost emoji)
        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings("BottomSheetConstants"))
            );
            for (MethodData method : methods) {
                if (!method.getClassName().equals("com.instagram.mainactivity.InstagramMainActivity"))
                    continue;
                Method reflectMethod;
                try { reflectMethod = method.getMethodInstance(Module.hostClassLoader); }
                catch (Throwable e) { continue; }
                int mod = reflectMethod.getModifiers();
                if (!Modifier.isStatic(mod) && Modifier.isFinal(mod)
                        && !String.valueOf(method.getReturnType()).contains("void")
                        && method.getParamTypes().size() == 0) {

                    XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = getCurrentActivity();
                            if (activity == null) return;
                            activity.runOnUiThread(() -> {
                                try {
                                    setupHooks(activity);
                                    addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                                } catch (Exception ignored) {}
                            });
                        }
                    });
                    XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked InstagramMainActivity");
                    break;
                }
            }
        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ DexKit: " + e.getMessage());
        }

        // Hook the sheet dialog — pass the root view directly so we don't need BFS
        hookBottomSheetDialog();
    }

    /**
     * Hooks BottomSheetDialogFragment.onViewCreated.
     * The `view` parameter IS the sheet's root — we pass it directly to
     * MediaDownloadButtonHook so it can inject without BFS scanning.
     */
    private static void hookBottomSheetDialog() {
        // onViewCreated(View view, Bundle savedInstanceState)
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.material.bottomsheet.BottomSheetDialogFragment",
                    Module.hostClassLoader,
                    "onViewCreated",
                    View.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            View sheetRoot = (View) param.args[0];
                            Activity activity = getCurrentActivity();
                            if (activity == null || sheetRoot == null) return;
                            activity.runOnUiThread(() ->
                                    MediaDownloadButtonHook.injectIntoSheetView(sheetRoot, activity));
                        }
                    });
            XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked BottomSheetDialogFragment.onViewCreated");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ BottomSheetDialogFragment hook: " + t.getMessage());
        }

        // BottomSheetDialog.setContentView(View) — fired for non-fragment sheets
        try {
            XposedHelpers.findAndHookMethod(
                    "com.google.android.material.bottomsheet.BottomSheetDialog",
                    Module.hostClassLoader,
                    "setContentView",
                    View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            View sheetRoot = (View) param.args[0];
                            Activity activity = getCurrentActivity();
                            if (activity == null || sheetRoot == null) return;
                            activity.runOnUiThread(() ->
                                    MediaDownloadButtonHook.injectIntoSheetView(sheetRoot, activity));
                        }
                    });
            XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked BottomSheetDialog.setContentView");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ BottomSheetDialog hook: " + t.getMessage());
        }

        // androidx.appcompat Dialog.setContentView — broadest fallback
        try {
            XposedHelpers.findAndHookMethod(
                    "androidx.appcompat.app.AppCompatDialog",
                    Module.hostClassLoader,
                    "setContentView",
                    View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableMediaDownload) return;
                            View sheetRoot = (View) param.args[0];
                            Activity activity = getCurrentActivity();
                            if (activity == null || sheetRoot == null) return;
                            activity.runOnUiThread(() ->
                                    MediaDownloadButtonHook.injectIntoSheetView(sheetRoot, activity));
                        }
                    });
        } catch (Throwable ignored) {}
    }
}