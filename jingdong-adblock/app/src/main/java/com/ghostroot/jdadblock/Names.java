package com.ghostroot.jdadblock;

import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * com.jingdong.app.mall 16.1.50 splash rules.
 *
 * Evidence 1 - uiautomator dump taken during the 5s "11.11 巅峰52小时" ad:
 *   RelativeLayout  content-desc="启动图广告"   [0,0][1080,2400]
 *   splash_skip_container  content-desc="跳过"  [896,0][1080,256]
 *     TextView "跳过"                          [896,184][1034,256]
 *
 * Evidence 2 - `dumpsys activity top` View Hierarchy:
 *   FrameLayout  app:id/splash_fragment
 *     com.jingdong.app.mall.ad.view.VideoSplashView
 *       SplashLinkageMask / SplashVideoPlayerView / SplashDefaultViewWrapper
 *       SplashBgView / AdButtonLottieView
 *   The whole ad lives in SplashFragment, hosted by MainFrameActivity; there
 *   is no separate splash Activity to finish.
 *
 * Dex side: com.jingdong.app.mall.ad.view.BaseSplashView has a static factory
 * C(3) and ~40 obfuscated single-letter methods, SplashCountDownView drives
 * the countdown, and SplashFragment.onCreateViews builds the tree. Hooking any
 * of those is version-fragile. Clicking splash_skip_container instead lets the
 * host run its own dismiss path (and keeps its ad exposure bookkeeping sane,
 * which matters because JD already showed a risk-control CAPTCHA on this device).
 */
public final class Names {

    /** The app's own skip control. */
    private static final Set<String> SKIP_IDS = new HashSet<>(Arrays.asList(
            "splash_skip_container", "tv_skip", "splash_skip", "skip_view",
            "tv_skip_ad", "splash_count_down"
    ));

    /**
     * The Fragment container is deliberately NOT collapsed: hiding it can leave
     * the Fragment in a half-dismissed state. The ad View classes below are
     * only logged in v1, not hidden -- the click is what ends the ad.
     */
    private static final Set<String> AD_IDS = new HashSet<>(Arrays.asList(
            "splash_fragment"
    ));

    private static final ConcurrentHashMap<Integer, String> ID_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> CLS_CACHE = new ConcurrentHashMap<>();

    private Names() {
    }

    public static String idName(View v) {
        if (v == null) {
            return "";
        }
        int id;
        try {
            id = v.getId();
        } catch (Throwable t) {
            return "";
        }
        if (id == View.NO_ID || id <= 0) {
            return "";
        }
        String cached = ID_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        String name;
        try {
            name = v.getContext().getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            name = "";
        }
        if (name == null) {
            name = "";
        }
        ID_CACHE.put(id, name);
        return name;
    }

    public static boolean isSkip(String name) {
        return name != null && !name.isEmpty()
                && SKIP_IDS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isAdId(String name) {
        return name != null && !name.isEmpty()
                && AD_IDS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isAdClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = CLS_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        boolean r = c.startsWith("com.jingdong.app.mall.ad.view.");
        CLS_CACHE.put(cls, r);
        return r;
    }
}
