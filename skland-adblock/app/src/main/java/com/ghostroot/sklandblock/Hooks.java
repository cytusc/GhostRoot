package com.ghostroot.sklandblock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * v1.0.0 — two goals for com.hypergryph.skland (森空岛, SecNeo-packed, Compose UI):
 *
 * 1) Splash ad: the launcher is splash.SplashActivity, whose Compose
 *    SplashAdScreen shows the ad countdown. Hook the framework
 *    Activity.onCreate and, BEFORE proceed, deep-link straight into
 *    MainActivity and finish the splash — the same pattern verified on
 *    waimai / zhihu / smzdm. The ad side effect never gets a chance to run.
 *
 * 2) Minors / teenager popups: the host ships TeenagerTipsActivity,
 *    TeenagerTipsDialogV2, TeenagerLockActivity and
 *    TeenagerRestReminderActivity (Minors manager drives them). Their exact
 *    dex is decrypted at runtime by the packer, so we never Class.forName:
 *    we match Activity class names at runtime and finish those activities,
 *    and hook android.app.Dialog.show() (Compose Dialog wraps one) to catch
 *    the dialog form.
 *
 * All class-name checks are runtime equals/contains, so packed dex loading
 * order does not matter. Framework methods are virtual and route through the
 * patched entrypoint (final performCreate does NOT — see waimai findings).
 */
public final class Hooks {

    private static final String PKG = "com.hypergryph.skland";
    private static final String SPLASH = PKG + ".splash.SplashActivity";
    private static final String MAIN = PKG + ".MainActivity";

    /** Diagnostic hook modes, selected on-device without rebuilding. */
    public static final int MODE_NONE = 0;
    public static final int MODE_NO_DIALOG = 1;
    public static final int MODE_FULL = 2;

    private static volatile boolean jumped = false;
    private static volatile boolean jumpFailed = false;
    private static volatile int actDiag = 0;
    private static volatile int dlgDiag = 0;

    private Hooks() {
    }

    public static synchronized void install(ClassLoader cl, int mode) {
        L.i("install mode=" + mode);
        if (mode == MODE_NONE) {
            return;
        }
        hookActivityCreate();
        if (mode == MODE_FULL) {
            hookDialogShow();
        }
    }

    /** Single framework hook covers the splash jump and all minors activities. */
    private static void hookActivityCreate() {
        Method create;
        try {
            create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed (no hooks)", t);
            return;
        }
        create.setAccessible(true);
        L.hook("sk.act.create", create, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = L.self(chain);
                if (self instanceof Activity) {
                    Activity a = (Activity) self;
                    String cls = a.getClass().getName();
                    if (actDiag < 40) {
                        actDiag++;
                        L.i("ACT#" + actDiag + " " + cls);
                    }
                    if (SPLASH.equals(cls)) {
                        jumpToMain(a);
                    } else if (isMinorsUi(cls)) {
                        suppress(a, cls);
                    }
                }
                return chain.proceed();
            }
        });
    }

    /** Only tip/lock/reminder surfaces are suppressed, never TeenagerMainActivity. */
    private static boolean isMinorsUi(String cls) {
        return cls.contains("TeenagerTips")
                || cls.contains("TeenagerLock")
                || cls.contains("TeenagerRestReminder")
                || cls.contains("MinorsTips")
                || cls.contains("MinorsLock");
    }

    private static void suppress(Activity a, String cls) {
        try {
            a.finish();
            a.overridePendingTransition(0, 0);
            L.i("BLOCK minors activity: " + cls);
        } catch (Throwable t) {
            L.e("suppress failed " + cls, t);
        }
    }

    private static void jumpToMain(Activity a) {
        if (jumped || jumpFailed) {
            return;
        }
        try {
            Intent i = new Intent();
            i.setClassName(a.getPackageName(), MAIN);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            a.startActivity(i);
            a.finish();
            try {
                a.overridePendingTransition(0, 0);
            } catch (Throwable ignored) {
            }
            jumped = true;
            L.i("jump to " + MAIN + " from " + SPLASH);
        } catch (Throwable t) {
            jumpFailed = true;
            L.e("jump failed, host splash flow continues", t);
        }
    }

    /**
     * Compose dialogs are hosted by android.app.Dialog (DialogWrapper /
     * ComponentDialog). We record a few stack traces to identify the minors
     * dialog in the field, and dismiss the ones whose stack points at the
     * teenager/minors feature.
     */
    private static void hookDialogShow() {
        Method show;
        try {
            show = Dialog.class.getDeclaredMethod("show");
        } catch (Throwable t) {
            L.e("locate Dialog.show failed", t);
            return;
        }
        show.setAccessible(true);
        L.hook("sk.dlg.show", show, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object self = L.self(chain);
                String stack = Log.getStackTraceString(new Throwable());
                boolean minors = stack.contains("TeenagerTips")
                        || stack.contains("Minors")
                        || stack.contains("minors_mode")
                        || stack.contains("Teenager");
                if (dlgDiag < 25) {
                    dlgDiag++;
                    L.i("DLG#" + dlgDiag + " cls="
                            + (self == null ? "null" : self.getClass().getName())
                            + " minorsHint=" + minors + " stack=" + condense(stack));
                }
                Object ret = chain.proceed();
                if (minors && self instanceof Dialog) {
                    ((Dialog) self).dismiss();
                    L.i("BLOCK minors dialog: " + self.getClass().getName());
                }
                return ret;
            }
        });
    }

    private static String condense(String stack) {
        StringBuilder sb = new StringBuilder();
        String[] lines = stack.split("\n");
        int n = 0;
        for (String line : lines) {
            if (line.contains("java.lang.Thread")
                    || line.contains("getStackTraceString")) {
                continue;
            }
            if (n++ >= 6) {
                break;
            }
            sb.append(line.trim()).append(" | ");
        }
        return sb.toString();
    }
}
