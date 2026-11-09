"""Exercise module recovery in a temporary filesystem, without adb or root."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ASSET = Path(__file__).resolve().parents[2] / "app/src/main/assets/revive_module_daemons.sh"


class ModuleRecoveryTest(unittest.TestCase):
    def run_recovery(self, *, flag=None, watchdog=False, alive=False, lsposed=False, lsposed_flag=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            module = root / "data/adb/modules/ksuGrantToast"
            module.mkdir(parents=True)
            (module / "boot-completed.sh").touch()
            if flag:
                (module / flag).touch()
            if watchdog:
                service = root / "data/adb/service.d"
                service.mkdir()
                (service / "99-ksuGrantToast-watchdog.sh").touch()
            if lsposed:
                framework = root / "data/adb/modules/zygisk_lsposed"
                framework.mkdir()
                (framework / "service.sh").write_text("official service script\n")
                if lsposed_flag:
                    (framework / lsposed_flag).touch()
                ksu = root / "data/adb/ksu/bin"
                ksu.mkdir(parents=True)
                resetprop = ksu / "resetprop"
                resetprop.write_text('#!/bin/sh\necho "resetprop $*" >> "$CALLS"\n')
                resetprop.chmod(0o755)
            commands = root / "bin"
            commands.mkdir()
            stubs = {
                "pidof": 'test "$ALIVE" = 1 && echo 123',
                "pgrep": "exit 1",
                "sleep": "exit 0",
                "setsid": 'echo "$*" >> "$CALLS"',
            }
            for name, body in stubs.items():
                command = commands / name
                command.write_text("#!/bin/sh\n" + body + "\n")
                command.chmod(0o755)
            script = root / "revive.sh"
            script.write_text(ASSET.read_text().replace("/data/adb", str(root / "data/adb"))
                              + "\nwait\n")
            calls = root / "calls"
            env = dict(os.environ, PATH=f"{commands}:/usr/bin:/bin",
                       ALIVE=str(int(alive)), CALLS=str(calls))
            result = subprocess.run(["sh", str(script)], env=env, capture_output=True,
                                    text=True, timeout=5, check=True)
            if lsposed:
                self.assertEqual("official service script\n", (framework / "service.sh").read_text())
            return result.stdout, calls.read_text() if calls.exists() else ""

    def test_lsposed_only_clears_property_when_enabled(self):
        for flag in (None, "disable", "remove"):
            with self.subTest(flag=flag):
                _, calls = self.run_recovery(alive=True, lsposed=True, lsposed_flag=flag)
                expected = "resetprop --delete dalvik.vm.dex2oat-flags\n" if flag is None else ""
                self.assertEqual(expected, calls)

    def test_disabled_or_removed_module_is_not_started(self):
        for flag in ("disable", "remove"):
            with self.subTest(flag=flag):
                output, calls = self.run_recovery(flag=flag)
                self.assertIn("module disabled or removed, skip", output)
                self.assertEqual("", calls)

    def test_watchdog_owns_recovery(self):
        output, calls = self.run_recovery(watchdog=True)
        self.assertIn("recovery delegated to watchdog", output)
        self.assertIn("99-ksuGrantToast-watchdog.sh", calls)
        self.assertNotIn("boot-completed.sh", calls)

    def test_alive_daemon_is_left_running(self):
        output, calls = self.run_recovery(alive=True)
        self.assertIn("SuToaster alive", output)
        self.assertEqual("", calls)

    def test_missing_daemon_without_watchdog_uses_module_startup(self):
        _, calls = self.run_recovery()
        self.assertEqual("sh ./boot-completed.sh\n", calls)


if __name__ == "__main__":
    unittest.main()
