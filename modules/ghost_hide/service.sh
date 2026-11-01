#!/system/bin/sh
# Ghost Hide v0.3 — 系统级 root 兜底屏蔽（开机一次性，KSU late-load 后执行，零守护进程）
#
# 分工（2026-11-01 与用户定案）：
#   - 细粒度按应用屏蔽管理 → Hide-My-Applist (Vector/LSPosed + ZygiskNext)
#   - 系统级 root 兜底屏蔽 → 本模块：无论 App 层怎么配，以下底线永远成立
#   - 网络修复             → vivo_net_fix
#
# 兜底线：
#   A. 非 KSU 的 su 二进制一律 chmod 000（只认 /system/bin/su = KSU 本体，
#      其对 App 域由 KSU 内核原生遮蔽）
#   B. /proc/modules 440 —— kernelsu 内核模块条目对 App/shell 不可读
#   C. root 授权审计 —— 从 KSU 授权库提取持 root 的包，系统级/vivo 探针
#      包持 root 直接告警（正常状态：只有 GhostRoot/KSU 管理器/白名单）
#   D. 反干扰守卫 —— vivo_net_fix 的 disable 标记被外部清除
#      （2026-11-01 12:01 月虹干扰事件，doc/04 F5）
LOG=/data/adb/ghost_hide/log.txt
mkdir -p /data/adb/ghost_hide

{
echo "=== ghost_hide v0.3 $(date) ==="

# ---- A. 非 KSU su 残留兜底 ----
for p in /system/xbin/su /sbin/su /su/bin/su /vendor/bin/su /system/app/Superuser.apk; do
  if [ -e "$p" ]; then
    chmod 000 "$p" 2>/dev/null
    echo "[fallback] non-KSU su artifact neutralized: $p (chmod 000)"
  fi
done
[ -e /system/bin/su ] && echo "[ok] KSU su present (/system/bin/su, kernel-level app gating)" \
                      || echo "[warn] /system/bin/su absent (KSU su 未部署?)"

# ---- B. /proc/modules 应用可见性关闭 ----
chmod 440 /proc/modules 2>/dev/null
PERM=$(stat -c %a /proc/modules 2>/dev/null)
[ "$PERM" = "440" ] && echo "[ok] proc/modules mode=440" || echo "[FAIL] proc/modules mode=$PERM (expect 440)"

# ---- C. root 授权审计（兜底证据）----
# KSU 默认策略 = 拒绝一切未授权 App（这就是系统级兜底）。授权库懒创建：
# 没有任何 App 持久授权时不存在文件 → 直接判定"默认拒绝生效"。
# 一旦存在授权，提取包名核对白名单；系统级/vivo 探针包持 root 直接告警。
DB=""
for c in /data/adb/ksu.db /data/adb/ksu/db /data/adb/ksud/ksu.db; do
  [ -f "$c" ] && DB="$c" && break
done
[ -z "$DB" ] && DB=$(find /data/adb -maxdepth 2 -name '*.db' 2>/dev/null | head -1)
if [ -n "$DB" ]; then
  GRANTED=$(strings "$DB" 2>/dev/null | grep -E '^[a-z][a-z0-9_]+(\.[a-z0-9_]+)+$' | sort -u | head -30)
  echo "[audit] root-grant db: $DB"
  echo "$GRANTED" | sed 's/^/[audit]   /'
  for pkg in $GRANTED; do
    case "$pkg" in
      com.hoyo.ghostroot|me.weishu.kernelsu|com.tsng.hidemyapplist) ;;  # 白名单
      *com.vivo*|*com.bbk*|*com.iqoo*)
        echo "[ALERT] system/probe package holds root grant: $pkg — 请立即在 KSU 管理器撤销！";;
    esac
  done
else
  echo "[ok] grant db 不存在（尚无持久授权）— KSU 默认拒绝策略生效，兜底成立"
fi

# ---- D. 反干扰守卫 ----
if [ -f /data/adb/modules/vivo_net_fix/disable ]; then
  rm -f /data/adb/modules/vivo_net_fix/disable
  echo "[guard] netfix disable-marker REMOVED (interference!)"
else
  echo "[ok] netfix clean"
fi

# ---- E. 环境基线快照 ----
echo "[audit] selinuxfs enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null || echo unreadable)"
echo "[audit] props tags=$(getprop ro.build.tags) debuggable=$(getprop ro.debuggable) vbmeta=$(getprop ro.boot.vbmeta.device_state)"
echo "[audit] modules: $(ls /data/adb/modules 2>/dev/null | tr '\n' ' ')"
for m in /data/adb/modules/*/disable; do
  [ -e "$m" ] && echo "[audit] disabled: $m"
done

echo "=== done ==="
} >> $LOG 2>&1
