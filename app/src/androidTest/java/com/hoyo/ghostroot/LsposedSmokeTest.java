package com.hoyo.ghostroot;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/** Device check: Shizuku must be running and GhostRoot already authorized. */
public class LsposedSmokeTest extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getTargetContext(), RootUserService.class))
                .tag("lsposed-smoke-test").processNameSuffix("lsposed_test")
                .daemon(false).version(43);
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IRootRunner> runner = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                runner.set(IRootRunner.Stub.asInterface(binder));
                connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { }
        };
        int code = -1;
        try {
            // App-context failure must be reported, never mistaken for success.
            String local = new RootUserService(getTargetContext()).openLsposedManager();
            if (!local.startsWith("✗ Shizuku 通道无法取得 root")) {
                throw new AssertionError("Expected unavailable app su on this device: " + local);
            }
            runOnMainSync(() -> Shizuku.bindUserService(args, connection));
            if (!connected.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Shizuku UserService did not connect");
            }
            String remote = runner.get().openLsposedManager();
            if (!remote.contains("✅ 已发送") || !remote.contains("Status: ok")) {
                throw new AssertionError("Lsposed launch failed: " + remote);
            }
            result.putString("stream", "PASS: app su unavailable; Shizuku LSPosed launch succeeded\n" + remote);
        } catch (Throwable e) {
            code = 1;
            result.putString("stream", "FAIL: " + e);
        } finally {
            runOnMainSync(() -> Shizuku.unbindUserService(args, connection, true));
        }
        finish(code, result);
    }
}
