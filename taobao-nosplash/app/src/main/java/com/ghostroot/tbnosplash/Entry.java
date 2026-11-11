package com.ghostroot.tbnosplash;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * System-framework-scope entry (scope.list = "android").
 *
 * LSPosed injects this into system_server. We deliberately do NOT filter by
 * process name (it is "system_server", not "android") and do NOT detach().
 *
 * Strategy: install the hook immediately in onModuleLoaded(). If ActivityRecord
 * is not resolvable yet (system_server is still booting), a retry thread keeps
 * trying every 2 seconds until the hook lands or 60 seconds elapse. We do NOT
 * rely on onPackageLoaded("android") because that callback may never fire for
 * the framework package in system_server.
 */
public final class Entry extends XposedModule {

    private static volatile boolean hookArmed = false;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        L.bind(this);
        L.i("module loaded api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " pid=" + android.os.Process.myPid());

        Hooks.install(null);
        hookArmed = Hooks.isInstalled();
        if (!hookArmed) {
            startRetryThread();
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        L.bind(this);
        String pkg;
        try {
            pkg = param.getPackageName();
        } catch (Throwable t) {
            L.e("getPackageName failed", t);
            return;
        }
        if (!"android".equals(pkg)) {
            return;
        }
        ClassLoader cl = null;
        try {
            cl = param.getDefaultClassLoader();
        } catch (Throwable t) {
            L.e("getDefaultClassLoader failed", t);
        }
        if (!hookArmed) {
            Hooks.install(cl);
            hookArmed = Hooks.isInstalled();
            if (hookArmed) {
                L.i("hook armed via onPackageLoaded android");
            }
        }
    }

    private void startRetryThread() {
        new Thread(() -> {
            int attempts = 0;
            while (!hookArmed && attempts++ < 30) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                }
                Hooks.install(null);
                hookArmed = Hooks.isInstalled();
                if (hookArmed) {
                    L.i("hook armed via retry attempt=" + attempts);
                    return;
                }
            }
            if (!hookArmed) {
                L.i("hook never armed after " + attempts + " attempts; module stays inert");
            }
        }, "TBNoSplash-retry").start();
    }
}
