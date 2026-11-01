#!/system/bin/sh
# Ghost Hide v0.4 — 系统级 root 兜底屏蔽（开机一次性，KSU late-load 后执行，零守护进程）
#
# 分工（2026-11-01 与用户定案）：
#   - 细粒度按应用屏蔽管理 → Hide-My-Applist (Vector/LSPosed + ZygiskNext)
#   - 系统级 root 兜底屏蔽 → 本模块：无论 App 层怎么配，以下底线永远成立
#   - 网络修复             → vivo_net_fix（先于本模块的 hide 生效顺序：
#     本模块绝不做任何触碰网络的操作，vivo_net_fix 在其后恢复网络）
#   - 环境伪装             → 悲伤一键隐藏环境（bszip，网络确认正常后启用）
#
# 兜底线：
#   A. 非 KSU 的 su 二进制一律 chmod 000（只认 /system/bin/su = KSU 本体，
#      其对 App 域由 KSU 内核原生遮蔽）
#   B. /proc/modules 440 —— kernelsu 内核模块条目对 App/shell 不可读
#   C. root 授权审计 —— 从 KSU 授权库提取持 root 的包，系统级/vivo 探针包
#      与敏感包（银行/支付/购物）持 root 直接告警
#   D. 反干扰守卫 —— vivo_net_fix 的 disable 标记被外部清除
#   E. 敏感 App 定向加固 —— 银行/支付/购物类 App 的 root 接触面审计
#   F. 自同步通道 —— /data/local/tmp/ghost-module-sync/ 的模块更新在
#      开机（模块自身 root 上下文）落地，绕开平时 vivo LSM 的防篡改封锁
LOG=/data/adb/ghost_hide/log.txt
mkdir -p /data/adb/ghost_hide

# 敏感 App 清单（银行/支付/购物/社交支付）——v0.4 定向加固对象
SENSITIVE_APPS="
com.eg.android.AlipayGphone   # 支付宝
com.tencent.mm                # 微信（含微信支付）
com.tencent.mobileqq          # QQ（含 QQ 钱包）
com.unionpay                  # 云闪付
com.taobao.taobao             # 淘宝
com.jingdong.app.mall         # 京东
com.xunmeng.pinduoduo         # 拼多多
com.sankuai.meituan           # 美团
com.icbc                      # 工商银行
com.chinamworld.main          # 建设银行
com.android.bankabc           # 农业银行
com.chinamworld.bocmbci       # 中国银行
cmb.pb                        # 招商银行
"

{
echo "=== ghost_hide v0.4 $(date) ==="

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
GRANTED=""
if [ -n "$DB" ]; then
  GRANTED=$(strings "$DB" 2>/dev/null | grep -E '^[a-z][a-z0-9_]+(\.[a-z0-9_]+)+$' | sort -u | head -40)
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

# ---- E. 敏感 App 定向加固（银行/支付/购物）----
# 原理分层：root 接触面 = KSU 内核默认拒绝（本模块审计兜底）；
# 包名/组件可见性 = HMA + ZygiskNext denylist（App 层）；
# 环境指纹（keybox/prop/mount）= bszip 悲伤一键（网络正常后启用）。
echo "[sensitive] ---- 定向加固开始 ----"
SENS_PKG=$(echo "$SENSITIVE_APPS" | grep -v '^#' | grep -v '^$' | awk '{print $1}')
for pkg in $SENS_PKG; do
  if pm path "$pkg" >/dev/null 2>&1; then
    # 敏感包持 root = 最高级告警
    if echo "$GRANTED" | grep -q "^$pkg\$"; then
      echo "[ALERT] sensitive app holds ROOT grant: $pkg — 立即在 KSU 管理器撤销！"
    else
      echo "[ok] $pkg installed, no root grant (kernel default-deny)"
    fi
  fi
done
# ZygiskNext denylist 探测（可编程配置时才动，否则只报告）
ZN_CFG=""
for c in /data/adb/zygisksu/config /data/adb/modules/zygisksu/config \
         /data/adb/zygisksu/denylist /data/adb/modules/zygisksu/denylist; do
  [ -f "$c" ] && ZN_CFG="$c" && break
done
if [ -n "$ZN_CFG" ]; then
  MISS=""
  for pkg in $SENS_PKG; do
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
    echo "[ok] ZN denylist 已覆盖全部敏感包"
  fi
else
  echo "[info] ZN denylist 无可编程配置文件 —— 请在 ZygiskNext 管理器 denylist 中勾选敏感包（清单见本日志）"
fi
# 隐藏环境层状态（bszip 悲伤一键 / HMA 框架）
[ -d /data/adb/modules/bszip ] && echo "[audit] bszip(悲伤一键) module present" \
                               || echo "[info] bszip(悲伤一键) 未安装——按启用顺序待网络确认后安装"
[ -d /data/adb/modules/zygisk_vector ] && echo "[audit] vector(LSPosed) present" || true
[ -d /data/adb/modules/zygisksu ] && echo "[audit] zygisksu(ZygiskNext) present" || true
echo "[sensitive] ---- 定向加固结束 ----"

# ---- F. 自同步通道 ----
# 平时 vivo LSM 封锁 /data/adb/modules 写入（service.sh inode 防篡改）。
# 开机时本脚本以模块 root 上下文运行，把 /data/local/tmp/ghost-module-sync/
# 下的待更新文件铺进模块目录（best-effort，LSM 拦截则留待下次开火窗口）。
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
