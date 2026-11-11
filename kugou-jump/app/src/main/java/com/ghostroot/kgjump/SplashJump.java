package com.ghostroot.kgjump;

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

    private static final String SPLASH_ACTIVITY = "com.kugou.android.app.splash.SplashActivity";
    private static final String MAIN_ACTIVITY = "com.kugou.android.app.MediaActivity";

    private static volatile boolean jumped = false;
    private static volatile boolean jumpFailed = false;

    private SplashJump() {
    }

    public static synchronized void install(ClassLoader cl) {
        // No Class.forName pre-resolution: some hosts load their splash
        // activity from a dynamically added dex that is not visible at
        // onPackageLoaded time. Hook the framework method once and match the
        // activity by class NAME at runtime instead (cheap string equals,
        // cached by equals-short-circuit on the common path).
        Method create;
        try {
            create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed (no jump)", t);
            return;
        }
        L.hook("kg.splash.jump", create, new XposedInterface.Hooker() {
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
