package com.ghostroot.tbadblock;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
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
 * Whole-window bootimage suppression for com.taobao.taobao.
 *
 * Live evidence (3 cold starts, 10.66.0): Welcome draws a white "淘" launch
 * page, then the app adds a FrameLayout app:id/fl_root_view on TOP of
 * TBMainActivity. That window contains FilletLinearLayout
 * app:id/fl_animation_view (the double-11 brand animation), ll_close /
 * tv_close / tv_countdown, and the creative. It is not an Activity, so there
 * is no splash Activity to finish.
 *
 * v1.1 only pressed ll_close. That still leaves the brand-animation window
 * on screen when the server sends no ad (no skip control is inflated), and
 * it leaves a visible transition even when the click succeeds. v1.2 hides
 * the entire bootimage window as soon as its root is added and keeps it
 * hidden for the full animation/countdown duration. We intentionally do NOT
 * remove the View from its parent: the host keeps a reference to it and
 * later dismisses it itself. GONE is enough to make the home page visible
 * and interactive underneath, and the host's own dismiss path stays intact
 * (we still press ll_close when it exists).
 *
 * Every interceptor proceeds first and catches everything, so a rule mistake
 * can never change the host's control flow. All ids are resolved through a
 * cache because these hooks run for every View in a very large app.
 */
public final class AdHooks {

    /** Cover the whole brand animation / countdown; the host may re-show the window. */
    private static final long[] SUPPRESS_DELAYS =
            {0L, 60L, 150L, 300L, 600L, 1000L, 1600L, 2500L, 4000L, 6000L};
    private static final long CLICK_WINDOW_MS = 10_000L;
    /** The skip container starts GONE and gets its listener only when the countdown begins. */
    private static final long[] RETRY_DELAYS = {0L, 200L, 500L, 1000L, 1800L, 3000L};

    private static volatile boolean installed = false;
    private static volatile boolean skipClicked = false;
    private static volatile long lastClickAt = 0L;
    private static volatile View suppressedRoot = null;
    private static volatile long lastSuppressAt = 0L;
    private static volatile Handler mainHandler = null;

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
        L.hook("tb.inflate", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                if (r instanceof View) {
                    guardedInspect((View) r, "inflate");
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
        L.hook("tb.addView", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object a0 = L.arg(chain, 0);
                Object r = chain.proceed();
                if (a0 instanceof View) {
                    guardedInspect((View) a0, "addView");
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
        L.hook("tb.attach", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                Object self = L.self(chain);
                if (self instanceof View) {
                    guardedInspect((View) self, "attach");
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

    /**
     * Nested hooks (inspect -> performClick -> host creates Views -> attach)
     * must not re-enter the rule engine. Only the inspection is guarded;
     * chain.proceed() always runs inside the hooker itself.
     */
    private static void guardedInspect(View v, String from) {
        if (!L.enterInspect()) {
            return;
        }
        try {
            inspect(v, from);
        } finally {
            L.exitInspect();
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
                    L.i("ad view " + from + ": " + name + " / " + cls
                            + " act=" + activityName(v));
                }
                if (Names.isWindowRoot(name)) {
                    if (isBootImageWindow(v)) {
                        suppressWindow(v);
                    } else {
                        recheckLater(v);
                    }
                } else if (adCls) {
                    // The brand-animation container. GONE before the first frame.
                    setGone(v);
                }
            }
        } catch (Throwable t) {
            L.e("inspect failed", t);
        }
    }

    /**
     * The bootimage root is a plain FrameLayout, so the id alone is not
     * specific enough to be safe on a huge app. Require a bootimage child
     * (the animation container or the skip controls) before hiding anything.
     * The children are all added before the root itself reaches addView, as
     * the live hook log shows.
     */
    private static boolean isBootImageWindow(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        ViewGroup root = (ViewGroup) v;
        int budget = 160;
        for (int i = 0; i < root.getChildCount() && budget > 0; i++) {
            View c = root.getChildAt(i);
            budget--;
            if (c == null) {
                continue;
            }
            String n = Names.idName(c);
            if (Names.isAdId(n) || Names.isSkip(n)
                    || Names.isAdClass(c.getClass().getName())) {
                return true;
            }
            if (c instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) c;
                for (int j = 0; j < g.getChildCount() && budget > 0; j++) {
                    View cc = g.getChildAt(j);
                    budget--;
                    if (cc == null) {
                        continue;
                    }
                    String nn = Names.idName(cc);
                    if (Names.isAdId(nn) || Names.isSkip(nn)
                            || Names.isAdClass(cc.getClass().getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * inflate() can expose the root before its children are attached. Give the
     * host a few frames to finish building the window, then check again.
     */
    private static void recheckLater(final View v) {
        if (!once("root-recheck:" + Names.idName(v))) {
            return;
        }
        L.i("bootimage root not ready, recheck scheduled: " + Names.idName(v));
        long[] delays = {120L, 400L, 900L};
        for (long delay : delays) {
            try {
                Handler h = mainHandler;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    mainHandler = h;
                }
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (isBootImageWindow(v)) {
                                suppressWindow(v);
                            }
                        } catch (Throwable t) {
                            L.e("root recheck failed", t);
                        }
                    }
                }, delay);
            } catch (Throwable t) {
                L.e("root recheck post failed", t);
            }
        }
    }

    /** Hide the whole window and keep it hidden while the host plays its animation. */
    private static void suppressWindow(final View v) {
        long now = SystemClock.uptimeMillis();
        if (suppressedRoot == v && now - lastSuppressAt < CLICK_WINDOW_MS) {
            return;
        }
        suppressedRoot = v;
        lastSuppressAt = now;
        L.i("suppress bootimage window: " + Names.idName(v)
                + " / " + v.getClass().getName() + " act=" + activityName(v));
        for (long delay : SUPPRESS_DELAYS) {
            postGone(v, delay);
        }
    }

    private static void postGone(final View v, long delay) {
        try {
            Handler h = mainHandler;
            if (h == null) {
                h = new Handler(Looper.getMainLooper());
                mainHandler = h;
            }
            final Handler target = h;
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    setGone(v);
                }
            };
            if (delay <= 0) {
                target.post(r);
            } else {
                target.postDelayed(r, delay);
            }
        } catch (Throwable t) {
            L.e("postGone failed", t);
        }
    }

    private static void setGone(View v) {
        try {
            if (v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
            }
        } catch (Throwable t) {
            L.e("setGone failed", t);
        }
    }

    private static String activityName(View v) {
        try {
            Context c = v.getContext();
            int guard = 0;
            while (c instanceof ContextWrapper && guard++ < 8) {
                if (c instanceof Activity) {
                    return c.getClass().getName();
                }
                c = ((ContextWrapper) c).getBaseContext();
            }
        } catch (Throwable ignored) {
        }
        return "?";
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
        if (!v.isAttachedToWindow()) {
            L.i("click skipped, view detached, round=" + round);
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

}
