#!/system/bin/sh
# Device-side installer + verifier for the ksuGrantToast watchdog.
# Run as root: su -c "sh /data/local/tmp/install_ksu_grant_toast_watchdog.sh [test-kill]"
# Source watchdog must already be pushed to /data/local/tmp/99-ksuGrantToast-watchdog.sh

SRC=/data/local/tmp/99-ksuGrantToast-watchdog.sh
DST=/data/adb/service.d/99-ksuGrantToast-watchdog.sh
LOG=/data/local/tmp/ksu_grant_toast_watchdog.log
PIDFILE=/data/local/tmp/ksu_grant_toast_watchdog.pid
KSUD=/data/adb/ksud
export KSU_MODULE=ksuGrantToast

show_state() {
  echo "--- SuToaster pid: $(pidof SuToaster 2>&1)"
  echo "--- description : $("$KSUD" module config get override.description 2>&1)"
}

echo "=== 1. install ==="
[ -f "$SRC" ] || { echo "FAIL: $SRC not found (push it first)"; exit 1; }
mkdir -p /data/adb/service.d
cp -f "$SRC" "$DST" || { echo "FAIL: copy"; exit 1; }
chown 0:0 "$DST"
chmod 0755 "$DST"
ls -la "$DST"

echo "=== 2. stop any previous watchdog loop ==="
if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$old" ] && kill "$old" 2>/dev/null && echo "killed old watchdog pid=$old"
  rm -f "$PIDFILE"
fi
rm -f /data/local/tmp/ksu_grant_toast_watchdog.errstamp

echo "=== 3. start watchdog ==="
sh "$DST"
sleep 3
echo "--- watchdog processes (/proc scan) ---"
for c in /proc/[0-9]*/cmdline; do
  grep -aq 'ksuGrantToast-watchdog' "$c" 2>/dev/null && echo "  ${c%/cmdline}"
done
echo "--- pidfile ---"
cat "$PIDFILE" 2>&1

echo "=== 4. current module state ==="
show_state

if [ "$1" = "test-kill" ]; then
  echo "=== 5. kill daemon and verify self-healing ==="
  p=$(pidof SuToaster 2>/dev/null)
  if [ -n "$p" ]; then
    kill -9 "$p" 2>/dev/null
    echo "killed SuToaster pid=$p"
  else
    echo "SuToaster was not running; watchdog should start it"
  fi
  i=0
  while [ "$i" -lt 16 ]; do
    sleep 5
    p=$(pidof SuToaster 2>/dev/null)
    [ -n "$p" ] && { echo "recovered at +$((i*5+5))s pid=$p"; break; }
    i=$((i+1))
  done
fi

echo "=== 6. final state ==="
show_state
echo "--- watchdog log (tail) ---"
tail -20 "$LOG" 2>&1
echo "=== done ==="
