package com.ghostroot.wmaijump;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point. scope.list locks injection to com.sankuai.meituan.takeoutnew.
 *
 * Filters by package, isFirstPackage() and process name per the official
 * API guidance, then detaches (API 102+) once hooks are installed.
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.sankuai.meituan.takeoutnew";

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
        if (proc != null && !proc.isEmpty() && !proc.equals(TARGET)) {
            L.i("skip child process: " + proc);
            detachIfSupported();
            return;
        }

        L.i("target loaded: " + pkg + " classloader=" + L.classLoaderOf(param));
        SplashJump.install(L.classLoaderOf(param));
        detachIfSupported();
    }

    /** detach() is @SinceApi(102), so guard it to keep minApiVersion=100 honest. */
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
