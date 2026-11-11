package com.ghostroot.tbnosplash;

import android.util.Log;

import java.lang.reflect.Executable;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.error.HookFailedError;

/**
 * Thin adapter over libxposed API 102, matching the other GhostRoot modules.
 *
 *  - setExceptionMode(PROTECTIVE) is set explicitly on every hook.
 *  - HookFailedError is a framework fault: log it with the full stack to both
 *    logcat and the LSPosed module log, but never rethrow it (that would
 *    crash system_server).
 *  - Chain.getArgs() is immutable; we only use getThisObject().
 *  - Chain never leaves intercept().
 */
public final class L {

    public static final String TAG = "TBNoSplash";

    private static volatile XposedModule module;

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
            e("HOOK FRAMEWORK FAILURE: " + id + " -> " + target, fatal);
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

}
