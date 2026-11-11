package com.ghostroot.wkblock;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * v1.0.1 — remove both ad surfaces of com.snda.wifilocating (WiFi 万能钥匙).
 *
 * Reconnaissance (device, cold start with dense dumpsys/screencap sampling):
 *  - launcher = com.wifitutu.ui.launcher.LauncherActivity, whose first frame
 *    was delayed to +8s254ms by an embedded splash ad. The ad view tree lives
 *    INSIDE this activity: app:id/ad_layout, ad_image, skipped,
 *    wf_union_splash_root_rl / wf_union_splash_skip_tv (掌盟 adxsdk union
 *    splash layout).
 *  - after the home page appears, a translucent activity
 *    com.zm.adxsdk.core.activity.WfTranslucentNoTitleActivity (+3s60ms)
 *    overlays an interstitial popup ad: wf_interstitial_ad_root_ll,
 *    wf_interstitial_close_iv, "摇动或点击图片 / 立即打开".
 *
 * Strategy (keeps the host's own flow intact, no deep-link jump needed):
 *  1) Activity.onCreate hook (virtual entrypoint proven on this ROM; final
 *     performCreate is bypassed by AOT) — finish adxsdk ad activities before
 *     their content is shown.
 *  2) For LauncherActivity, poll its decor view on the main looper: click
 *     the host's own skip/close controls (normal ad-close path, so the home
 *     page arrives immediately) and hide only the ad image layers. Polling
 *     tolerates ads inflated asynchronously.
 *
 * All matching is by runtime class name / resource entry name, so no host
 * class has to be resolved with Class.forName.
 */
public final class Hooks {

    private static final String PKG = "com.snda.wifilocating";
    private static final String LAUNCHER = "com.wifitutu.ui.launcher.LauncherActivity";

    /** Host controls that dismiss an ad through the host's own close path. */
    private static final String[] SKIP_IDS = {
            "skipped",
            "wf_union_splash_skip_tv",
            "wf_interstitial_close_iv",
    };

    /** Pure image layers: hiding these does not take the skip button with them. */
    private static final String[] MATERIAL_IDS = {
            "ad_image",
            "wf_union_splash_material_iv",
            "wf_union_splash_blur_iv",
    };

    private static volatile int actDiag = 0;
    private static volatile boolean skipLoopStarted = false;
    private static final java.util.Set<String> clickedSkips =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private Hooks() {
    }

    public static synchronized void install(ClassLoader cl) {
        Method create;
        try {
            create = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        } catch (Throwable t) {
            L.e("locate Activity.onCreate failed (no hooks)", t);
            return;
        }
        create.setAccessible(true);
        L.hook("wk.act.create", create, new XposedInterface.Hooker() {
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
                    if (isAdActivity(cls)) {
                        finishAdActivity(a, cls);
                    } else if (LAUNCHER.equals(cls) && !skipLoopStarted) {
                        skipLoopStarted = true;
                        startSkipLoop(a);
                    }
                }
                return chain.proceed();
            }
        });
    }

    /**
     * Ad hosts that the app launches by itself. Four families were found in
     * the manifest / cold-start traces:
     *   com.zm.adxsdk.core.activity.*                (WfTranslucentNoTitleActivity)
     *   com.wifi.business.core.interstitial.*       (WifiInterstitialActivity)
     *   com.link.ida.sdk.kernel.core.activity.*     (WfInterstitialActivity)
     *   com.vivo.mobilead.unified.interstitial.*    (InterstitialActivity)
     * Ad LANDING pages (AdDyMallPageActivity / AdWebPageActivity /
     * AdRecommendActivity) are intentionally NOT blocked: those open only
     * after an explicit user tap.
     */
    private static boolean isAdActivity(String cls) {
        return cls.startsWith("com.zm.adxsdk.core.activity.")
                || cls.startsWith("com.wifi.business.core.interstitial.")
                || cls.startsWith("com.link.ida.sdk.kernel.core.activity.")
                || cls.startsWith("com.vivo.mobilead.unified.interstitial.")
                || (cls.contains("Interstitial") && cls.endsWith("Activity"))
                || cls.contains("SplashAdActivity");
    }

    private static void finishAdActivity(Activity a, String cls) {
        try {
            a.finish();
            a.overridePendingTransition(0, 0);
            L.i("BLOCK ad activity: " + cls);
        } catch (Throwable t) {
            L.e("finish ad activity failed " + cls, t);
        }
    }

    private static void startSkipLoop(Activity a) {
        final Handler h = new Handler(Looper.getMainLooper());
        final int[] ticks = {0};
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing() || a.isDestroyed()) {
                    return;
                }
                ticks[0]++;
                try {
                    skipSplashAd(a, ticks[0]);
                } catch (Throwable t) {
                    L.e("skip loop failed", t);
                }
                // 90 x 200ms = 18s, covers late-loaded ads (host measured
                // +8s254ms before its first frame on an unmodified cold start).
                if (ticks[0] < 90) {
                    h.postDelayed(this, 200);
                }
            }
        };
        h.postDelayed(task, 50);
        L.i("splash skip loop started");
    }

    private static void skipSplashAd(Activity a, int tick) {
        View decor = a.getWindow().getDecorView();
        if (decor == null) {
            return;
        }
        android.content.res.Resources res = a.getResources();

        // Click the host's own skip / close controls so it runs its normal
        // ad-close path (immediately advancing to the home page). Ads are
        // inflated asynchronously, so keep retrying until each id appears.
        // performClick() still reaches the listener even if a layer was
        // hidden, so no visibility check here.
        for (String name : SKIP_IDS) {
            if (clickedSkips.contains(name)) {
                continue;
            }
            int id = res.getIdentifier(name, "id", PKG);
            if (id == 0) {
                continue;
            }
            View v = decor.findViewById(id);
            if (v != null) {
                v.performClick();
                clickedSkips.add(name);
                L.i("auto-click ad control: " + name + " @tick " + tick);
            }
        }

        // Hide only image layers. Hiding the parent (ad_layout) in v1.0.0
        // made the skip button unreachable, so the host sat out the full
        // countdown and the home page still took +10s.
        for (String name : MATERIAL_IDS) {
            int id = res.getIdentifier(name, "id", PKG);
            if (id == 0) {
                continue;
            }
            View v = decor.findViewById(id);
            if (v != null && v.getVisibility() != View.GONE) {
                v.setVisibility(View.GONE);
                L.i("hide ad material: " + name);
            }
        }

        // Last resort: no skip control exists at all -> hide the container
        // after a grace period so at least nothing is displayed.
        if (tick == 25 && clickedSkips.isEmpty()) {
            int id = res.getIdentifier("ad_layout", "id", PKG);
            if (id != 0) {
                View v = decor.findViewById(id);
                if (v != null) {
                    v.setVisibility(View.GONE);
                    L.i("fallback: hide ad_layout (no skip control found)");
                }
            }
        }
    }
}
