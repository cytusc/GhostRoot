package com.ghostroot.tbadblock;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point. scope.list locks injection to com.taobao.taobao.
 *
 * The official API docs warn that the framework injects a module into ALL
 * regular processes of the scoped packages, and that every loaded package in
 * that process triggers callbacks: "modules may receive callbacks beyond the
 * originally scoped packages, so they should always filter by process name
 * and package name". We filter by package, isFirstPackage() and process name,
 * then detach() once initialization is done (API 102+).
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.taobao.taobao";

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
        AdHooks.install(L.classLoaderOf(param));
        // Hooks are installed once; no further lifecycle callbacks are needed.
        // detach() only stops callbacks, all XposedInterface APIs stay usable.
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
