package com.ghostroot.tbadblock;

import android.view.View;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * com.taobao.taobao 10.66.0 boot-image ("启动图") splash rules.
 *
 * Evidence: a live `dumpsys activity top` snapshot taken while the "11.11 FOLO"
 * creative was on screen with the skip label "跳过广告 1" in the top-right:
 *
 *   FrameLayout          app:id/fl_root_view
 *     View               app:id/iv_bg_view
 *     FilletLinearLayout app:id/fl_animation_view
 *       RelativeLayout   app:id/ll_container
 *         TUrlImageView  app:id/iv_main              <- the creative
 *       ImageView        app:id/iv_bottom_tb_logo
 *     RelativeLayout     app:id/rv_title
 *       LinearLayout     app:id/ll_close            <- GONE until countdown runs
 *         TextView       app:id/tv_close            "跳过广告"
 *         TextView       app:id/tv_countdown        "1"
 *
 * The ad is NOT an Activity. logcat shows Welcome -> TBMainActivity in ~1s
 * (Displayed +2s25ms) and the boot-image window is added on top afterwards;
 * Welcome is already DESTROYED while the ad is still visible. So there is no
 * splash Activity to finish -- pressing the app's own skip control is the only
 * safe way out, because the host then dismisses the window itself.
 *
 * On the dex side com.taobao.bootimage.* holds BootImageDataMgr (plus
 * FatigueInfo, which is why a second cold start often shows no ad at all),
 * BootImageWorkFlow and arch/flow/view/SkipStyle. Its methods are obfuscated
 * down to a/b/c, so hooking them would break on the next release. Resource
 * ids are stable and were read straight off the live View tree.
 */
public final class Names {

    /** The app's own skip control. Clicking it lets the host close the ad. */
    private static final Set<String> SKIP_IDS = new HashSet<>(Arrays.asList(
            "ll_close", "tv_close", "tv_countdown"
    ));

    /**
     * The whole ad window. fl_root_view is a full-screen FrameLayout added on
     * top of TBMainActivity; collapsing that single node hides every child,
     * so there is no need to enumerate the creative / logo / title ids.
     */
    private static final Set<String> AD_IDS = new HashSet<>(Arrays.asList(
            "fl_root_view", "fl_animation_view"
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

    /**
     * The full-screen FrameLayout that carries the whole bootimage window.
     * Hiding this one node removes the ad creative AND the brand animation,
     * even on starts where the server sends no ad and no skip control is
     * inflated. It is only acted on after isBootImageWindow() confirms a
     * bootimage child, so an unrelated fl_root_view elsewhere is untouched.
     */
    public static boolean isWindowRoot(String name) {
        return "fl_root_view".equals(name);
    }

    /** Only the boot-image package is treated as ad SDK code. */
    public static boolean isAdClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        Boolean cached = CLS_CACHE.get(cls);
        if (cached != null) {
            return cached;
        }
        String c = cls.toLowerCase(Locale.ROOT);
        boolean r = c.contains("com.taobao.bootimage.");
        CLS_CACHE.put(cls, r);
        return r;
    }
}
