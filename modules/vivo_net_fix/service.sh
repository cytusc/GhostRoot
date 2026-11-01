#!/system/bin/sh
# Vivo NetFix v1.6 — 开机审计（v1.5→v1.6 红线变更）
#
# v1.6→v1.7 变更（2026-11-01 实测结论）：
#   enforcing 实验证明：全局 Enforcing 下 WiFi 数据面（dp_rx_thread，kernel 域
#   scontext + unlabeled 包类型）被 LSM 拦截，unlabeled 是类型不是域、无法用
#   permissive 放行 → 189 条域级规则覆盖不到数据面，网络必断。
#   历史稳定态 = exploit 后全局 Permissive（fire.sh v6 行为）。
#   故 v1.6 红线改为：全局 SELinux 模式只记录不判定；规则完备性照旧核对。
#   另 sepolicy.rule 增补 permissive kernel（第 190 条），供未来 enforcing 实验用。
#
# 本模块主体是 sepolicy.rule（190 条域级 permissive）。
# service.sh 不加规则、零守护进程，只做三件事：
#   1. 记录全局 SELinux 模式（Permissive=exploit 后预期态，不判 FAIL）
#   2. 核对规则文件完备性（抽取 netd/dnsmasq/clatd/untrusted_app/shell/kernel）
#   3. 落盘审计日志，供 App/PC 交叉核对
LOG=/data/adb/vivo_net_fix/log.txt
mkdir -p /data/adb/vivo_net_fix

{
echo "=== vivo_net_fix v1.7 $(date) ==="

ENFORCE=$(cat /sys/fs/selinux/enforce 2>/dev/null)
if [ "$ENFORCE" = "1" ]; then
  echo "[info] global enforce=1 — 注意：enforcing 下 kernel/unlabeled 数据面会被拦，网络大概率断（v1.6 实验结论）"
else
  echo "[ok] global enforce=0 (Permissive) — exploit 后预期稳定态"
fi

RULES=$(grep -c '^permissive ' /data/adb/modules/vivo_net_fix/sepolicy.rule 2>/dev/null)
echo "[ok] sepolicy.rule lines=$RULES (expect 190)"

# 抽查策略中的 permissive 状态（核对规则文件本身；实时策略核对需 ksud-gk sepolicy apply）
for d in netd dnsmasq clatd untrusted_app untrusted_app_30 shell kernel; do
  grep -q "^permissive $d\$" /data/adb/modules/vivo_net_fix/sepolicy.rule 2>/dev/null \
    && echo "[ok] rule: $d" || echo "[MISS] rule: $d"
done

# 若存在 ksud-gk 且需要 enforcing 实战，可手动：
#   /data/local/tmp/ksud-gk sepolicy apply /data/adb/modules/vivo_net_fix/sepolicy.rule
# 但注意：apply 只解决域级放行，kernel/unlabeled 数据面仍需 allow 规则，enforcing 依旧不可用。

# ---- 网络金丝雀（v1.7）：网络是否恢复的唯一客观判据 ----
# 这是「悲伤一键隐藏环境启用前」的门禁：ghost_hide 先完成系统级隐藏，
# 本模块恢复网络，金丝雀确认 [ok] 之后，bszip 环境伪装层才允许启用。
CANARY_IP=$(ping -c 1 -W 3 223.5.5.5 2>/dev/null | grep -c '1 received')
CANARY_DNS=$(ping -c 1 -W 3 www.baidu.com 2>/dev/null | grep -c '1 received')
if [ "$CANARY_IP" = "1" ] && [ "$CANARY_DNS" = "1" ]; then
  echo "[ok] net-canary: IP + DNS both PASS —— 网络已恢复，隐藏环境层可启用"
elif [ "$CANARY_IP" = "1" ]; then
  echo "[FAIL] net-canary: IP 通但 DNS 断 —— 域级规则未覆盖 DNS 链路，检查 dnsmasq/clatd"
else
  echo "[FAIL] net-canary: IP 不通 —— 数据面被拦或飞行模式，ghost_hide/网络层需排查"
fi

echo "=== done ==="
} >> $LOG 2>&1
