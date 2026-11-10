package com.ghostroot.smzdmadblock;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Minimal libxposed API 102 adapter: logging, safe hook installation, chain helpers. */
public final class L {

    public static final String TAG = "SMZDMBlock";

    private static volatile XposedModule module;

    private L() {
    }

    public static void bind(XposedModule m) {
        module = m;
    }

    public static void i(String msg) {
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        try {
            android.util.Log.e(TAG, msg, t);
        } catch (Throwable ignored) {
            i(msg + " :: " + t);
        }
    }

    /** A failed hook must never break the host app. */
    public static void hook(String id, Executable target, XposedInterface.Hooker hooker) {
        XposedModule m = module;
        if (m == null || target == null || hooker == null) {
            return;
        }
        try {
            m.hook(target).setId(id).intercept(hooker);
            i("hook installed: " + id + " -> " + target);
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
            List<Object> a = chain.getArgs();
            if (a == null || index < 0 || index >= a.size()) {
                return null;
            }
            return a.get(index);
        } catch (Throwable t) {
            return null;
        }
    }

    public static ClassLoader classLoaderOf(Object param) {
        if (param == null) {
            return null;
        }
        String[] names = {"getClassLoader", "getDefaultClassLoader"};
        for (String n : names) {
            try {
                Method m = param.getClass().getMethod(n);
                m.setAccessible(true);
                Object v = m.invoke(param);
                if (v instanceof ClassLoader) {
                    return (ClassLoader) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static String packageNameOf(Object param) {
        if (param != null) {
            try {
                Method m = param.getClass().getMethod("getPackageName");
                m.setAccessible(true);
                Object v = m.invoke(param);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }
}
