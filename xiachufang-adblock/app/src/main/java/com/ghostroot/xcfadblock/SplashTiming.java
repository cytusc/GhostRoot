package com.ghostroot.xcfadblock;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Make a cold start go straight to HomeActivity by invoking the host's own
 * "ad closed" callback on StartPageActivity. Hiding the ad view only removes
 * the pixels: the host still waits a fixed ~9s before navigating, even when the
 * ad SDK reports onAdFailedToLoad. Rewriting Handler delays does not work here
 * because libxposed Chain.getArgs() is read-only (set() throws), so we call the
 * real navigation callback instead of forcing finish() / startActivity ourselves.
 */
public final class SplashTiming {

    private static volatile boolean autoClosed = false;
    private static volatile boolean methodsDumped = false;

    /** Activity classes are a small fixed set; cache the splash test. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> SPLASH_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SplashTiming() {
    }

    public static synchronized void install() {
        hookStartPageCreate();
    }

    private static void hookStartPageCreate() {
        try {
            Method create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
            create.setAccessible(true);
            L.hook("xcf.onCreate", create, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = L.self(chain);
                    final Activity act = (self instanceof Activity) ? (Activity) self : null;
                    final boolean isSplash = act != null
                            && isStartPage(act.getClass().getName());
                    if (isSplash) {
                        autoClosed = false;
                    }
                    // Host onCreate must finish before we poke its callbacks.
                    Object r = chain.proceed();
                    if (isSplash) {
                        L.i("splash onCreate done: " + act.getClass().getName());
                        dumpMethods(act.getClass());
                        scheduleAutoClose(act);
                    }
                    return r;
                }
            });
        } catch (Throwable t) {
            L.e("hook onCreate failed", t);
        }
    }

    private static void scheduleAutoClose(final Activity act) {
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (autoClosed || act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    autoClosed = true;
                    try {
                        Method m = act.getClass().getDeclaredMethod("onAdClose");
                        m.setAccessible(true);
                        m.invoke(act);
                        L.i("splash onAdClose invoked");
                    } catch (Throwable t) {
                        L.e("onAdClose failed", t);
                        invokeFallback(act);
                    }
                }
            }, 300);
        } catch (Throwable t) {
            L.e("scheduleAutoClose failed", t);
        }
    }

    /** Try the host's ad-failure callback if onAdClose is not usable. */
    private static void invokeFallback(Activity act) {
        try {
            Method m = act.getClass().getDeclaredMethod("onAdErr", Throwable.class);
            m.setAccessible(true);
            m.invoke(act, new Throwable("AdClose by GhostRoot"));
            L.i("splash onAdErr fallback invoked");
        } catch (Throwable t) {
            L.e("onAdErr fallback failed", t);
        }
    }

    private static boolean isStartPage(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = SPLASH_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(java.util.Locale.ROOT);
        boolean r = c.contains("startpage") || c.contains("splashactivity");
        SPLASH_CACHE.put(cls, r);
        return r;
    }

    private static void dumpMethods(Class<?> c) {
        if (methodsDumped) {
            return;
        }
        methodsDumped = true;
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : c.getDeclaredMethods()) {
                sb.append(m.getName()).append(' ');
            }
            L.i("splash methods: " + sb);
        } catch (Throwable t) {
            L.e("dumpMethods failed", t);
        }
    }
}
