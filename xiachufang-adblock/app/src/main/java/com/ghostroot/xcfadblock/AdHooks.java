package com.ghostroot.xcfadblock;

import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;

/**
 * Installs view-level hooks. Every interceptor calls chain.proceed() first and wraps
 * its own work in try/catch, so a mistake here cannot change the app's control flow.
 *
 * Performance notes (these hooks run for EVERY View in the process):
 *  - Method arguments are read BEFORE chain.proceed(); the arg snapshot is only
 *    guaranteed valid in the pre-execution phase.
 *  - inspect() has a cheap reject path: a View with no id and a non-ad-SDK class
 *    cannot match anything, so it returns without touching Resources.
 *  - Resource names and class match results are cached in Names.
 *  - collapse() only sets GONE and never mutates LayoutParams: changing layout
 *    params forces requestLayout, which made long lists stutter on every hit.
 */
public final class AdHooks {

    private static volatile boolean installed = false;
    private static volatile boolean skipDone = false;

    /** Hot-path counters. Cheap, and useful to prove where the cost actually is. */
    private static final AtomicInteger INSPECTED = new AtomicInteger();
    private static final AtomicInteger HITS = new AtomicInteger();

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
        SplashTiming.install();
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
        L.hook("xcf.inflate", m, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // Read args first: post-proceed the snapshot is not guaranteed.
                Object idArg = L.arg(chain, 0);
                Object r = chain.proceed();
                if (r instanceof View) {
                    View v = (View) r;
                    String res = "";
                    if (idArg instanceof Integer) {
                        res = Names.entryName(v.getContext(), (Integer) idArg);
                    }
                    if (res.isEmpty()) {
                        res = Names.idName(v);
                    }
                    String cls = v.getClass().getName();
                    if (Names.isAdView(res, cls)) {
                        HITS.incrementAndGet();
                        L.i("inflate ad layout: " + res + " / " + cls);
                        collapse(v);
                    }
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
        L.hook("xcf.addView", m, new XposedInterface.Hooker() {
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
        L.hook("xcf.attach", m, new XposedInterface.Hooker() {
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
            // Cheap reject: no id and not an ad SDK class => nothing can match.
            // This is the path almost every View in a list takes.
            if (id <= 0 && !Names.isAdClass(cls)) {
                return;
            }
            int n = INSPECTED.incrementAndGet();
            if ((n & 1023) == 0) {
                L.i("inspect budget: calls=" + n + " hits=" + HITS.get());
            }
            String name = Names.idName(v);
            if (Names.isSkip(name)) {
                HITS.incrementAndGet();
                L.i("skip view via " + from + ": " + name + " / " + cls);
                autoSkip(v);
                return;
            }
            if (Names.isAdView(name, cls)) {
                HITS.incrementAndGet();
                L.i("ad view via " + from + ": " + name + " / " + cls);
                collapse(v);
            }
        } catch (Throwable t) {
            L.e("inspect failed", t);
        }
    }

    /**
     * Fire the app's own skip action from inside the process. This is a method call,
     * not an accessibility-service click. The splash container is built before its
     * listeners, so retry a couple of times and walk clickable descendants.
     */
    private static void autoSkip(final View v) {
        if (skipDone) {
            return;
        }
        try {
            v.post(new Runnable() {
                @Override
                public void run() {
                    attemptSkip(v, 0);
                }
            });
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptSkip(v, 1);
                }
            }, 400);
            v.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptSkip(v, 2);
                }
            }, 1200);
        } catch (Throwable t) {
            L.e("autoSkip post failed", t);
        }
    }

    private static void attemptSkip(View v, int round) {
        if (skipDone) {
            return;
        }
        boolean clicked = false;
        boolean touched = false;
        try {
            clicked = v.performClick();
            if (!clicked) {
                touched = tap(v);
            }
            if (!clicked) {
                clicked = clickDescendants(v);
            }
        } catch (Throwable t) {
            L.e("attemptSkip failed", t);
        }
        L.i("skip attempt=" + round + " click=" + clicked + " touch=" + touched
                + " view=" + v.getClass().getName());
        // Only a real click listener firing counts as done; a consumed touch may
        // just be a pressed state, so allow the scheduled retries to run.
        if (clicked) {
            skipDone = true;
        }
    }

    private static boolean clickDescendants(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            try {
                if (c.isClickable() || c.hasOnClickListeners()) {
                    if (c.performClick() || tap(c)) {
                        return true;
                    }
                }
                if (c instanceof ViewGroup && clickDescendants(c)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Dispatch a real DOWN/UP pair. Skip large containers so we never tap ad creatives. */
    private static boolean tap(View v) {
        try {
            int w = v.getWidth();
            int h = v.getHeight();
            if (w <= 0 || h <= 0 || w > 500 || h > 500
                    || v.getVisibility() != View.VISIBLE) {
                return false;
            }
            long t = SystemClock.uptimeMillis();
            float x = w / 2f;
            float y = h / 2f;
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(t + 30, t + 30, MotionEvent.ACTION_UP, x, y, 0);
            try {
                return v.dispatchTouchEvent(down) | v.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Hide instead of removeView: keeps the layout intact and avoids black screen.
     * Deliberately does NOT touch LayoutParams (see class comment).
     */
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
