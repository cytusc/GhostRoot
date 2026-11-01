package com.hoyo.ghostroot;

import java.io.File;

/**
 * Diagnostic entry point: run the proven CLI from a JVM parent, exactly like the
 * Shizuku UserService does, but without Shizuku. Used to isolate whether the
 * hang comes from the JVM parent (inherited fds/session) or from Shizuku's
 * cgroup.
 *
 * adb shell "CLASSPATH=<apk> app_process /system/bin com.hoyo.ghostroot.CliMain"
 */
public class CliMain {
    public static void main(String[] args) {
        try {
            String cli = args.length > 0 ? args[0] : "/data/local/tmp/ghostlock";
            String profile = args.length > 1 ? args[1] : "/data/local/tmp/profile.bin";
            String tag = args.length > 2 ? args[2] : "climain";

            File workDir = new File("/data/local/tmp");
            File log = new File(workDir, tag + "-run.log");
            ProcessBuilder pb = new ProcessBuilder(
                    cli, "--load-prebuilt-profile", profile, "--force-attack");
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
            if (args.length > 3 && "noenv".equals(args[3])) {
                pb.environment().clear();
            }
            System.out.println("climain: uid=" + android.os.Process.myUid()
                    + " cgroup=" + readText("/proc/self/cgroup").replace("\n", " | ")
                    + " fds=" + countFds());
            java.lang.Process p = pb.start();
            int code = p.waitFor();
            System.out.println("climain: exit=" + code + " enforce=" + readText("/sys/fs/selinux/enforce").trim());
            System.exit(code);
        } catch (Throwable t) {
            System.out.println("climain: error " + t);
            System.exit(99);
        }
    }

    private static int countFds() {
        String[] fds = new File("/proc/self/fd").list();
        return fds == null ? -1 : fds.length;
    }

    private static String readText(String path) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(path)))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }
}
