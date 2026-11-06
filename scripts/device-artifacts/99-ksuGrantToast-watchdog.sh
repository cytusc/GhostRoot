#!/system/bin/sh
# Watchdog for KernelSU Grant Toast (ksuGrantToast / SuToaster).
#
# Install to /data/adb/service.d/99-ksuGrantToast-watchdog.sh (root:root 0755).
# KernelSU executes service.d scripts at the late-start service stage; this one
# detaches a single loop and returns immediately.
#
# Why it exists:
#   The module only has boot-completed.sh, which does
#     exec app_process -Djava.class.path=./daemon.dex ... --nice-name=SuToaster
#   If the native side (Shimizu / libshimizu.so) cannot obtain the KSU su-log fd
#   at that moment it prints "Failed to get Su log fd" -> "Error on working,Exited"
#   -> dex "Native init failed!" -> "Error on showing toast!", writes
#   override.description = [❌Error on showing toast!] and the process dies.
#   The module has no restart / self-heal path. A manual re-run of
#   boot-completed.sh works because by then ksud's sulog is ready.
#
# This script ONLY manages this one module's daemon. It never touches other
# modules, never changes module files, and only calls the module's own official
# boot-completed.sh (which re-applies the manage.sulog / feature sulog dance).
#
# Disable: delete the service.d file and kill the "--daemon" process.

SELF=/data/adb/service.d/99-ksuGrantToast-watchdog.sh
MOD=/data/adb/modules/ksuGrantToast
KSUD=/data/adb/ksud
LOG=/data/local/tmp/ksu_grant_toast_watchdog.log
PIDFILE=/data/local/tmp/ksu_grant_toast_watchdog.pid
ERRSTAMP=/data/local/tmp/ksu_grant_toast_watchdog.errstamp

log() {
  printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null
  sz=$(wc -c < "$LOG" 2>/dev/null || echo 0)
  if [ "$sz" -gt 65536 ] 2>/dev/null; then
    tail -200 "$LOG" > "$LOG.tmp" 2>/dev/null && mv -f "$LOG.tmp" "$LOG"
  fi
}

desc() {
  KSU_MODULE=ksuGrantToast "$KSUD" module config get override.description 2>/dev/null
}

if [ "$1" = "--daemon" ]; then
  # Single-instance guard (service.d may run more than once on some setups).
  if [ -f "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    if [ -n "$old" ] && [ "$old" != "$$" ] && [ -r "/proc/$old/cmdline" ] \
       && grep -aq 'ksuGrantToast-watchdog' "/proc/$old/cmdline" 2>/dev/null; then
      exit 0
    fi
  fi
  echo $$ > "$PIDFILE" 2>/dev/null
  log "watchdog started pid=$$"

  # Let ksud's own boot-completed.sh take the first shot; avoids a start/kill race
  # with the module's built-in "kill old SuToaster" logic.
  sleep 30

  while :; do
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] \
       && [ -f "$MOD/module.prop" ] \
       && [ ! -f "$MOD/disable" ] && [ ! -f "$MOD/remove" ]; then

      # Readiness gate: never start the native daemon before ksud's sulog is up.
      sulog_ready=0
      "$KSUD" feature get sulog 2>/dev/null | grep -q 'Status: enabled' && sulog_ready=1

      if [ "$sulog_ready" = "1" ]; then
        if ! pidof SuToaster >/dev/null 2>&1; then
          log "SuToaster missing -> run boot-completed.sh"
          ( cd "$MOD" && setsid sh ./boot-completed.sh < /dev/null >> "$LOG" 2>&1 ) &
          sleep 20
          log "after restart pid=$(pidof SuToaster 2>/dev/null) desc=$(desc)"
        else
          # Daemon alive but module may still show the red-X state (native init
          # failed yet process lingered). Force one clean restart, rate-limited.
          d=$(desc)
          case "$d" in
            *'❌'*|*'Error on showing toast'*|*'Native init failed'*)
              now=$(date +%s)
              last=$(cat "$ERRSTAMP" 2>/dev/null || echo 0)
              if [ $((now - last)) -ge 180 ] 2>/dev/null; then
                echo "$now" > "$ERRSTAMP" 2>/dev/null
                log "error state while alive: $d -> kill for clean restart"
                kill -9 "$(pidof SuToaster)" 2>/dev/null
                sleep 5
              fi
              ;;
          esac
        fi
      else
        log "sulog not enabled yet -> wait"
      fi
    fi
    sleep 15
  done
fi

# Launcher path: detach exactly one loop, return immediately.
setsid sh "$SELF" --daemon < /dev/null > /dev/null 2>&1 &
exit 0
