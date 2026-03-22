package ps.reso.instaeclipse.mods.devops;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class DevOptionsEnable {

    // Stable unique strings that identify the download-eligibility class (LX/515)
    // Confirmed from Instagram 421 APK smali analysis:
    //   LX/515->A0A(UserSession, Media) -> Z  gates MediaOption$Option.DOWNLOAD
    //   LX/515->A06(UserSession, Z)     -> Z  is a second gate in the Reels path
    private static final String DOWNLOAD_GATE_STRING_1 = "is_third_party_downloads_eligible";
    private static final String DOWNLOAD_GATE_STRING_2 = "is_clips_downloadable";

    public void handleDevOptions(DexKitBridge bridge) {
        try {
            // Hook 1: original dev-options gate (is_employee class)
            findAndHookDynamicMethod(bridge);

            // Hook 2: download eligibility gate (LX/515)
            hookDownloadEligibility(bridge);

        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook 2 — Download eligibility gate
    //
    // Instagram's menu builder (X/6Dd, X/6Db) calls:
    //   LX/515->A0A(UserSession, Media) -> Z
    // If it returns TRUE, MediaOption$Option.DOWNLOAD is added to the menu list.
    // Instagram already has the Download row built-in — it's just gated here.
    //
    // We find LX/515 via its unique strings "is_third_party_downloads_eligible"
    // and "is_clips_downloadable" (confirmed stable across versions from APK analysis).
    // Then we hook ALL (UserSession, ...) -> Z methods in that class to return true,
    // which enables the native Download option unconditionally.
    // ─────────────────────────────────────────────────────────────────────────
    private void hookDownloadEligibility(DexKitBridge bridge) {
        try {
            // Find the download-eligibility class via its unique string constants
            List<ClassData> classes = bridge.findClass(
                    FindClass.create().matcher(
                            ClassMatcher.create()
                                    .usingStrings(DOWNLOAD_GATE_STRING_1)
                    )
            );

            // Fallback: try second unique string if first not found
            if (classes.isEmpty()) {
                classes = bridge.findClass(
                        FindClass.create().matcher(
                                ClassMatcher.create()
                                        .usingStrings(DOWNLOAD_GATE_STRING_2)
                        )
                );
            }

            if (classes.isEmpty()) {
                XposedBridge.log("(InstaEclipse | DownloadGate): ❌ eligibility class not found");
                return;
            }

            for (ClassData classData : classes) {
                String className = classData.getName();
                XposedBridge.log("(InstaEclipse | DownloadGate): found class → " + className);

                // Hook all boolean methods that take UserSession as first param
                // This covers:
                //   A0A(UserSession, Media) -> Z  — feed post download gate
                //   A04(UserSession)        -> Z  — simple eligibility check
                //   A06(UserSession, Z)     -> Z  — Reels second gate (needs false, handled below)
                List<MethodData> methods = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create()
                                        .declaredClass(className)
                                        .returnType("boolean")
                        )
                );

                XposedBridge.log("(InstaEclipse | DownloadGate): found " + methods.size()
                        + " boolean methods in " + className);

                for (MethodData md : methods) {
                    hookDownloadGateMethod(md, className);
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ " + t.getMessage());
        }
    }

    private void hookDownloadGateMethod(MethodData md, String className) {
        try {
            Method m = md.getMethodInstance(Module.hostClassLoader);

            // Determine what this method should return:
            // A06(UserSession, Z) -> Z is the Reels "already-enabled" check.
            // if-nez result, :skip_download — so it must return FALSE to show download.
            // All other boolean methods in this class should return TRUE.
            //
            // We identify A06-style methods as: returns Z, first param is UserSession,
            // second param is Z (boolean). These must return false.
            boolean mustReturnFalse = false;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2
                    && params[1] == Boolean.TYPE
                    && params[0].getName().contains("UserSession")) {
                mustReturnFalse = true;
            }

            final boolean returnValue = !mustReturnFalse;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    param.setResult(returnValue);
                }
            });

            XposedBridge.log("(InstaEclipse | DownloadGate): ✅ hooked "
                    + md.getName() + " → always returns " + returnValue);

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ hook failed for "
                    + md.getName() + ": " + t.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Original dev-options hook (unchanged)
    // ─────────────────────────────────────────────────────────────────────────
    private void findAndHookDynamicMethod(DexKitBridge bridge) {
        try {
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("is_employee"))
            );

            if (classes.isEmpty()) return;

            for (ClassData classData : classes) {
                String className = classData.getName();
                if (!className.startsWith("X.")) continue;

                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(className)
                                .usingStrings("is_employee"))
                );

                if (methods.isEmpty()) continue;

                for (MethodData method : methods) {
                    inspectInvokedMethods(bridge, method);
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error during discovery: "
                    + e.getMessage());
        }
    }

    private void inspectInvokedMethods(DexKitBridge bridge, MethodData method) {
        try {
            List<MethodData> invokedMethods = method.getInvokes();
            if (invokedMethods.isEmpty()) return;

            for (MethodData invokedMethod : invokedMethods) {
                String returnType = String.valueOf(invokedMethod.getReturnType());
                if (!returnType.contains("boolean")) continue;

                List<String> paramTypes = new ArrayList<>();
                for (Object param : invokedMethod.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (paramTypes.size() == 1
                        && paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {
                    String targetClass = invokedMethod.getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking boolean methods in: "
                            + targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    return;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error inspecting: "
                    + e.getMessage());
        }
    }

    private void hookAllBooleanMethodsInClass(DexKitBridge bridge, String className) {
        try {
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().declaredClass(className))
            );

            for (MethodData method : methods) {
                String returnType = String.valueOf(method.getReturnType());
                List<String> paramTypes = new ArrayList<>();
                for (Object param : method.getParamTypes()) {
                    paramTypes.add(String.valueOf(param));
                }

                if (returnType.contains("boolean")
                        && paramTypes.size() == 1
                        && paramTypes.get(0).contains("com.instagram.common.session.UserSession")) {
                    try {
                        Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                        XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (FeatureFlags.isDevEnabled) {
                                    param.setResult(true);
                                    FeatureStatusTracker.setHooked("DevOptions");
                                }
                            }
                        });
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ Hooked: "
                                + method.getClassName() + "." + method.getName());
                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Failed to hook "
                                + method.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ Error while hooking class: "
                    + className + " → " + e.getMessage());
        }
    }
}