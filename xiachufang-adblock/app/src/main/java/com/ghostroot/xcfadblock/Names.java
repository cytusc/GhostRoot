package com.ghostroot.xcfadblock;

import android.content.Context;
import android.view.View;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative ad-view matching.
 *
 * Matching works on underscore-delimited SEGMENTS, never on raw substrings.
 * A bare contains("ad_view") also matches "head_view"; contains("ad_container")
 * matches "head_container"; contains("ad_layout") matches "head_layout". Those
 * false positives used to zero-size normal page headers, which is exactly the
 * "some content never loads" symptom. Segment matching removes that class of bug
 * while still catching ad_container / splash_ad / adview / pangle.
 */
public final class Names {

    public static final String SKIP = "skip_container";

    /** A segment equal to one of these identifies an ad container on its own. */
    private static final String[] AD_SEGMENTS = {
            "ad", "ads", "advert", "adverts", "advertise", "advertisement",
            "adview", "adcontainer", "adbanner", "adlayout", "adframe", "adslot",
            "adroot", "adfl", "adsplash", "splashad", "gdtad", "ttad", "bdad",
            "pangle", "panglead"
    };

    /** Container-ish segments; only meaningful together with "splash". */
    private static final String[] BOX_SEGMENTS = {
            "container", "root", "layout", "frame", "fl", "view", "group", "box", "layer"
    };

    /**
     * Resource entry names never change during a process, so cache them. This turns
     * a native AssetManager lookup (tens of microseconds) into a map hit on every
     * View attach, which is the main reason long feeds stuttered.
     */
    private static final ConcurrentHashMap<Integer, String> ID_CACHE = new ConcurrentHashMap<>();

    /** Class names are few; cache the match result to keep the hot path allocation free. */
    private static final ConcurrentHashMap<String, Boolean> CLS_CACHE = new ConcurrentHashMap<>();

    private Names() {
    }

    public static String entryName(Context ctx, int id) {
        if (ctx == null || id == View.NO_ID || id <= 0) {
            return "";
        }
        String cached = ID_CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        String name;
        try {
            name = ctx.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            name = "";
        }
        if (name == null) {
            name = "";
        }
        ID_CACHE.put(id, name);
        return name;
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
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (SKIP.equals(n)) {
            return true;
        }
        if (n.indexOf("skip") < 0) {
            return false;
        }
        boolean skip = false;
        boolean ad = false;
        for (String s : n.split("_")) {
            if (s.startsWith("skip")) {
                skip = true;
            }
            if (equalsAny(s, AD_SEGMENTS)) {
                ad = true;
            }
        }
        return skip && ad;
    }

    public static boolean isAdView(String name, String cls) {
        if (cls != null && !cls.isEmpty() && isAdClass(cls)) {
            return true;
        }
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        // Cheap reject first: almost every View fails this, and it costs one scan.
        if (n.indexOf("ad") < 0 && n.indexOf("splash") < 0 && n.indexOf("pangle") < 0
                && n.indexOf("gdt") < 0) {
            return false;
        }
        boolean ad = false;
        boolean splash = false;
        boolean box = false;
        for (String s : n.split("_")) {
            if (!ad && equalsAny(s, AD_SEGMENTS)) {
                ad = true;
            }
            if (!splash && s.contains("splash")) {
                splash = true;
            }
            if (!box && equalsAny(s, BOX_SEGMENTS)) {
                box = true;
            }
        }
        // "xx_ad_yy" is an ad slot; "splash_container" is the splash ad frame,
        // but "splash_logo" is the brand logo and must stay visible.
        return ad || (splash && box);
    }

    /**
     * Ad SDK packages and real ad-widget class segments only. A bare
     * contains("adview") also matches "HomeRecommendHeadView" (he+adview),
     * which is a normal home-page header, not an ad.
     */
    public static boolean isAdClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = CLS_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        boolean r = c.contains("splashad")
                || c.contains(".ad.")
                || c.contains("com.xiachufang.ad.")
                || c.contains("com.byted.pangle")
                || c.contains("com.qq.e.")
                || c.contains("com.kwad")
                || c.contains("admanager")
                || c.contains("adloader")
                || c.contains("adholder")
                || c.contains(".adview")
                || (c.contains("gdt") && c.contains("ad"));
        CLS_CACHE.put(cls, r);
        return r;
    }

    private static boolean equalsAny(String s, String[] pool) {
        for (String p : pool) {
            if (p.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
