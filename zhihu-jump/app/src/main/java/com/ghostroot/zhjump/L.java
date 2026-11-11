package com.ghostroot.zhjump;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.error.HookFailedError;

/**
 * Thin adapter over the modern libxposed API, shared by all GhostRoot modules.
 *
 * Rules learned from the official docs (libxposed.github.io/api, API 102) and
 * applied here:
 *
 *  - HookBuilder.setId() is @SinceApi(102), but module.prop declares
 *    minApiVersion=100. Calling it would silently kill every hook on an
 *    API 100/101 framework. We never need atomic hook replacement, so it is
 *    gone and minApiVersion=100 is now honest.
 *  - setExceptionMode(PROTECTIVE) is set explicitly on each hook instead of
 *    relying on the module.prop default. A hooker exception is then caught
 *    and logged by the framework, and the call continues as if unhooked;
 *    exceptions thrown by chain.proceed() still propagate as they must.
 *  - Chain.getArgs() returns an IMMUTABLE list. The old trick of mutating
 *    args with getArgs().set(...) throws; getArg(int) is the read-only
 *    accessor, and changing args requires proceed(Object[]).
 *  - Chain objects must not leave intercept(). We only copy the View or an
 *    argument out of it and never retain the Chain itself.
 *  - PackageLoadedParam exposes getPackageName() / isFirstPackage() /
 *    getDefaultClassLoader() directly. Reflecting into the Xposed API is
 *    unsafe: a framework may set PROP_RT_API_PROTECTION, which disallows
 *    accessing the Xposed API via reflection or dynamically loaded code.
 *  - HookFailedError is a framework-level fatal error, not a module bug.
 *    We must not swallow it silently, so it is written to logcat AND the
 *    LSPosed module log with the full stack. We deliberately do not rethrow
 *    it: that would turn a framework fault into a host app crash.
 *  - deoptimize(Executable) is intentionally NOT called. Its Javadoc says it
 *    deoptimizes the CALLER that inlined the hooked callee, and warns that
 *    finding every caller is practically impossible; changing the hook point
 *    is preferred. Our hook points already work on the device.
 */
public final class L {

    public static final String TAG = "ZHJump";

    private static volatile XposedModule module;
    private static final ThreadLocal<Boolean> IN_INSPECT = new ThreadLocal<>();

    private L() {
    }

    public static void bind(XposedModule m) {
        module = m;
    }

    public static void i(String msg) {
        try {
            Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    /** Errors go to logcat AND the framework log, so they survive a logcat -c. */
    public static void e(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
        }
        XposedModule m = module;
        if (m != null) {
            try {
                m.log(Log.ERROR, TAG, msg, t);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Reentrancy guard. inspect() touches Resources and posts Runnables whose
     * click handlers can synchronously create/attach Views, re-entering our
     * own hooks. Only the inspection is guarded; chain.proceed() always runs
     * in the hooker itself, so host control flow is never changed.
     */
    public static boolean enterInspect() {
        if (Boolean.TRUE.equals(IN_INSPECT.get())) {
            return false;
        }
        IN_INSPECT.set(Boolean.TRUE);
        return true;
    }

    public static void exitInspect() {
        IN_INSPECT.remove();
    }

    /** Install one hook. A bad rule must never break the host app. */
    public static void hook(String id, Executable target, XposedInterface.Hooker hooker) {
        XposedModule m = module;
        if (m == null || target == null || hooker == null) {
            return;
        }
        try {
            m.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            i("hook installed: " + id + " -> " + target);
        } catch (HookFailedError fatal) {
            // Framework bug, not ours. Keep the full stack, do not rethrow.
            e("HOOK FRAMEWORK FAILURE (report to LSPosed maintainers): "
                    + id + " -> " + target, fatal);
        } catch (IllegalArgumentException bad) {
            e("invalid hook target: " + id + " -> " + target, bad);
        } catch (Throwable t) {
            e("hook failed: " + id, t);
        }
    }

    public static Object self(XposedInterface.Chain chain) {
        try {
            return chain.getThisObject();
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object arg(XposedInterface.Chain chain, int index) {
        try {
            return chain.getArg(index);
        } catch (Throwable t) {
            return null;
        }
    }

    /** getDefaultClassLoader() is @RequiresApi(29); minSdk here is 26. */
    public static ClassLoader classLoaderOf(XposedModuleInterface.PackageLoadedParam param) {
        if (param == null || Build.VERSION.SDK_INT < 29) {
            return null;
        }
        try {
            return param.getDefaultClassLoader();
        } catch (Throwable t) {
            e("getDefaultClassLoader failed", t);
            return null;
        }
    }

    public static String packageNameOf(XposedModuleInterface.PackageLoadedParam param) {
        if (param == null) {
            return "";
        }
        try {
            return param.getPackageName();
        } catch (Throwable t) {
            e("getPackageName failed", t);
            return "";
        }
    }
}
