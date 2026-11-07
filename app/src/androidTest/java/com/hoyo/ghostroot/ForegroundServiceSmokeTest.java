package com.hoyo.ghostroot;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/** Checks only the idle foreground lifecycle; never requests root or runs a task. */
public class ForegroundServiceSmokeTest extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int code = -1;
        Context context = getTargetContext();
        try {
            send(GhostRootService.ACTION_START);
            awaitForeground(true);
            send(GhostRootService.ACTION_START);
            send("UNKNOWN");
            // Wait for both starts to be delivered before checking persistence.
            SystemClock.sleep(500);
            awaitForeground(true);
            if (GhostRootService.running) {
                throw new AssertionError("Starting the foreground service must not start a task");
            }
            send(GhostRootService.ACTION_STOP);
            awaitForeground(false);
            send(GhostRootService.ACTION_START);
            awaitForeground(true);
            result.putString("stream", "PASS: idle foreground, repeated start, stop and restart; no root task\n");
        } catch (Throwable e) {
            code = 1;
            result.putString("stream", "FAIL: " + e);
        } finally {
            context.stopService(new Intent(context, GhostRootService.class));
        }
        finish(code, result);
    }

    private void send(String action) {
        Context context = getTargetContext();
        context.startForegroundService(new Intent(context, GhostRootService.class).setAction(action));
    }

    @SuppressWarnings("deprecation")
    private void awaitForeground(boolean expected) {
        ActivityManager manager = getTargetContext().getSystemService(ActivityManager.class);
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean found = false;
            for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(100)) {
                if (GhostRootService.class.getName().equals(info.service.getClassName())) {
                    found = true;
                    if (expected && info.foreground) return;
                }
            }
            if (!expected && !found) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Expected foreground service present=" + expected);
    }
}
