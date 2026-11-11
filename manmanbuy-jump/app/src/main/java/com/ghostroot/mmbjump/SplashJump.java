package com.ghostroot.mmbjump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Deep-link style direct entry (pattern proven in taobao-adblock v1.7.0).
 *
 * manmanbuy is unique on this device: hooks on the framework
 * Activity.onCreate (and even the final performCreate) install cleanly but
 * NEVER fire, and deoptimize() does not help — its AOT-compiled activity
 * code reaches super.onCreate via direct compiled branches that bypass the
 * patched entrypoint.
 *
 * Primary strategy: hook Application.attach(Context) (not overridden by
 * apps, not final — the framework's virtual call always routes through the
 * base entrypoint), then hook the splash class's OWN onCreate declaration. An app-class method is invoked
 * virtually by the framework, so that hook cannot be bypassed the same way.
 * Class.forName is retried at app-creation time because the splash may live
 * in a dex that is only added after onPackageLoaded.
 *
 * Fallback: the generic Activity.onCreate hook (proven on smzdm / waimai /
 * zhihu) is kept in case the host changes its compile profile.
 *
 * Safety: the jump is issued once per process; REORDER_TO_FRONT keeps it
 * idempotent when the host's own navigation fires later; on any failure the
 * flags reset and the host's normal splash flow continues unchanged.
 */
public final class SplashJump {

    private static final String SPLASH_ACTIVITY = "com.manmanbuy.bijia.StartActivity";
    private static final String MAIN_ACTIVITY = "com.manmanbuy.bijia.MainActivity";

    private static volatile boolean jumped = false;
    private static volatile boolean jumpFailed = false;
    private static volatile boolean splashHooked = false;
    /** Classloader captured at onPackageLoaded, used when the runtime one fails. */
    private static volatile ClassLoader baseLoader;
    /** Diagnostic: log the first few fallback hits to prove hook dispatch. */
    private static volatile int diagHits = 0;

    private SplashJump() {
    }

    public static synchronized void install(ClassLoader cl) {
        baseLoader = cl;
        // v1.0.10 primary: Application.dispatchActivityPreCreated /
        // dispatchActivityCreated. The framework's (final, AOT-bypassed)
        // Activity.performCreate calls these on the Application instance via
        // virtual dispatch; apps never override them and they are not final,
        // so the call always routes through the base entrypoint — the only
        // activity-creation point that reliably dispatches in this host.
        hookApplicationDispatch("mmb.dispatch.pre", "dispatchActivityPreCreated", 2);
        hookApplicationDispatch("mmb.dispatch.created", "dispatchActivityCreated", 2);
        hookActivityCreateFallback();
    }

    /** Locate the splash class at app-creation time and hook its onCreate. */
    private static synchronized void hookSplash(ClassLoader runtime) {
        if (splashHooked) {
            return;
        }
        Class<?> splash = null;
        // Prefer the runtime loader of the app's own Application instance;
        // fall back to the one captured at onPackageLoaded.
        if (runtime != null) {
            try {
                splash = Class.forName(SPLASH_ACTIVITY, false, runtime);
            } catch (Throwable ignored) {
                // fall through to baseLoader
            }
        }
        if (splash == null && baseLoader != null) {
            try {
                splash = Class.forName(SPLASH_ACTIVITY, false, baseLoader);
            } catch (Throwable t) {
                L.e("locate " + SPLASH_ACTIVITY + " failed at app create (fallback only)", t);
                return;
            }
        }
        if (splash == null) {
            return;
        }
        // Hook the first APP-side declaration of onCreate(Bundle) in the
        // splash's hierarchy, never the framework Activity.onCreate itself.
        for (Class<?> c = splash; c != null && c != Activity.class && c != Object.class;
                c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("onCreate", Bundle.class);
                m.setAccessible(true);
                splashHooked = true;
                L.hook("mmb.splash.direct -> " + c.getName(), m, new XposedInterface.Hooker() {
                    @Override
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        Object self = L.self(chain);
                        if (self instanceof Activity
                                && SPLASH_ACTIVITY.equals(((Activity) self).getClass().getName())) {
                            jumpToMain((Activity) self);
                        }
                        return chain.proceed();
                    }
                });
                return;
            } catch (NoSuchMethodException ignored) {
                // walk up to the next app-side superclass
            } catch (Throwable t) {
                L.e("scan onCreate declarations failed", t);
                return;
            }
        }
    }

    /**
     * Hook one Application dispatch point (non-final, never overridden by
     * apps, invoked virtually by the framework for every activity creation).
     * The first argument is the Activity, so the splash is matched here and
     * the jump fires before the host's splash flow can resume.
     */
    private static void hookApplicationDispatch(final String id, final String methodName,
            final int paramCount) {
        try {
            Method m = null;
            for (Method cand : android.app.Application.class.getDeclaredMethods()) {
                if (methodName.equals(cand.getName())
                        && cand.getParameterTypes().length == paramCount) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                L.e("locate Application." + methodName + " failed", new Throwable("missing"));
                return;
            }
            m.setAccessible(true);
            L.hook(id, m, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object self = L.self(chain);
                    if (self instanceof Activity
                            && SPLASH_ACTIVITY.equals(((Activity) self).getClass().getName())) {
                        jumpToMain((Activity) self);
                    }
                    return chain.proceed();
                }
            });
        } catch (Throwable t) {
            L.e("hook " + id + " failed", t);
        }
    }

    /** Generic Activity.onCreate hook: works on every other host on this device. */
    private static void hookActivityCreateFallback() {
        Method create;
        try {
            create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed (no fallback)", t);
            return;
        }
        create.setAccessible(true);
        L.hook("mmb.splash.jump", create, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = L.self(chain);
                if (diagHits < 5) {
                    diagHits++;
                    L.i("create hit#" + diagHits + " self=" + self
                            + " cls=" + (self == null ? "null" : self.getClass().getName()));
                }
                if (self instanceof Activity
                        && SPLASH_ACTIVITY.equals(((Activity) self).getClass().getName())) {
                    jumpToMain((Activity) self);
                }
                return chain.proceed();
            }
        });
    }

    private static void jumpToMain(Activity a) {
        if (jumped || jumpFailed) {
            return;
        }
        try {
            Intent i = new Intent();
            i.setClassName(a.getPackageName(), MAIN_ACTIVITY);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            a.startActivity(i);
            a.finish();
            try {
                a.overridePendingTransition(0, 0);
            } catch (Throwable ignored) {
            }
            jumped = true;
            L.i("jump to " + MAIN_ACTIVITY + " from " + SPLASH_ACTIVITY);
        } catch (Throwable t) {
            jumpFailed = true;
            L.e("jump failed, host splash flow continues", t);
        }
    }
}
