package com.ghostroot.wkblock;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point for com.snda.wifilocating (WiFi 万能钥匙).
 *
 * The host is NOT packed (11 dex files, plain class names), so framework
 * hooks are sufficient. Both ad surfaces are handled in Hooks:
 *  - splash ad embedded in LauncherActivity (ad_layout / skipped)
 *  - interstitial popup ad in com.zm.adxsdk.core.activity.* activities
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.snda.wifilocating";

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        L.bind(this);
        L.i("module loaded api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " pid=" + android.os.Process.myPid());
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        L.bind(this);
        String pkg = L.packageNameOf(param);
        boolean first = true;
        try {
            first = param.isFirstPackage();
        } catch (Throwable t) {
            L.e("isFirstPackage failed", t);
        }
        if (!TARGET.equals(pkg) || !first) {
            return;
        }

        String proc = null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                proc = Application.getProcessName();
            } catch (Throwable ignored) {
            }
        }
        // The host runs .daemon / :assist / :assist1 / :persistent children.
        // Only the main process creates activities, so hook just that one;
        // injecting into children adds noise and needless risk.
        if (proc != null && !proc.isEmpty() && !proc.equals(TARGET)) {
            L.i("skip child process: " + proc);
            detachIfSupported();
            return;
        }

        L.i("target loaded: " + pkg + " proc=" + proc
                + " classloader=" + L.classLoaderOf(param));
        Hooks.install(L.classLoaderOf(param));
        detachIfSupported();
    }

    private void detachIfSupported() {
        if (getApiVersion() < 102) {
            return;
        }
        try {
            detach();
        } catch (Throwable t) {
            L.e("detach failed", t);
        }
    }
}
