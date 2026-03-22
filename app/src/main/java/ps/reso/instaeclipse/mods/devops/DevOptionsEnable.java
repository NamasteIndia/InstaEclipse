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

    // Stable non-obfuscated class names confirmed from APK smali analysis
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
    // Download eligibility gate hook
    //
    // APK analysis (classes8.dex) confirmed:
    //   LX/515 has virtual method A0A(UserSession, Media) -> Z
    //   Called from X/6Dd.smali:5233 and X/6Db.smali:3002
    //   If returns true → MediaOption$Option.DOWNLOAD added to menu
    //
    // A0A has NO string constants — DexKit cannot find it by string search.
    // We search by exact param types (UserSession, Media) -> boolean.
    // Among all ~120 methods with that signature, we identify LX/515 by
    // also checking the class has A06(UserSession, boolean)->boolean (the
    // second gate method, confirmed from 6Db.smali:3005).
    //
    // SAFE: we hook ONLY A0A in the class that also declares A06(UserSession,Z)->Z.
    // ─────────────────────────────────────────────────────────────────────────
    private void hookDownloadGate(DexKitBridge bridge) {
        // Find all methods: (UserSession, Media) -> boolean
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

        for (MethodData md : candidates) {
            String className = md.getClassName();

            // Identify LX/515: it is the ONLY class among candidates that also
            // declares A06(UserSession, boolean)->boolean (the "already-enabled" gate).
            // Confirmed from X/6Db.smali:3005 and LX/515 method list from DEX binary.
            if (!classHasA06WithUserSessionBool(bridge, className)) continue;

            XposedBridge.log("(InstaEclipse | DownloadGate): identified gate class → " + className);

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
                        + "(UserSession, Media) → always true when toggle on");
            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | DownloadGate): ❌ hook failed: " + t.getMessage());
            }

            // Only hook the first match (there should be exactly one)
            return;
        }

        XposedBridge.log("(InstaEclipse | DownloadGate): ❌ gate method not identified among "
                + candidates.size() + " candidates");
    }

    /**
     * Returns true if the given class also declares A06(UserSession, boolean) -> boolean.
     * This is the fingerprint that identifies LX/515 uniquely among all classes
     * that have a (UserSession, Media) -> boolean method.
     */
    private boolean classHasA06WithUserSessionBool(DexKitBridge bridge, String className) {
        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .declaredClass(className)
                                    .name("A06")
                                    .returnType("boolean")
                                    .paramTypes(USER_SESSION, "boolean")
                    )
            );
            return !methods.isEmpty();
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