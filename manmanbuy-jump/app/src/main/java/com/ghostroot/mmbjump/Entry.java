package com.ghostroot.mmbjump;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point. scope.list locks injection to com.manmanbuy.bijia.
 *
 * Filters by package, isFirstPackage() and process name per the official
 * API guidance, then detaches (API 102+) once hooks are installed.
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.manmanbuy.bijia";

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
        // v1.0.12 probe: are ANY hooks dispatching in this process? View-level
        // hooks fire constantly in every app; if these stay silent while the
        // hooks are installed, a host-side anti-hook SDK is restoring entry
        // points process-wide and no hook point will ever work here.
        try {
            L.hook("probe.ViewGroup.addView",
                    android.view.ViewGroup.class.getDeclaredMethod("addView",
                            android.view.View.class, android.view.ViewGroup.LayoutParams.class),
                    new XposedInterface.Hooker() {
                        private volatile boolean logged = false;

                        @Override
                        public Object intercept(io.github.libxposed.api.XposedInterface.Chain chain)
                                throws Throwable {
                            if (!logged) {
                                logged = true;
                                L.i("PROBE FIRED: ViewGroup.addView");
                            }
                            return chain.proceed();
                        }
                    });
        } catch (Throwable t) {
            L.e("probe addView failed", t);
        }
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
