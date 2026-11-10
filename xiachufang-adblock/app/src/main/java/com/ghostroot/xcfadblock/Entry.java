package com.ghostroot.xcfadblock;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed API 102 entry point.
 * scope.list locks injection to com.xiachufang; LSPosed will not load us elsewhere.
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.xiachufang";

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
        if (!TARGET.equals(pkg)) {
            return;
        }
        try {
            String proc = android.app.Application.getProcessName();
            if (proc != null && !proc.isEmpty() && !proc.equals(TARGET)) {
                L.i("skip child process: " + proc);
                return;
            }
        } catch (Throwable ignored) {
        }
        L.i("target loaded: " + pkg + " classloader=" + L.classLoaderOf(param));
        AdHooks.install(L.classLoaderOf(param));
    }
}
