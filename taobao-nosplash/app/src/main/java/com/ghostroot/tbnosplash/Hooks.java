package com.ghostroot.tbnosplash;

import android.content.ComponentName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Suppresses Android's cold-start "starting window" for Taobao only.
 *
 * Reverse-engineered from /system/framework/services.jar (classes3.dex) on
 * vivo V2227A / Android 16:
 *
 *   ActivityRecord.addStartingWindow(10 args) -> Z
 *     invokes ActivityRecord.getStartingWindowType(7 args) -> I
 *   ActivityRecord.STARTING_WINDOW_TYPE_NONE        = 0
 *                       _SNAPSHOT                  = 1
 *                       _SPLASH_SCREEN            = 2
 *                       _SPLASH_SCREEN_HOT        = 3
 *   vivo's own code already assigns 0 to that type in some branches, so NONE
 *   is a fully supported state, not an invalid one.
 *
 * The white "淘" page is drawn by system_server BEFORE the app process even
 * starts, which is why hooking Welcome from the app-side module cannot remove
 * it. Here we only replace a return value (or, as a fallback, skip one
 * addStartingWindow call) for com.taobao.taobao; every other app continues
 * through chain.proceed() unchanged.
 *
 * All reflection and every hooker body is guarded. If the class or methods
 * are missing on another ROM, this module is completely inert.
 */
public final class Hooks {

    private static final String TARGET = "com.taobao.taobao";
    private static final String AR_CLASS = "com.android.server.wm.ActivityRecord";

    private static volatile boolean installed = false;
    private static volatile int typeHits = 0;
    private static volatile int addHits = 0;
    private static volatile int selfLogs = 0;
    private static volatile Field packageNameField;
    private static volatile Field componentField;

    private Hooks() {
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static synchronized void install(ClassLoader prefer) {
        if (installed) {
            return;
        }
        Class<?> ar = findClass(prefer);
        if (ar == null) {
            L.i("ActivityRecord not resolvable yet (will retry)");
            return;
        }
        resolveFields(ar);
        int count = 0;
        for (Method m : ar.getDeclaredMethods()) {
            try {
                String n = m.getName();
                if ("getStartingWindowType".equals(n) && m.getReturnType() == int.class) {
                    L.hook("ns.type", m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            Object self = L.self(chain);
                            if (self != null && isTarget(self)) {
                                if (typeHits++ < 8) {
                                    L.i("force STARTING_WINDOW_TYPE_NONE hit=" + typeHits);
                                }
                                return Integer.valueOf(0);
                            }
                            return r;
                        }
                    });
                    count++;
                } else if ("addStartingWindow".equals(n) && m.getReturnType() == boolean.class) {
                    L.hook("ns.add", m, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object self = L.self(chain);
                            if (self != null && isTarget(self)) {
                                if (addHits++ < 8) {
                                    L.i("skip addStartingWindow hit=" + addHits);
                                }
                                return Boolean.FALSE;
                            }
                            return chain.proceed();
                        }
                    });
                    count++;
                }
            } catch (Throwable t) {
                L.e("hook candidate failed", t);
            }
        }
        if (count == 0) {
            L.i("no starting-window hook point found; module stays inert");
            return;
        }
        installed = true;
        L.i("system hooks installed count=" + count + " loader=" + ar.getClassLoader());
    }

    private static Class<?> findClass(ClassLoader prefer) {
        ClassLoader[] loaders = {
                prefer,
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                Hooks.class.getClassLoader()
        };
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(AR_CLASS, false, cl);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void resolveFields(Class<?> ar) {
        packageNameField = findField(ar, "packageName");
        componentField = findField(ar, "mActivityComponent");
        L.i("ActivityRecord fields: packageName=" + (packageNameField != null)
                + " component=" + (componentField != null));
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** Cheap checks first; toString() is only a diagnostic fallback. */
    private static boolean isTarget(Object ar) {
        try {
            Field f = packageNameField;
            if (f != null && TARGET.equals(f.get(ar))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Field f = componentField;
            if (f != null) {
                Object v = f.get(ar);
                if (v instanceof ComponentName
                        && TARGET.equals(((ComponentName) v).getPackageName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String s = String.valueOf(ar);
            if (selfLogs++ < 6) {
                L.i("ActivityRecord sample: " + s.substring(0, Math.min(200, s.length())));
            }
            return s.contains(TARGET);
        } catch (Throwable ignored) {
        }
        return false;
    }
}
