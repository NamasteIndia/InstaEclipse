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

    public void handleDevOptions(DexKitBridge bridge) {
        try {
            findAndHookDynamicMethod(bridge);
            hookDownloadEligibility(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hook: Download eligibility gate
    //
    // From Instagram 421 APK smali analysis (X/6Dd.smali:5233, X/6Db.smali:3002):
    //
    //   sget-object v2, LX/515;->A00:LX/515;
    //   invoke-virtual {v2, v14, v3}, LX/515;->A0A(UserSession, Media) -> Z
    //   if-eqz v2, :skip_download
    //   sget-object v2, MediaOption$Option->DOWNLOAD
    //   list.add(v2)   <-- Instagram adds its own native Download row
    //
    // We hook ONLY A0A(UserSession, Media) -> Z to return true.
    // This is safe — it's only called in the menu option builder methods.
    // All other LX/515 methods are left untouched to prevent crashes.
    // ─────────────────────────────────────────────────────────────────────────
    private void hookDownloadEligibility(DexKitBridge bridge) {
        try {
            // Find LX/515 via its unique string constant
            List<ClassData> classes = bridge.findClass(
                    FindClass.create().matcher(
                            ClassMatcher.create()
                                    .usingStrings("is_third_party_downloads_eligible")
                    )
            );

            if (classes.isEmpty()) {
                // Fallback string
                classes = bridge.findClass(
                        FindClass.create().matcher(
                                ClassMatcher.create()
                                        .usingStrings("is_clips_downloadable")
                        )
                );
            }

            if (classes.isEmpty()) {
                XposedBridge.log("(InstaEclipse | DownloadGate): ❌ class not found");
                return;
            }

            for (ClassData classData : classes) {
                String className = classData.getName();
                XposedBridge.log("(InstaEclipse | DownloadGate): found → " + className);

                // Find ONLY A0A(UserSession, Media) -> Z
                // Exact param types confirmed from DEX binary parsing of all Instagram dex files
                List<MethodData> methods = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create()
                                        .declaredClass(className)
                                        .returnType("boolean")
                                        .paramTypes(
                                            "com.instagram.common.session.UserSession",
                                            "com.instagram.feed.media.Media"
                                        )
                        )
                );

                if (methods.isEmpty()) {
                    XposedBridge.log("(InstaEclipse | DownloadGate): ❌ A0A not found in "
                            + className);
                    continue;
                }

                for (MethodData md : methods) {
                    try {
                        Method m = md.getMethodInstance(Module.hostClassLoader);
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (!FeatureFlags.enableMediaDownload) return;
                                param.setResult(true);
                            }
                        });
                        XposedBridge.log("(InstaEclipse | DownloadGate): ✅ hooked "
                                + className + "." + md.getName()
                                + "(UserSession, Media) -> Z");
                    } catch (Throwable t) {
                        XposedBridge.log("(InstaEclipse | DownloadGate): ❌ hook failed: "
                                + t.getMessage());
                    }
                }
            }

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ " + t.getMessage());
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
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ " + e.getMessage());
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
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking: " + targetClass);
                    hookAllBooleanMethodsInClass(bridge, targetClass);
                    return;
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ " + e.getMessage());
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
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ✅ "
                                + method.getClassName() + "." + method.getName());
                    } catch (Throwable e) {
                        XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ "
                                + method.getName() + ": " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ " + e.getMessage());
        }
    }
}