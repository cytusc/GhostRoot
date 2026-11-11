package com.ghostroot.tbadblock;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;

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
 * v1.3 additionally removes the white "淘" launch page. Live window dumps
 * show Welcome is a translucent Activity (mIsTranslucent=true) and there is
 * NO separate "Starting com.taobao.taobao" window, so the white page is
 * drawn by Welcome's own PhoneWindow, not by system_server. Welcome declares
 * its own onResume() (classes10.dex), which gives us an exact, single-class
 * hook point -- no need to hook Activity.onResume for every Activity, and no
 * system-framework scope / zygote restart. We clear the window + decor
 * backgrounds and GONE the content children; GONE (not remove) keeps every
 * View findable by the host, so its own navigation logic is untouched.
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
        hookWelcome(cl);
        L.i("install done");
    }

    /**
     * Welcome overrides onResume() itself, so this hook fires once per cold
     * start and only in the launch Activity. If the class is ever missing
     * (split / renamed in a future release) we just log and keep the v1.2
     * bootimage suppression working.
     *
     * v1.4.0: also hook Activity.onCreate (framework class, instance-filtered
     * to Welcome) and clear the window synchronously right after onCreate.
     * At that point no frame has been drawn yet, so the very first frame
     * Welcome paints is already transparent — the onResume path stays as a
     * fallback for ROMs where the decor is rebuilt later.
     */
    private static void hookWelcome(ClassLoader cl) {
        if (cl == null) {
            return;
        }
        final Class<?> welcome;
        try {
            welcome = Class.forName("com.taobao.tao.welcome.Welcome", false, cl);
        } catch (Throwable t) {
            L.e("locate Welcome failed (launch page stays)", t);
            return;
        }
        Method m;
        try {
            m = welcome.getDeclaredMethod("onResume");
        } catch (Throwable t) {
            L.e("locate Welcome.onResume failed (launch page stays)", t);
            return;
        }
        L.hook("tb.welcome", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                Object self = L.self(chain);
                if (self instanceof Activity) {
                    hideLaunchPage((Activity) self);
                }
                return r;
            }
        });
        try {
            m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed", t);
            return;
        }
        L.hook("tb.welcome.create", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                Object self = L.self(chain);
                if (welcome.isInstance(self)) {
                    Activity a = (Activity) self;
                    if (once("welcome-early")) {
                        L.i("early clear Welcome window in onCreate");
                    }
                    clearLaunchPage(a);
                    hideLaunchPage(a);
                }
                return r;
            }
        });
    }

    /**
     * Make the whole Welcome window transparent. It is a translucent bridge
     * Activity; the home page (TBMainActivity) is a separate window that
     * appears underneath/after it, so nothing is lost by hiding its content.
     */
    private static void hideLaunchPage(final Activity a) {
        if (once("welcome-hidden")) {
            L.i("hide Welcome launch page: " + a.getClass().getName());
            dumpLaunchChildren(a);
        }
        long[] delays = {0L, 60L, 150L, 300L, 600L, 1000L, 1600L};
        for (long delay : delays) {
            try {
                Handler h = mainHandler;
                if (h == null) {
                    h = new Handler(Looper.getMainLooper());
                    mainHandler = h;
                }
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        clearLaunchPage(a);
                    }
                };
                if (delay <= 0) {
                    h.post(r);
                } else {
                    h.postDelayed(r, delay);
                }
            } catch (Throwable t) {
                L.e("hideLaunchPage post failed", t);
            }
        }
    }

    private static void clearLaunchPage(Activity a) {
        try {
            if (a.isFinishing() || a.isDestroyed()) {
                return;
            }
            Window w = a.getWindow();
            if (w == null) {
                return;
            }
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            View decor = w.getDecorView();
            if (decor == null) {
                return;
            }
            decor.setBackgroundColor(Color.TRANSPARENT);
            View content = decor.findViewById(android.R.id.content);
            if (content instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) content;
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (c != null && c.getVisibility() != View.GONE) {
                        c.setVisibility(View.GONE);
                    }
                }
            }
            // v1.5.0: the splash logo can also be an overlay added directly to
            // the decor (AndroidX SplashScreen compat keeps the system splash
            // alive as an in-app overlay view). Hide every decor child that is
            // not on the android.R.id.content path. Only GONE, never remove.
            if (decor instanceof ViewGroup && content != null) {
                ViewGroup d = (ViewGroup) decor;
                for (int i = 0; i < d.getChildCount(); i++) {
                    View c = d.getChildAt(i);
                    if (c != null && c.getVisibility() != View.GONE && !isOnContentPath(c, content)) {
                        c.setVisibility(View.GONE);
                    }
                }
            }
        } catch (Throwable t) {
            L.e("clearLaunchPage failed", t);
        }
    }

    /** True if {@code node} lies on the decor -> content ancestor path. */
    private static boolean isOnContentPath(View node, View content) {
        View v = content;
        while (v != null) {
            if (v == node) {
                return true;
            }
            ViewParent p = v.getParent();
            v = (p instanceof View) ? (View) p : null;
        }
        return false;
    }

    /** One-time structural evidence: confirms the white page is Welcome's own content. */
    private static void dumpLaunchChildren(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            if (decor instanceof ViewGroup) {
                ViewGroup d = (ViewGroup) decor;
                for (int i = 0; i < d.getChildCount(); i++) {
                    View c = d.getChildAt(i);
                    if (c != null) {
                        L.i("welcome decor child[" + i + "]: " + Names.idName(c)
                                + " / " + c.getClass().getName());
                    }
                }
            }
            View content = decor.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) {
                L.i("welcome content is not a ViewGroup");
                return;
            }
            ViewGroup g = (ViewGroup) content;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c != null) {
                    L.i("welcome child[" + i + "]: " + Names.idName(c)
                            + " / " + c.getClass().getName());
                }
            }
        } catch (Throwable t) {
            L.e("dumpLaunchChildren failed", t);
        }
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
                if (a0 instanceof View) {
                    // Hide BEFORE the view enters the hierarchy so the boot
                    // image never draws a single frame (the live log shows a
                    // 30-frame Choreographer skip right after this addView).
                    // Setting a flag cannot change the host's control flow:
                    // the addView itself still runs, exactly as the host expects.
                    preGone((View) a0);
                }
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
     * Cheap check before chain.proceed(): only cached id/class lookups, then
     * GONE. The full rule engine still runs after proceed() via inspect().
     */
    private static void preGone(View v) {
        try {
            boolean hit = false;
            int id = v.getId();
            if (id > 0) {
                String n = Names.idName(v);
                hit = Names.isWindowRoot(n) || Names.isAdId(n);
            }
            if (!hit) {
                hit = Names.isAdClass(v.getClass().getName());
            }
            if (hit) {
                setGone(v);
            }
        } catch (Throwable t) {
            L.e("preGone failed", t);
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
