#!/bin/bash
# gr_autoloop.sh — GhostRoot 全自动提权测试循环
#
# 流程（每轮）：
#   1) 确保设备在一个「未测过的干净 boot」上（崩溃重启后自动复用新 boot，不浪费）
#   2) 动态解析 + 启动 Shizuku（每次重启都要重新授权）
#   3) adb 触发 GhostRoot（am start -a ACTION_ADB_RUN）
#   4) 判定提权：设备端 `su -c id` 返回 uid=0 即真值，命中即停
#
# 关键正确性（针对之前测试的几处坑）：
#   - adb reboot 后**必须等真正掉线**再等上线，避免打在旧 boot 上（竞态）
#   - boot_id 用**全文比对**，不依赖 .fired 文件（可能跨 boot 残留 / 死亡轮次误写）
#   - 提权判定只用 `su -c id` 真值；验证窗口内若 boot_id 变化=崩溃重启，判负
#   - 内核不匹配（uname -r 与预期不符）直接 refuse-to-fire，避免白打浪费 boot
#
# 用法:
#   bash gr_autoloop.sh                 # 默认循环（MAX_ROUNDS=300）
#   MAX_ROUNDS=0 bash gr_autoloop.sh    # 无限循环直到拿到 root
#   VERIFY_TIMEOUT=120 SHIZUKU_WAIT=12 bash gr_autoloop.sh
#
set -u

# ---------- adb（沿用项目自带路径，缺失则回退 PATH） ----------
ADB="/Users/hoyo/WorkBuddy AI/2026-10-28-12-02-20/tools/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb)"
a() { "$ADB" "$@"; }

# ---------- 配置 ----------
PKG="com.hoyo.ghostroot"
SHIZUKU_PKG="moe.shizuku.privileged.api"
ADB_RUN_ACTION="com.hoyo.ghostroot.action.ADB_RUN"
EXPECTED_KERNEL="${EXPECTED_KERNEL:-5.15.197-g346b1c36bd5c-dirty}"  # 你手机实际内核
EXPECTED_UAPI="${EXPECTED_UAPI:-5}"   # 本机内核(g346b1c36bd5c)的 KSU UAPI=5；/data/adb/ksud 若是 uapi2 会导致模块安装/sepolicy 注入全部失败

SHIZUKU_WAIT="${SHIZUKU_WAIT:-10}"      # 启动 Shizuku 后等待秒数
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-90}"  # 触发后判定提权的轮询总时长(秒)
VERIFY_POLL="${VERIFY_POLL:-3}"         # 轮询间隔(秒)
OFFLINE_WAIT="${OFFLINE_WAIT:-60}"      # 等真正掉线的上限(秒)
ONLINE_WAIT="${ONLINE_WAIT:-90}"        # 等上线+boot_completed 的上限(秒)
MAX_ROUNDS="${MAX_ROUNDS:-300}"         # 安全上限，0=无限

LOG_DIR="${HOME}/.cache/gr_autoloop"
mkdir -p "$LOG_DIR"
TEE_LOG="${LOG_DIR}/autoloop.log"
ROOT_OUT=""

# ---------- 日志 ----------
log()  { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$TEE_LOG"; }
warn() { echo "[$(date '+%H:%M:%S')] ⚠ $*" | tee -a "$TEE_LOG" >&2; }
die()  { echo "[$(date '+%H:%M:%S')] ✗ $*" | tee -a "$TEE_LOG" >&2; exit 1; }

# ---------- adb 状态/等待 ----------
adb_state() {
    local s
    s=$(a get-state 2>/dev/null)
    case "$s" in
        device|offline) echo "$s" ;;
        *) echo "no-device" ;;
    esac
}

wait_offline() {
    local waited=0
    while [ "$(adb_state)" = "device" ]; do
        [ "$waited" -ge "$OFFLINE_WAIT" ] && { warn "等待掉线超时(${OFFLINE_WAIT}s)"; return 1; }
        sleep 1; waited=$((waited+1))
    done
    return 0
}

wait_online() {
    local waited=0
    while [ "$(adb_state)" != "device" ]; do
        [ "$waited" -ge "$ONLINE_WAIT" ] && { warn "等待上线超时(${ONLINE_WAIT}s)"; return 1; }
        sleep 1; waited=$((waited+1))
    done
    # 等 sys.boot_completed == 1
    waited=0
    while [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        [ "$waited" -ge "$ONLINE_WAIT" ] && { warn "等待 boot_completed 超时"; return 1; }
        sleep 1; waited=$((waited+1))
    done
    return 0
}

get_boot_id() {
    a shell 'cat /proc/sys/kernel/random/boot_id 2>/dev/null' 2>/dev/null | tr -d '\r' | tr -d ' '
}

get_kernel() {
    local kr=""
    for _ in 1 2 3 4 5; do
        kr=$(a shell 'uname -r 2>/dev/null' 2>/dev/null | tr -d '\r\n')
        [ -n "$kr" ] && break
        sleep 2
    done
    echo "$kr"
}

# 确保设备在线且启动完成（不强制重启）
ensure_ready() {
    [ "$(adb_state)" = "device" ] || wait_online || return 1
    local waited=0
    while [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
        [ "$waited" -ge "$ONLINE_WAIT" ] && return 1
        sleep 1; waited=$((waited+1))
    done
    return 0
}

# ---------- Shizuku ----------
find_shizuku_bin() {
    # 安装路径每次会变（~~XXX==），动态解析 libshizuku.so
    # 崩溃重启后 pm path 可能短暂读不到（包管理未就绪），重试几次
    local apk dir n
    for n in 1 2 3 4 5; do
        apk=$(a shell "pm path $SHIZUKU_PKG 2>/dev/null" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^package://')
        [ -n "$apk" ] && break
        sleep 2
    done
    [ -z "$apk" ] && { echo ""; return 1; }
    dir=$(dirname "$apk")
    echo "$dir/lib/arm64/libshizuku.so"
}

start_shizuku() {
    local bin
    bin=$(find_shizuku_bin)
    if [ -z "$bin" ]; then
        warn "找不到 Shizuku 库路径（$SHIZUKU_PKG 未安装？）"
        return 1
    fi
    log "→ 启动 Shizuku: $bin"
    # 后台启动，避免 adb shell 被常驻 server 阻塞
    a shell "nohup '$bin' >/dev/null 2>&1 &" 2>/dev/null
    sleep "$SHIZUKU_WAIT"
    if a shell "ps -A 2>/dev/null | grep -i shizuku | grep -v grep" 2>/dev/null | grep -q shizuku; then
        log "  Shizuku 进程已存在"
    else
        warn "  未检测到 Shizuku 进程（可能启动失败，本回合 GhostRoot 可能无法绑定时权）"
    fi
    return 0
}

# ---------- GhostRoot ----------
trigger_ghostroot() {
    # 触发前复验 adb 在线，am start 输出含 "Starting" 才算成功；最多重试 3 次
    local n out
    for n in 1 2 3; do
        if [ "$(adb_state)" != "device" ]; then
            warn "触发前 adb 不在线（第 $n 次），5s 后重试"
            sleep 5
            continue
        fi
        out=$(a shell "am start -a $ADB_RUN_ACTION -n $PKG/.MainActivity" 2>&1 | tr -d '\r')
        echo "$out" | tee -a "$TEE_LOG" >/dev/null
        if echo "$out" | grep -q "Starting"; then
            log "→ GhostRoot 已触发（第 $n 次尝试）"
            return 0
        fi
        warn "am start 未成功（第 $n 次）"
        sleep 5
    done
    return 1
}

# ---------- 提权真值判定 ----------
root_ok() {
    local out
    out=$(a shell 'su -c id 2>/dev/null || /data/adb/ksu/bin/su -c id 2>/dev/null' 2>/dev/null | tr -d '\r')
    if echo "$out" | grep -q "uid=0"; then
        ROOT_OUT="$out"
        return 0
    fi
    return 1
}

verify_root() {
    # $1 = 触发前的 boot_id；窗口内若 boot 变化=崩溃，判负
    local boot0="$1"
    local waited=0
    while [ "$waited" -lt "$VERIFY_TIMEOUT" ]; do
        local b
        b=$(get_boot_id)
        if [ -n "$b" ] && [ "$b" != "$boot0" ]; then
            warn "  验证窗口内 boot_id 变化($boot0 -> $b)，疑似崩溃重启，本轮判负"
            return 1
        fi
        if root_ok; then
            log "  ✓ su 返回 root: $ROOT_OUT"
            return 0
        fi
        sleep "$VERIFY_POLL"; waited=$((waited+VERIFY_POLL))
    done
    return 1
}

force_reboot() {
    log "→ 发送 reboot"
    a reboot 2>/dev/null
    wait_offline || true
    wait_online || die "重启后设备未恢复"
}

# ---------- 落地后守卫：ksud UAPI ----------
# 2026-11-03 教训：落地后 /data/adb/ksud 可能是 uapi2（链内 swap 只在 autoroot-v2 里做，
# autoloop 之前不做）——后果：ksud module install 报 UAPI mismatch、vivo_net_fix 的
# sepolicy 规则静默不注入（Enforcing 下网络必断）、bszip 安装器内刷三件套全挂。
# 参考 PathMask 离线分析：struct module 1024(GKI) vs 960(vivo)，任何 LKM 路线已判死，
# 模块体系全靠 ksud 正常工作，此守卫是落地后的必要一步。
ksud_uapi() {
    a shell 'su -c "/data/adb/ksud -V 2>/dev/null"' 2>/dev/null | tr -d '\r' \
        | grep -oE 'uapi: [0-9]+' | grep -oE '[0-9]+' | head -1
}

uapi_guard() {
    local u
    u=$(ksud_uapi)
    if [ "$u" = "$EXPECTED_UAPI" ]; then
        log "uapi guard: /data/adb/ksud uapi$u ok"
        return 0
    fi
    warn "uapi guard: ksud uapi${u:-?} != 期望 uapi${EXPECTED_UAPI}，尝试换用 /data/local/tmp/ksud-gk"
    # 备源检查：ksud-gk 不在设备上则无能为力（只告警，不失败——root 本身已到手）
    if ! a shell 'ls /data/local/tmp/ksud-gk >/dev/null 2>&1'; then
        warn "uapi guard: /data/local/tmp/ksud-gk 不存在，跳过替换（请手动处理）"
        return 1
    fi
    a shell 'su -c "cp -f /data/adb/ksud /data/adb/ksud-uapi2.bak 2>/dev/null; cp -f /data/local/tmp/ksud-gk /data/adb/ksud; chown 0:0 /data/adb/ksud; chmod 755 /data/adb/ksud"' >/dev/null 2>&1
    u=$(ksud_uapi)
    if [ "$u" = "$EXPECTED_UAPI" ]; then
        log "uapi guard: 已替换为 uapi$u"
        return 0
    fi
    warn "uapi guard: 替换后仍为 uapi${u:-?}（可能与 autoroot-v2 的 b0 通道一样需要绕 vivo LSM），请手动处理"
    return 1
}

# ---------- 落地后状态快照（死因/状态归因用，全部尽力而为不阻塞） ----------
post_root_snapshot() {
    log "---- 落地后状态快照 ----"
    log "ksud: $(a shell 'su -c "/data/adb/ksud -V 2>/dev/null"' 2>/dev/null | tr -d '\r' | head -1)"
    log "selinux: $(a shell 'getenforce 2>/dev/null' 2>/dev/null | tr -d '\r')"
    log "net(canary): $(a shell 'ping -c1 -W2 223.5.5.5 >/dev/null 2>&1 && echo OK || echo DEAD' 2>/dev/null | tr -d '\r')"
    log "modules: $(a shell 'su -c "ls -1 /data/adb/modules/ 2>/dev/null" | tr -d "\r" | tr "\n" " "' 2>/dev/null)"
    log "disabled: $(a shell 'su -c "ls /data/adb/modules/*/disable 2>/dev/null" | tr -d "\r" | tr "\n" " "' 2>/dev/null)"
    log "modules_update: $(a shell 'su -c "ls -1 /data/adb/modules_update/ 2>/dev/null" | tr -d "\r" | tr "\n" " "' 2>/dev/null)"
    log "------------------------"
}

# ---------- 主循环 ----------
log "===== GhostRoot 自动提权循环开始 (MAX_ROUNDS=$MAX_ROUNDS, VERIFY_TIMEOUT=${VERIFY_TIMEOUT}s) ====="
log "adb=$ADB  expect_kernel=$EXPECTED_KERNEL"

ensure_ready || die "初始设备未就绪"

TESTED_BOOT=""
SHIZ_FAIL=0
ROUND=0
while true; do
    [ "$MAX_ROUNDS" -gt 0 ] && [ "$ROUND" -ge "$MAX_ROUNDS" ] && { warn "已达 MAX_ROUNDS，停止"; break; }
    ROUND=$((ROUND+1))
    log "===== 第 $ROUND 轮 ====="

    ensure_ready || { warn "设备掉线，尝试恢复"; force_reboot || continue; }

    BOOT=$(get_boot_id)
    if [ -z "$BOOT" ]; then warn "无法读取 boot_id，跳过本轮"; sleep 3; continue; fi

    # 内核护栏：不匹配直接 refuse-to-fire（避免白打浪费 boot）
    KR=$(get_kernel)
    if [ -n "$KR" ] && [ "$KR" != "$EXPECTED_KERNEL" ]; then
        warn "内核不符 [$KR] != [$EXPECTED_KERNEL]，拒绝开火，重启换 boot"
        force_reboot || continue
        TESTED_BOOT=""
        continue
    fi
    [ -n "$KR" ] && log "内核校验通过: $KR"

    if [ "$BOOT" = "$TESTED_BOOT" ]; then
        # 同一 boot 已触发过且未提权 → 强制重启拿干净 boot
        log "本 boot($BOOT) 已测过且未提权，强制重启"
        force_reboot || continue
        TESTED_BOOT=""
        continue
    fi
    if ! start_shizuku; then
        SHIZ_FAIL=$((SHIZ_FAIL+1))
        warn "Shizuku 启动失败（连续 $SHIZ_FAIL 次）"
        if [ "$SHIZ_FAIL" -ge 3 ]; then
            die "Shizuku 连续 $SHIZ_FAIL 次无法启动，可能已被卸载/禁用，停止"
        fi
        force_reboot || continue
        TESTED_BOOT=""
        continue
    fi
    SHIZ_FAIL=0

    # 触发前复验 adb + 失败重试（round2 教训：崩溃后 adb 闪断，am start 打空
    # 浪费整个 boot）；触发成功才标记 TESTED_BOOT
    if ! trigger_ghostroot; then
        warn "触发失败，本 boot 作废，强制重启换新 boot"
        force_reboot || continue
        TESTED_BOOT=""
        continue
    fi
    TESTED_BOOT="$BOOT"

    if verify_root "$BOOT"; then
        log "🎉🎉🎉 第 $ROUND 轮成功提权！停止循环。"
        uapi_guard || true
        post_root_snapshot
        {
            echo "==== ROOT OBTAINED at $(date) ===="
            echo "boot_id: $BOOT"
            echo "round:   $ROUND"
            echo "kernel:  $(get_kernel)"
            echo "su -c id:"
            a shell 'su -c id 2>/dev/null' 2>/dev/null | tr -d '\r'
        } | tee -a "$TEE_LOG"
        break
    else
        warn "本 boot 未提权"
        # 崩溃已自动重启 → 下一轮直接测新 boot；未崩溃 → 下一轮会强制重启
        a shell 'logcat -d -t 60 -s GhostRoot:V 2>/dev/null' 2>/dev/null | tail -15 | tee -a "$TEE_LOG" >/dev/null
    fi
done

log "===== 循环结束 (rounds=$ROUND) ====="
