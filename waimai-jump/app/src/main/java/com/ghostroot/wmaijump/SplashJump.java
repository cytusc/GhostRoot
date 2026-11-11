package com.ghostroot.wmaijump;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * Deep-link style direct entry (pattern proven in taobao-adblock v1.7.0).
 *
 * Hook Activity.onCreate, filtered to the launch/splash activity; BEFORE
 * chain.proceed() navigate to the home activity and finish the splash. The
 * splash's ad countdown and init waits are skipped entirely: the home
 * activity is created as early as the process allows, the same way an ad
 * deep-link enters the app.
 *
 * Safety: issued once per process; REORDER_TO_FRONT keeps it idempotent when
 * the host's own navigation fires later; on any failure the flags reset and
 * the host's normal splash flow continues unchanged.
 */
public final class SplashJump {

    private static final String SPLASH_ACTIVITY = "com.sankuai.meituan.takeoutnew.ui.page.boot.WelcomeActivity";
    private static final String MAIN_ACTIVITY = "com.sankuai.waimai.business.page.homepage.MainActivity";

    private static volatile boolean jumped = false;
    private static volatile boolean jumpFailed = false;

    private SplashJump() {
    }

    public static synchronized void install(ClassLoader cl) {
        // Hook Activity.performCreate instead of Activity.onCreate:
        // hosts whose splash overrides onCreate (or swaps in a custom
        // Instrumentation, observed on com.manmanbuy.bijia) dispatch away
        // from the framework methods, and this ROM's AOT-compiled invoke-super
        // bypasses the hook entrypoint. performCreate is FINAL on
        // android.app.Activity, so no host code can override it and every
        // activity creation must pass through it, before any host onCreate
        // code. Class names are matched at runtime because some hosts load
        // their splash from a dynamically added dex that is not visible at
        // onPackageLoaded time.
        Method create = null;
        try {
            for (Method m : Activity.class.getDeclaredMethods()) {
                if ("performCreate".equals(m.getName())) {
                    Class<?>[] ps = m.getParameterTypes();
                    if (ps.length == 1 && ps[0] == Bundle.class) {
                        create = m;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            L.e("scan performCreate failed (no jump)", t);
        }
        if (create == null) {
            L.e("locate Activity.performCreate failed (no jump)", new Throwable("performCreate missing"));
            return;
        }
        create.setAccessible(true);
        L.hook("wm.splash.jump", create, new XposedInterface.Hooker() {
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
