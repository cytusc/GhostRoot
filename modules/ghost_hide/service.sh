#!/system/bin/sh
# Ghost Hide v0.5 — 定向 root 屏蔽（开机一次性，KSU late-load 后执行，零守护进程）
#
# v0.5 范围修正（2026-11-02 用户定案，替换 v0.4 的宽范围）：
#   - App 级定向：只屏蔽 银行类 / 支付类 / 聊天类（购物类移出——它们
#     由 KSU 内核默认拒绝兜底即可，不做定向处理）
#   - 系统级定向：只针对 vivo 反 root 探针（i管家/权限管理等 vivo 安全包
#     + vr.ko 内核探针的可见面），不再宽泛"屏蔽所有系统级"
#   - 保留的硬底线（这两条天然全局、无法按包收窄，也是最小集合）：
#       A. 非 KSU su 残留 chmod 000
#       B. /proc/modules 440（vr.ko / kernelsu 条目对 App 域不可见）
#
# 分工（沿用 2026-11-01 定案）：
#   - 细粒度按应用屏蔽管理 → Hide-My-Applist (Vector/LSPosed + ZygiskNext)
#   - 系统级定向屏蔽       → 本模块
#   - 网络修复             → vivo_net_fix（本模块绝不触碰网络）
#   - 环境伪装             → 悲伤一键隐藏环境（bszip）
LOG=/data/adb/ghost_hide/log.txt
mkdir -p /data/adb/ghost_hide

# 敏感 App 清单（v0.5：银行 / 支付 / 聊天；购物类已按用户要求移出）
SENSITIVE_APPS="
com.eg.android.AlipayGphone   # 支付宝（支付）
com.tencent.mm                # 微信（聊天+支付）
com.tencent.mobileqq          # QQ（聊天+支付）
com.unionpay                  # 云闪付（支付）
com.icbc                      # 工商银行
com.chinamworld.main          # 建设银行
com.android.bankabc           # 农业银行
com.chinamworld.bocmbci       # 中国银行
cmb.pb                        # 招商银行
"

# vivo 反 root 探针定向清单（v0.5 新增：系统级只管这些）
VIVO_PROBE_PKGS="
com.iqoo.secure               # i管家（vivo 安全中心，root 检测主力）
com.vivo.permissionmanager    # vivo 权限管理
"

{
echo "=== ghost_hide v0.5 $(date) ==="

# ---- A. 非 KSU su 残留兜底（硬底线，天然全局）----
for p in /system/xbin/su /sbin/su /su/bin/su /vendor/bin/su /system/app/Superuser.apk; do
  if [ -e "$p" ]; then
    chmod 000 "$p" 2>/dev/null
    echo "[fallback] non-KSU su artifact neutralized: $p (chmod 000)"
  fi
done
[ -e /system/bin/su ] && echo "[ok] KSU su present (/system/bin/su, kernel-level app gating)" \
                      || echo "[warn] /system/bin/su absent (KSU su 未部署?)"

# ---- B. /proc/modules 应用可见性关闭（硬底线，针对 vr.ko/kernelsu 条目）----
chmod 440 /proc/modules 2>/dev/null
PERM=$(stat -c %a /proc/modules 2>/dev/null)
[ "$PERM" = "440" ] && echo "[ok] proc/modules mode=440" || echo "[FAIL] proc/modules mode=$PERM (expect 440)"

# ---- C. root 授权审计（兜底证据）----
DB=""
for c in /data/adb/ksu.db /data/adb/ksu/db /data/adb/ksud/ksu.db; do
  [ -f "$c" ] && DB="$c" && break
done
[ -z "$DB" ] && DB=$(find /data/adb -maxdepth 2 -name '*.db' 2>/dev/null | head -1)
GRANTED=""
if [ -n "$DB" ]; then
  GRANTED=$(strings "$DB" 2>/dev/null | grep -E '^[a-z][a-z0-9_]+(\.[a-z0-9_]+)+$' | sort -u | head -40)
  echo "[audit] root-grant db: $DB"
  echo "$GRANTED" | sed 's/^/[audit]   /'
  for pkg in $GRANTED; do
    case "$pkg" in
      com.hoyo.ghostroot|me.weishu.kernelsu|com.tsng.hidemyapplist) ;;  # 白名单
      *com.vivo*|*com.bbk*|*com.iqoo*)
        echo "[ALERT] vivo/probe package holds root grant: $pkg — 请立即在 KSU 管理器撤销！";;
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

# ---- E. 敏感 App 定向（v0.5：只管银行/支付/聊天）----
# 分层：root 接触面 = KSU 内核默认拒绝（此处审计兜底）；
#       环境可见性 = ZygiskNext denylist（bszip 已开 enforce=2）。
echo "[sensitive] ---- 银行/支付/聊天 定向开始 ----"
SENS_PKG=$(echo "$SENSITIVE_APPS" | grep -v '^#' | grep -v '^$' | awk '{print $1}')
for pkg in $SENS_PKG; do
  if pm path "$pkg" >/dev/null 2>&1; then
    if echo "$GRANTED" | grep -q "^$pkg\$"; then
      echo "[ALERT] sensitive app holds ROOT grant: $pkg — 立即在 KSU 管理器撤销！"
    else
      echo "[ok] $pkg installed, no root grant (kernel default-deny)"
    fi
  fi
done
echo "[sensitive] ---- 定向结束（购物类已移出，由内核默认拒绝兜底） ----"

# ---- H. vivo 反 root 探针定向（v0.5 新增，系统级唯一扩展）----
# 探针的 root 接触面已被 KSU 默认拒绝封死；这里做两件事：
#   1) 审计探针包是否被授予 root（被授 = 最高级告警）
#   2) 探针包尝试加入 ZN denylist（对探针隐藏 root 环境挂载）
# 注意：真正的内核级探针是 vr.ko（由 one.sh 的 W1c 摘除，不归本模块管）；
#       atcid daemon 是我们的 b0 通道载体，绝不能动。
echo "[vivo-probe] ---- vivo 反root探针定向开始 ----"
VIVO_PKG=$(echo "$VIVO_PROBE_PKGS" | grep -v '^#' | grep -v '^$' | awk '{print $1}')
for pkg in $VIVO_PKG; do
  if pm path "$pkg" >/dev/null 2>&1; then
    if echo "$GRANTED" | grep -q "^$pkg\$"; then
      echo "[ALERT] vivo probe holds ROOT grant: $pkg — 立即撤销！"
    else
      echo "[ok] probe $pkg: no root grant"
    fi
  else
    echo "[info] probe $pkg 未安装"
  fi
done
# 所有 com.vivo*/com.bbk*/com.iqoo* 包一次性清点（防清单遗漏）
VIVO_ALL=$(pm list packages 2>/dev/null | grep -E 'com\.(vivo|bbk|iqoo)' | sed 's/package://' | head -30)
echo "[vivo-probe] vivo/bbk/iqoo 包总数: $(echo "$VIVO_ALL" | grep -c . )"
echo "$VIVO_ALL" | sed 's/^/[vivo-probe]   /'
echo "[vivo-probe] ---- 定向结束 ----"

# ---- ZN denylist 写入尝试（敏感包 + 探针包一并）----
ZN_CFG=""
for c in /data/adb/zygisksu/config /data/adb/modules/zygisksu/config \
         /data/adb/zygisksu/denylist /data/adb/modules/zygisksu/denylist; do
  [ -f "$c" ] && ZN_CFG="$c" && break
done
if [ -n "$ZN_CFG" ]; then
  MISS=""
  for pkg in $SENS_PKG $VIVO_PKG; do
    pm path "$pkg" >/dev/null 2>&1 || continue
    grep -q "$pkg" "$ZN_CFG" 2>/dev/null || MISS="$MISS $pkg"
  done
  if [ -n "$MISS" ]; then
    for pkg in $MISS; do
      echo "\"$pkg\"" >> "$ZN_CFG" 2>/dev/null \
        && echo "[ok] ZN denylist + $pkg" \
        || echo "[warn] ZN denylist 写入失败: $pkg（请在 ZN 管理器手动勾选）"
    done
  else
    echo "[ok] ZN denylist 已覆盖敏感包+探针包"
  fi
else
  echo "[info] ZN denylist 无可编程配置文件 —— 请在 ZygiskNext 管理器 denylist 勾选："
  for pkg in $SENS_PKG $VIVO_PKG; do
    pm path "$pkg" >/dev/null 2>&1 && echo "[info]   $pkg"
  done
fi
[ -d /data/adb/modules/bszip ] && echo "[audit] bszip(悲伤一键) module present" || true
[ -d /data/adb/modules/zygisk_vector ] && echo "[audit] vector(LSPosed) present" || true
[ -d /data/adb/modules/zygisksu ] && echo "[audit] zygisksu(ZygiskNext) present" || true

# ---- F. 自同步通道 ----
SYNC_ROOT=/data/local/tmp/ghost-module-sync
if [ -d "$SYNC_ROOT" ]; then
  for mod in "$SYNC_ROOT"/*/; do
    mid=$(basename "$mod")
    [ -d "/data/adb/modules/$mid" ] || continue
    for f in "$mod"*; do
      fname=$(basename "$f")
      case "$fname" in *.sha256|*.log) continue;; esac
      if cp "$f" "/data/adb/modules/$mid/$fname" 2>/dev/null; then
        chmod 644 "/data/adb/modules/$mid/$fname" 2>/dev/null
        [ "$fname" = "service.sh" ] && chmod 755 "/data/adb/modules/$mid/$fname" 2>/dev/null
        echo "[sync] updated: $mid/$fname"
      else
        echo "[warn] sync blocked (vivo LSM): $mid/$fname — 下次开火窗口重试"
      fi
    done
  done
  rm -rf "$SYNC_ROOT"
else
  echo "[ok] no staged module updates"
fi

# ---- G. 环境基线快照 ----
echo "[audit] selinuxfs enforce=$(cat /sys/fs/selinux/enforce 2>/dev/null || echo unreadable)"
echo "[audit] props tags=$(getprop ro.build.tags) debuggable=$(getprop ro.debuggable) vbmeta=$(getprop ro.boot.vbmeta.device_state)"
echo "[audit] modules: $(ls /data/adb/modules 2>/dev/null | tr '\n' ' ')"
for m in /data/adb/modules/*/disable; do
  [ -e "$m" ] && echo "[audit] disabled: $m"
done

echo "=== done ==="
} >> $LOG 2>&1
