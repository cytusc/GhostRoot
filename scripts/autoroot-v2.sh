#!/bin/bash
# autoroot-v2 — RootFamiliar 思路借鉴版（原 autoroot.sh 保留不动）
#
# 借鉴点与对应实现：
#   1. checkKernel（RootFamiliar flow/RootFlow）：开火前 uname -r 硬校验，
#      profile 只对一个内核有效，OTA 后乱打就是给 vr.ko 送采样 → fail-fast
#   2. EnvProbe（core/EnvProbe）：环境一探到底（kallsyms 是否被 kptr_restrict
#      归零、/data/adb 可读性、ksud uapi）→ 每轮失败现场有数据可归因
#   3. ManagerProbe.uapi（core/ManagerProbe）：root 到手后读 /data/adb/ksud -V
#      的 (uapi: N)，与内核 uapi 不符自动换 ksud-gk；直写被 vivo LSM 拒时
#      退到 b0 atcid_vendor_socket 通道（2026-11-02 实测可用）
#   4. 同 boot 多次尝试：官方路径失败 rc=1 仅 7.3s 退出（Write 1 failed），
#      不复位就再打——每轮都是独立抽奖，比"一轮一重启"快 3 倍
#
# 用法: bash autoroot-v2.sh [cycles] [cap_seconds] [attempts_per_boot]
ADB="/Users/hoyo/WorkBuddy AI/2026-10-28-12-02-20/tools/platform-tools/adb"
a() { "$ADB" "$@"; }
log() { echo "[$(date +%T)] $*"; }
MAXCYC=${1:-8}
CAP=${2:-90}
ATT=${3:-3}
# 命中窗口：2026-11-02 两次成功都在 uptime 29s / 36s 开火。
# 太早（<25s）系统还在 churn，太晚页污染。瞄准 32s 附近再开火。
FIRE_AT=${4:-32}
EXPECTED_KERNEL="5.15.197-g346b1c36bd5c-dirty"
EXPECTED_UAPI=5

wait_boot() {
  for n in $(seq 1 60); do
    if a get-state 2>/dev/null | grep -q device; then
      B=$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
      [ "$B" = "1" ] && return 0
    fi
    sleep 3
  done
  return 1
}

root_ok() { a shell 'su -c id 2>/dev/null' 2>/dev/null | grep -q "uid=0"; }

ensure_fire() {
  pid=$(a shell 'cat /data/local/tmp/.fire.pid 2>/dev/null' 2>/dev/null | tr -d '\r\n')
  if [ -n "$pid" ] && a shell "kill -0 $pid 2>/dev/null" >/dev/null 2>&1; then
    log "fire watcher alive pid=$pid"
  else
    a shell 'rm -f /data/local/tmp/.fired /data/local/tmp/.fire.pid; nohup sh /data/local/tmp/fire.sh >/dev/null 2>&1 & sleep 1; echo newpid=$(cat /data/local/tmp/.fire.pid 2>/dev/null)'
    log "fire watcher restarted"
  fi
}

# ---- 借鉴 1: checkKernel —— 开火前硬校验（每 boot 一次） ----
check_kernel() {
  # 注意：/proc/sys/kernel/osrelease 在本机被 vivo 策略禁读（Permission denied），
  # 只有 uname -r 可用 —— 别改成 cat。
  # 刚开机时 adbd 可能还没稳，uname 会返回空串 —— 重试，不能当"内核不符"。
  KR=""
  for t in 1 2 3 4 5 6 7 8 9 10; do
    KR=$(a shell 'uname -r 2>/dev/null' | tr -d '\r\n')
    [ -n "$KR" ] && break
    sleep 2
  done
  if [ -z "$KR" ]; then
    log "checkKernel: adb unstable (uname empty after retries) — skip this cycle"
    return 2
  fi
  if [ "$KR" != "$EXPECTED_KERNEL" ]; then
    log "[X] checkKernel FAIL: running [$KR] expected [$EXPECTED_KERNEL] — refusing to fire"
    return 1
  fi
  log "checkKernel ok: $KR"
  return 0
}

# ---- 借鉴 2: EnvProbe —— 环境快照（进每轮日志） ----
env_probe() {
  KS=$(a shell 'head -3 /proc/kallsyms 2>/dev/null' | grep -c "0000000000000000")
  KALL="full"; [ "${KS:-0}" -ge 2 ] && KALL="restricted"
  ADBR=$(a shell 'test -r /data/adb && echo r || echo -' 2>/dev/null | tr -d '\r\n')
  log "env: kallsyms=$KALL data_adb=$ADBR"
}

# ---- 借鉴 3: uapi guard —— root 后 ksud 版本守卫（直写 + b0 兜底） ----
uapi_guard() {
  V=$(a shell 'su -c "/data/adb/ksud -V 2>/dev/null"' 2>/dev/null | tr -d '\r\n')
  U=$(echo "$V" | grep -oE 'uapi: [0-9]+' | grep -oE '[0-9]+' | head -1)
  [ -z "$U" ] && U=-1
  if [ "$U" = "$EXPECTED_UAPI" ]; then
    log "uapi guard: ksud uapi$U ok"
    return 0
  fi
  log "uapi guard: /data/adb/ksud is uapi$U, kernel needs uapi$EXPECTED_UAPI — swapping"
  a shell 'su -c "cp -f /data/adb/ksud /data/adb/ksud-uapi2.bak 2>/dev/null; cp -f /data/local/tmp/ksud-gk /data/adb/ksud && chmod 755 /data/adb/ksud"' >/dev/null 2>&1
  V2=$(a shell 'su -c "/data/adb/ksud -V 2>/dev/null"' 2>/dev/null | tr -d '\r\n')
  U2=$(echo "$V2" | grep -oE 'uapi: [0-9]+' | grep -oE '[0-9]+' | head -1)
  if [ "$U2" = "$EXPECTED_UAPI" ]; then
    log "uapi guard: swapped uapi$U -> uapi$U2 (direct)"
    return 0
  fi
  log "uapi guard: direct swap failed, trying b0 channel"
  a shell 'printf "%s\n" "#!/system/bin/sh" "cp -f /data/adb/ksud /data/adb/ksud-uapi2.bak 2>/dev/null" "cp -f /data/local/tmp/ksud-gk /data/adb/ksud" "chmod 755 /data/adb/ksud" > /data/local/tmp/.uapi_swap.sh; chmod 755 /data/local/tmp/.uapi_swap.sh' >/dev/null 2>&1
  a shell 'su -c "/data/local/tmp/b0 atcid_vendor_socket \"sh /data/local/tmp/.uapi_swap.sh #seedkeeper_client\" >/dev/null 2>&1; sleep 2; /data/adb/ksud -V 2>/dev/null"' 2>/dev/null | grep -q "uapi: $EXPECTED_UAPI" \
    && log "uapi guard: swapped via b0 channel" \
    || log "uapi guard: swap FAILED (uapi$U) — 模块安装前需手动处理"
}

log "=== autoroot-v2: cycles=$MAXCYC cap=${CAP}s attempts/boot=$ATT ==="

for cycle in $(seq 1 "$MAXCYC"); do
  log "=== v2 cycle $cycle/$MAXCYC ==="
  wait_boot || { log "no device; abort"; exit 1; }
  UPT=$(a shell 'cut -d. -f1 /proc/uptime' 2>/dev/null | tr -d '\r\n')
  log "boot uptime=${UPT}s"
  check_kernel
  KRC=$?
  if [ "$KRC" = "1" ]; then exit 3; fi
  if [ "$KRC" = "2" ]; then
    a reboot >/dev/null 2>&1
    sleep 8
    continue
  fi
  env_probe
  if root_ok; then
    log "=== ROOT OK (boot had root) ==="
    uapi_guard
    exit 0
  fi
  ensure_fire

  # 瞄准窗口（数据驱动：今天两次成功都在 29s / 36s 开火）
  if [ "$UPT" -lt "$FIRE_AT" ] 2>/dev/null; then
    W=$((FIRE_AT - UPT))
    log "aim window: uptime=${UPT}s < ${FIRE_AT}s, waiting ${W}s"
    sleep "$W"
  fi

  # ---- 借鉴 4: 同 boot 连抽 ATT 次官方（失败 rc=1 仅 ~8s，不复位就再来） ----
  hit=0
  for att in $(seq 1 "$ATT"); do
    log "-- official attempt $att/$ATT (cap ${CAP}s)"
    OUT=$(a shell "cd /data/local/tmp && timeout ${CAP} ./ghostlock --load-prebuilt-profile profile.bin --force-attack >gl_a.log 2>&1; echo rc=\$?" 2>/dev/null | tail -1)
    log "   $OUT"
    sleep 4
    if root_ok; then log "=== ROOT OK after official attempt $att ==="; hit=1; break; fi
    if ! a get-state 2>/dev/null | grep -q device; then
      log "   device lost (wedge/reset) — next boot"
      hit=2
      break
    fi
    E=$(a shell 'cat /sys/fs/selinux/enforce 2>/dev/null' | tr -d '\r\n')
    if [ "$E" = "0" ]; then
      log "   permissive! waiting fire chain"
      for i in $(seq 1 30); do sleep 3; root_ok && { log "=== ROOT OK via fire chain ==="; hit=1; break; }; done
      [ "$hit" = "1" ] && break
    fi
    # 死因摘要（借鉴 RootFamiliar 的 dispatcherReport：每步有据可查）
    a shell 'grep -aE "Write 1|route_done|prepare_kernel_page|p0 kernel" gl_a.log 2>/dev/null | tail -2' 2>/dev/null | tr '\r\n' ' '; echo
    sleep 10
  done

  if [ "$hit" = "1" ]; then
    uapi_guard
    log "=== done ==="
    exit 0
  fi
  # hit=2 表示设备已自己复位，无需再 reboot
  if [ "$hit" != "2" ] && a get-state 2>/dev/null | grep -q device; then
    log "-- reboot for next cycle"
    a reboot >/dev/null 2>&1
    sleep 8
  fi
done
log "=== exhausted $MAXCYC cycles, no root ==="
exit 2
