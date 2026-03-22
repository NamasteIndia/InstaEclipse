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

    private static final String USER_SESSION = "com.instagram.common.session.UserSession";
    private static final String MEDIA        = "com.instagram.feed.media.Media";

    public void handleDevOptions(DexKitBridge bridge) {
        try {
            findAndHookDynamicMethod(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DevOptionsEnable): ❌ " + e.getMessage());
        }
        try {
            hookDownloadGate(bridge);
        } catch (Exception e) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Download eligibility gate
    //
    // Both feed posts (X/25U.smali) and Reels (X/6Db.smali) use a double gate:
    //
    //   Gate 1 — LX/515->A0A(UserSession, Media) -> Z
    //     if false: skip DOWNLOAD option entirely
    //
    //   Gate 2 — LX/515->A06(UserSession, boolean) -> Z
    //     if TRUE: skip DOWNLOAD option (means "download already configured/on")
    //     We must return FALSE to let Download appear
    //
    // Feed posts previously only showed Gate 1 in ClipsOrganicMoreOptionsHelper
    // (X/6Dd.smali) which is why Download appeared for Reels but not feed posts.
    // The actual feed post handler (X/25U.smali) has BOTH gates.
    //
    // Fingerprinting LX/515: only class with a (UserSession, Media)->Z method
    // that ALSO has A06(UserSession, boolean)->Z — confirmed from DEX binary.
    // A0A has no string constants so cannot be found by string search directly.
    // ─────────────────────────────────────────────────────────────────────────
    private void hookDownloadGate(DexKitBridge bridge) {
        // Step 1: find all (UserSession, Media) -> boolean candidates
        List<MethodData> candidates;
        try {
            candidates = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .returnType("boolean")
                                    .paramTypes(USER_SESSION, MEDIA)
                    )
            );
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ findMethod: " + t.getMessage());
            return;
        }

        XposedBridge.log("(InstaEclipse | DownloadGate): candidates=" + candidates.size());

        String gateClass = null;

        for (MethodData md : candidates) {
            String className = md.getClassName();
            // Identify LX/515 by presence of A06(UserSession, boolean)->boolean
            if (!classHasMethod(bridge, className, "A06", "boolean",
                                USER_SESSION, "boolean")) continue;

            gateClass = className;
            XposedBridge.log("(InstaEclipse | DownloadGate): gate class = " + className);

            // Hook A0A — must return true for Download to appear
            hookMethod(md, true, "A0A");
            break;
        }

        if (gateClass == null) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ gate class not found");
            return;
        }

        // Step 2: hook A06(UserSession, boolean)->boolean — must return FALSE
        // If A06 returns true, Instagram skips showing Download (treats it as
        // "already enabled via settings"), so we force false to always show it.
        try {
            List<MethodData> a06 = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .declaredClass(gateClass)
                                    .name("A06")
                                    .returnType("boolean")
                                    .paramTypes(USER_SESSION, "boolean")
                    )
            );
            for (MethodData md : a06) {
                hookMethod(md, false, "A06");
            }
            XposedBridge.log("(InstaEclipse | DownloadGate): A06 hooks=" + a06.size());
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ A06 hook: " + t.getMessage());
        }
    }

    private void hookMethod(MethodData md, boolean returnValue, String label) {
        try {
            Method m = md.getMethodInstance(Module.hostClassLoader);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableMediaDownload) return;
                    param.setResult(returnValue);
                }
            });
            XposedBridge.log("(InstaEclipse | DownloadGate): ✅ hooked "
                    + md.getClassName() + "." + label + " → " + returnValue);
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | DownloadGate): ❌ hook " + label
                    + ": " + t.getMessage());
        }
    }

    private boolean classHasMethod(DexKitBridge bridge, String className,
                                    String name, String returnType,
                                    String... paramTypes) {
        try {
            List<MethodData> r = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .declaredClass(className)
                                    .name(name)
                                    .returnType(returnType)
                                    .paramTypes(paramTypes)
                    )
            );
            return !r.isEmpty();
        } catch (Throwable t) {
            return false;
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
                        && paramTypes.get(0).contains(USER_SESSION)) {
                    String targetClass = invokedMethod.getClassName();
                    XposedBridge.log("(InstaEclipse | DevOptionsEnable): 📦 Hooking: "
                            + targetClass);
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
                        && paramTypes.get(0).contains(USER_SESSION)) {
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