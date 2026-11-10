package com.ghostroot.smzdmadblock;

import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * View-level ad removal for com.smzdm.client.android.
 *
 * Every interceptor proceeds first and catches everything, so a rule mistake can
 * never change the host's control flow.
 *
 * Cost control (these run for every View in the process):
 *  - arguments are read before chain.proceed()
 *  - a View with no id and a non-ad-SDK class returns immediately
 *  - resource names / class matches / splash-Activity tests are all cached
 *  - collapse() only sets GONE and never mutates LayoutParams (that forces
 *    requestLayout and makes lists stutter)
 */
public final class AdHooks {

    private static volatile boolean installed = false;
    private static volatile boolean skipClicked = false;

    private static final AtomicInteger INSPECTED = new AtomicInteger();
    private static final AtomicInteger HITS = new AtomicInteger();

    /**
     * The splash builds ~20 ad-ish children in one burst, and every one of them
     * passes through inflate + addView + attach. Logging each occurrence turned a
     * single cold start into 50 log lines, so steady-state hits are logged once.
     */
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
        L.hook("smzdm.inflate", m, new XposedInterface.Hooker() {
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
        L.hook("smzdm.addView", m, new XposedInterface.Hooker() {
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
        L.hook("smzdm.attach", m, new XposedInterface.Hooker() {
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

    /** True only the first time a given key is seen; bounded so it cannot grow forever. */
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
            if ((n & 1023) == 0) {
                L.i("inspect budget: calls=" + n + " hits=" + HITS.get());
            }
            String name = Names.idName(v);
            if (Names.isSkip(name)) {
                HITS.incrementAndGet();
                L.i("skip " + from + ": " + name + " / " + cls);
                autoClick(v);
                return;
            }
            if (adCls || Names.isAdId(name)) {
                HITS.incrementAndGet();
                if (once("ad:" + name)) {
                    L.i("ad " + from + ": " + name + " / " + cls);
                }
                if (Names.isBubbleId(name)) {
                    collapseBubble(v);
                } else {
                    collapse(v);
                }
                return;
            }
            // Generic names like iv_image / player_view exist on normal screens too,
            // so they only count while the splash Activity is the host.
            if (Names.isSplashOnlyId(name)) {
                String act = Names.activityName(v);
                if (Names.isSplashActivity(act)) {
                    HITS.incrementAndGet();
                    if (once("splash:" + name)) {
                        L.i("splash ad " + from + ": " + name + " in " + act);
                    }
                    collapse(v);
                }
            }
        } catch (Throwable t) {
            L.e("inspect failed", t);
        }
    }

    /** The splash container is built before its listeners, so retry a few times. */
    private static void autoClick(final View v) {
        if (skipClicked) {
            return;
        }
        try {
            v.post(new Runnable() {
                @Override
                public void run() {
                    tryClick(v, 0);
                }
            });
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryClick(v, 1);
                }
            }, 400);
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryClick(v, 2);
                }
            }, 1200);
        } catch (Throwable t) {
            L.e("autoClick post failed", t);
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

    /**
     * The home promo bubble is not one View: the device shows iv_float as the
     * clickable circle at [864,1770][1044,1950] and iv_close as a *sibling*
     * button at [1011,1734][1080,1803]. Hiding only the artwork left a stray
     * "x" floating over the feed, so the whole wrapper goes when it is small.
     */
    private static void collapseBubble(View v) {
        String firstId = Names.idName(v);
        try {
            ViewParent p = v.getParent();
            if (p instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) p;
                int n = g.getChildCount();
                if (n <= 4) {
                    L.i("bubble container collapsed: " + n + " children, first=" + firstId);
                    collapse(g);
                    return;
                }
                for (int i = 0; i < n; i++) {
                    View c = g.getChildAt(i);
                    if (c != v && "iv_close".equalsIgnoreCase(Names.idName(c))) {
                        L.i("bubble close button collapsed next to " + firstId);
                        collapse(c);
                    }
                }
            }
        } catch (Throwable t) {
            L.e("collapseBubble failed", t);
        }
        collapse(v);
    }
}
