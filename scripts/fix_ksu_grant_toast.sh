#!/bin/bash
# Host-side one-shot: fix "KernelSU Grant Toast" showing [❌Error on showing toast!].
#
#   scripts/fix_ksu_grant_toast.sh            # install + verify
#   scripts/fix_ksu_grant_toast.sh --test-kill # also kill SuToaster and verify recovery
#
# Deploys a service.d watchdog that boots the module's own boot-completed.sh only
# after sys.boot_completed=1 and ksud sulog is enabled, and self-heals the daemon.
# Requires USB adb + root (su). Set ADB=/path/to/adb to override the binary.

set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ART="$ROOT/scripts/device-artifacts"
WATCHDOG="$ART/99-ksuGrantToast-watchdog.sh"
INSTALLER="$ART/install_ksu_grant_toast_watchdog.sh"

# macOS: host adb v37 crashes its server; the bundled platform-tools adb + libusb
# backend is the known-good combination (same as gr_autoloop.sh).
export ADB_LIBUSB=1
if [ -z "${ADB:-}" ]; then
  for c in "/Users/hoyo/WorkBuddy AI/2026-10-28-12-02-20/tools/platform-tools/adb" \
           "$(command -v adb 2>/dev/null)"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
: "${ADB:?no adb binary found; set ADB=/path/to/adb}"

echo "adb = $ADB"

echo "=== wait for device ==="
"$ADB" kill-server >/dev/null 2>&1
"$ADB" start-server >/dev/null 2>&1
i=0
while [ "$i" -lt 12 ]; do
  state=$("$ADB" get-state 2>/dev/null)
  [ "$state" = "device" ] && break
  echo "  device not ready ($i/12) ..."
  sleep 3
  i=$((i+1))
done
if [ "$("$ADB" get-state 2>/dev/null)" != "device" ]; then
  echo "FAIL: no adb device. Check the USB cable, unlock the phone and allow USB debugging."
  "$ADB" devices -l 2>&1
  exit 2
fi
"$ADB" devices -l

echo "=== check root ==="
if ! "$ADB" shell 'su -c "id -u"' 2>/dev/null | grep -q '^0$'; then
  echo "FAIL: root (su) unavailable. Grant GhostRoot/shell superuser in KernelSU first."
  exit 3
fi

echo "=== push device-side scripts ==="
"$ADB" push "$WATCHDOG"  /data/local/tmp/99-ksuGrantToast-watchdog.sh          || exit 4
"$ADB" push "$INSTALLER" /data/local/tmp/install_ksu_grant_toast_watchdog.sh   || exit 4

ARG=""
[ "${1:-}" = "--test-kill" ] && ARG="test-kill"
echo "=== install + verify (arg=$ARG) ==="
"$ADB" shell "su -c 'sh /data/local/tmp/install_ksu_grant_toast_watchdog.sh $ARG'" 2>&1
