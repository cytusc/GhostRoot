package com.ghostroot.smzdmadblock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * v1.4.0: deep-link style direct entry, mirroring taobao-adblock v1.7.0.
 *
 * smzdm's WelComeActivity shows the splash ad and, when an ad was delivered,
 * holds the user for the countdown before HomeActivity becomes usable (with no
 * ad delivered the host itself jumps to Home almost instantly). Hooking
 * Activity.onCreate and jumping BEFORE proceed skips every WelCome wait: the
 * home activity is created as early as the process allows, the same way an ad
 * deep-link enters the app.
 *
 * Safety: the jump is issued once per process; REORDER_TO_FRONT keeps it
 * idempotent when the host's own navigation fires later; on any failure the
 * flags reset and the existing view-level auto-close logic (v1.0-v1.3) keeps
 * working unchanged.
 */
public final class SplashJump {

    private static final String SPLASH_ACTIVITY = "com.smzdm.client.android.app.WelComeActivity";
    private static final String MAIN_ACTIVITY = "com.smzdm.client.android.app.HomeActivity";

    private static volatile boolean jumped = false;
    private static volatile boolean jumpFailed = false;

    private SplashJump() {
    }

    public static synchronized void install(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        final Class<?> splash;
        try {
            splash = Class.forName(SPLASH_ACTIVITY, false, cl);
        } catch (Throwable t) {
            L.e("locate " + SPLASH_ACTIVITY + " failed (no jump)", t);
            return;
        }
        Method create;
        try {
            create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed (no jump)", t);
            return;
        }
        L.hook("smzdm.splash.jump", create, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = L.self(chain);
                if (splash.isInstance(self)) {
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
