package com.ghostroot.jdadblock;

import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * View-level splash skip for com.jingdong.app.mall.
 *
 * Every interceptor proceeds first and catches everything, so a rule mistake
 * can never change the host's control flow. All ids are resolved through a
 * cache because these hooks run for every View in a very large app.
 *
 * v1 deliberately does NOT hide the ad Views. JD already showed a
 * risk-control CAPTCHA on this device; collapsing the ad root while the
 * host countdown is still running risks leaving a stuck window. Pressing the
 * app's own skip control is the least invasive way out, and it happens during
 * inflate/addView/attach, i.e. before the first ad frame is drawn.
 */
public final class AdHooks {

    private static final boolean HIDE_AD_VIEW = false;
    private static final long CLICK_WINDOW_MS = 10_000L;
    /** The skip container starts GONE and gets its listener only when the countdown begins. */
    private static final long[] RETRY_DELAYS = {0L, 200L, 500L, 1000L, 1800L, 3000L};

    private static volatile boolean installed = false;
    private static volatile boolean skipClicked = false;
    private static volatile long lastClickAt = 0L;

    private static final AtomicInteger INSPECTED = new AtomicInteger();
    private static final AtomicInteger HITS = new AtomicInteger();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private AdHooks() {
    }

    public static synchronized void install(ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        L.i("install begin, classloader=" + cl);
        hookInflate();
        hookAddView();
        hookAttach();
        L.i("install done");
    }

    private static void hookInflate() {
        Method m = null;
        try {
            m = LayoutInflater.class.getDeclaredMethod(
                    "inflate", int.class, ViewGroup.class, boolean.class);
        } catch (Throwable ignored) {
        }
        if (m == null) {
            return;
        }
        L.hook("jd.inflate", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (r instanceof View) {
                    inspect((View) r, "inflate");
                }
                return r;
            }
        });
    }

    private static void hookAddView() {
        Method m = null;
        try {
            m = ViewGroup.class.getMethod(
                    "addView", View.class, int.class, ViewGroup.LayoutParams.class);
        } catch (Throwable ignored) {
        }
        if (m == null) {
            return;
        }
        L.hook("jd.addView", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object a0 = L.arg(chain, 0);
                Object r = chain.proceed();
                if (a0 instanceof View) {
                    inspect((View) a0, "addView");
                }
                return r;
            }
        });
    }

    private static void hookAttach() {
        Method m = null;
        try {
            m = View.class.getDeclaredMethod("onAttachedToWindow");
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
        if (m == null) {
            return;
        }
        L.hook("jd.attach", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                Object self = L.self(chain);
                if (self instanceof View) {
                    inspect((View) self, "attach");
                }
                return r;
            }
        });
    }

    private static boolean once(String key) {
        try {
            if (LOGGED.size() >= 128) {
                return false;
            }
            return LOGGED.add(key);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void inspect(View v, String from) {
        try {
            String cls = v.getClass().getName();
            int id;
            try {
                id = v.getId();
            } catch (Throwable t) {
                id = View.NO_ID;
            }
            boolean adCls = Names.isAdClass(cls);
            if (id <= 0 && !adCls) {
                return;
            }
            int n = INSPECTED.incrementAndGet();
            if ((n & 2047) == 0) {
                L.i("inspect budget: calls=" + n + " hits=" + HITS.get());
            }
            String name = Names.idName(v);
            if (Names.isSkip(name)) {
                HITS.incrementAndGet();
                if (once("skip:" + name)) {
                    L.i("skip " + from + ": " + name + " / " + cls);
                }
                autoClick(v);
                return;
            }
            if (adCls || Names.isAdId(name)) {
                HITS.incrementAndGet();
                if (once("ad:" + name + ":" + cls)) {
                    L.i("ad view " + from + ": " + name + " / " + cls);
                }
                if (HIDE_AD_VIEW) {
                    collapse(v);
                }
            }
        } catch (Throwable t) {
            L.e("inspect failed", t);
        }
    }

    /**
     * A cold start normally rebuilds the process (so the statics reset), but
     * returning from background can show the boot image again in the same
     * process. Allow a new click round after the window expires.
     */
    private static void autoClick(final View v) {
        long now = SystemClock.uptimeMillis();
        if (skipClicked && now - lastClickAt < CLICK_WINDOW_MS) {
            return;
        }
        skipClicked = false;
        for (int i = 0; i < RETRY_DELAYS.length; i++) {
            final int round = i;
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    tryClick(v, round);
                }
            };
            try {
                if (RETRY_DELAYS[i] == 0) {
                    v.post(r);
                } else {
                    v.postDelayed(r, RETRY_DELAYS[i]);
                }
            } catch (Throwable t) {
                L.e("autoClick post failed", t);
            }
        }
    }

    private static void tryClick(View v, int round) {
        if (skipClicked) {
            return;
        }
        boolean ok = false;
        try {
            ok = v.performClick();
            if (!ok) {
                ok = parentClick(v);
            }
            if (!ok) {
                ok = childClick(v);
            }
        } catch (Throwable t) {
            L.e("tryClick failed", t);
        }
        L.i("click round=" + round + " ok=" + ok
                + " id=" + Names.idName(v) + " cls=" + v.getClass().getName());
        if (ok) {
            skipClicked = true;
            lastClickAt = SystemClock.uptimeMillis();
            verifyDismissed(v);
        }
    }

    /** performClick() returns true even if a listener ignored a GONE View, so check the result. */
    private static void verifyDismissed(final View v) {
        try {
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        L.i("postClick stillAttached=" + v.isAttachedToWindow()
                                + " visibility=" + v.getVisibility()
                                + " id=" + Names.idName(v));
                    } catch (Throwable ignored) {
                    }
                }
            }, 1200);
        } catch (Throwable ignored) {
        }
    }

    /** The skip label often sits inside a clickable container. */
    private static boolean parentClick(View v) {
        ViewParent p = v.getParent();
        int guard = 0;
        while (p instanceof View && guard++ < 4) {
            View pv = (View) p;
            try {
                if (pv.isClickable() || pv.hasOnClickListeners()) {
                    if (pv.performClick()) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            p = pv.getParent();
        }
        return false;
    }

    private static boolean childClick(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            try {
                if ((c.isClickable() || c.hasOnClickListeners()) && c.performClick()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void collapse(View v) {
        try {
            if (v.getVisibility() == View.GONE) {
                return;
            }
            v.setVisibility(View.GONE);
        } catch (Throwable t) {
            L.e("collapse failed", t);
        }
    }
}
