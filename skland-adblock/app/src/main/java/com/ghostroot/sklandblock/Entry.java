package com.ghostroot.sklandblock;

import android.app.Application;
import android.os.Build;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * libxposed entry point. scope.list locks injection to com.hypergryph.skland.
 *
 * The host is packed with SecNeo: the APK's primary dex is only a stub, real
 * classes are decrypted at runtime. All hooks therefore target FRAMEWORK
 * methods (Activity.onCreate / Dialog.show) and match host class names at
 * runtime — no Class.forName on the host loader is required.
 */
public final class Entry extends XposedModule {

    private static final String TARGET = "com.hypergryph.skland";

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
        // Field switches let us isolate which layer trips SecNeo's native
        // anti-injection without rebuilding the module:
        //   /data/local/tmp/sk_nohook -> inject only, install no hooks
        //   /data/local/tmp/sk_nodlg  -> Activity.onCreate only
        //   (neither present)        -> all hooks
        int mode = Hooks.MODE_FULL;
        try {
            if (new java.io.File("/data/local/tmp/sk_nohook").exists()) {
                mode = Hooks.MODE_NONE;
            } else if (new java.io.File("/data/local/tmp/sk_nodlg").exists()) {
                mode = Hooks.MODE_NO_DIALOG;
            }
        } catch (Throwable ignored) {
        }
        L.i("probe mode=" + mode);
        Hooks.install(L.classLoaderOf(param), mode);
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
