package com.ghostroot.smzdmadblock;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * com.smzdm.client.android ad-view rules, derived from the unpacked resources:
 *
 *   res/layout/activity_welcome.xml
 *     tv_skip     "跳过 3"   -> the app's own skip control
 *     tv_ads_tag  "广告"     -> ad marker
 *     iv_image / player_view / pv_big / lav_click -> the ad creative itself
 *   res/layout/layout_splash_view_countdown.xml
 *     cl_jump / tv_jump / tv_time -> a second splash implementation
 *   com.miui.zeus.mimo.sdk (also bundled)
 *     mimo_splash_skip / mimo_splash_tv_adMark / mimo_splash_background
 *
 * Three rule tiers, deliberately kept separate:
 *   SKIP_IDS    unique names, safe to click anywhere
 *   AD_IDS      unique ad containers, safe to hide anywhere
 *   SPLASH_IDS  generic names (iv_image, player_view...) that are ordinary widgets
 *               on other screens, so they only apply inside the splash Activity
 */
public final class Names {

    private static final Set<String> SKIP_IDS = new HashSet<>(Arrays.asList(
            "tv_skip", "cl_jump", "tv_jump", "skip_container", "mimo_splash_skip",
            "tv_time", "mimo_splash_tv_count_down"
    ));

    private static final Set<String> AD_IDS = new HashSet<>(Arrays.asList(
            "ad_container", "tv_ads_tag", "mimo_splash_background", "mimo_splash_title",
            "mimo_splash_summary", "mimo_splash_custom_area", "mimo_splash_custom_background",
            "mimo_splash_tv_admark", "mimo_splash_next",
            // Home screen floating promo bubble ("绝对值 有好价"), confirmed on device
            // at [864,1770][1044,1950] with its close button iv_close right above it.
            "iv_float"
    ));

    /** Only meaningful while the splash Activity is on screen. */
    private static final Set<String> SPLASH_IDS = new HashSet<>(Arrays.asList(
            "iv_image", "player_view", "pv_big", "lav_click", "bg_black_gradient",
            "tv_float", "tv_dual1", "tv_dual2", "tv_in_lav", "tv_single_big",
            "tv_slide_content", "tv_slide_guide", "cl_slide", "iv_slide_anim",
            "iv_slide_hand", "iv_slide_shadow", "vs_shake", "vs_roll", "iv_logo_small"
    ));

    private static final ConcurrentHashMap<Integer, String> ID_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> CLS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ACT_CACHE = new ConcurrentHashMap<>();

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

    /**
     * Ids whose visible footprint is split across sibling Views, so removing them
     * needs container-aware handling rather than a plain GONE.
     */
    public static boolean isBubbleId(String name) {
        return name != null && "iv_float".equalsIgnoreCase(name);
    }

    public static boolean isSplashOnlyId(String name) {
        return name != null && !name.isEmpty()
                && SPLASH_IDS.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Ad SDK packages actually present in this build. */
    public static boolean isAdClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = CLS_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        boolean r = c.contains("com.qq.e")
                || c.contains("com.byted.pangle")
                || c.contains("com.jd.junion")
                || c.contains("com.miui.zeus.mimo")
                || c.contains("com.baidu.mobads")
                || c.contains("com.smzdm.module.advertise.")
                || c.contains("splashad")
                || c.contains("nativead");
        CLS_CACHE.put(cls, r);
        return r;
    }

    /** Walk up the context chain to the hosting Activity class name. */
    public static String activityName(View v) {
        try {
            Context c = v.getContext();
            int guard = 0;
            while (c != null && guard++ < 12) {
                if (c instanceof Activity) {
                    return c.getClass().getName();
                }
                if (c instanceof ContextWrapper) {
                    c = ((ContextWrapper) c).getBaseContext();
                } else {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isSplashActivity(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = ACT_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        boolean r = c.endsWith("welcomeactivity")
                || c.endsWith("welcomactivity")
                || c.contains("splashactivity")
                || c.endsWith(".welcome");
        ACT_CACHE.put(cls, r);
        return r;
    }
}
