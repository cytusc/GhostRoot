#!/bin/bash
# gr_autoloop.sh — GhostRoot 全自动提权测试循环
#
# 流程（每轮）：
#   1) 确保设备在一个「未测过的干净 boot」上（崩溃重启后自动复用新 boot，不浪费）
#   2) 动态解析 + 启动 Shizuku（每次重启都要重新授权）
#   3) adb 触发 GhostRoot（am start -a ACTION_ADB_RUN）
#   4) 判定提权：设备端 `su -c id` 返回 uid=0 即真值，命中即停
#   5) 触发 ZN 一键修复（am start -a ACTION_ADB_REPAIR）并验证修复结果：
#        · 本 boot 新鲜的 .gr_zn_repair 留痕（outcome=zn-ok*）
#        · zygiskd status：zygote_states>=2 / 双模块已加载 / modules_with_issue=0
#        · dex2oat-flags 不存在（工行兼容） + .abort_msg 闩锁不存在（daemon 可启）
#        · outcome=zn-softreboot-scheduled 时等框架软重启恢复后再触发一次复查
#      验证失败不回滚 root（提权结果已保留），但脚本以退出码 2 告知自动化
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
#   REPAIR_WAIT=60 SOFTREBOOT_WAIT=150 bash gr_autoloop.sh
#
# 退出码: 0=提权(+修复验证)完成或正常结束；2=提权成功但修复验证未通过
# 依赖: GhostRoot >= v51（ACTION_ADB_REPAIR 入口，低版本 am start 会静默忽略）
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
ADB_REPAIR_ACTION="com.hoyo.ghostroot.action.ADB_REPAIR"
EXPECTED_KERNEL="${EXPECTED_KERNEL:-5.15.197-g346b1c36bd5c-dirty}"  # 你手机实际内核
EXPECTED_UAPI="${EXPECTED_UAPI:-5}"   # 本机内核(g346b1c36bd5c)的 KSU UAPI=5；/data/adb/ksud 若是 uapi2 会导致模块安装/sepolicy 注入全部失败

SHIZUKU_WAIT="${SHIZUKU_WAIT:-10}"      # 启动 Shizuku 后等待秒数
VERIFY_TIMEOUT="${VERIFY_TIMEOUT:-90}"  # 触发后判定提权的轮询总时长(秒)
VERIFY_POLL="${VERIFY_POLL:-3}"         # 轮询间隔(秒)
OFFLINE_WAIT="${OFFLINE_WAIT:-60}"      # 等真正掉线的上限(秒)
ONLINE_WAIT="${ONLINE_WAIT:-90}"        # 等上线+boot_completed 的上限(秒)
MAX_ROUNDS="${MAX_ROUNDS:-300}"         # 安全上限，0=无限

# ---------- 提权后 ZN 修复验证配置 ----------
REPAIR_TRIGGERS="${REPAIR_TRIGGERS:-3}"        # 触发重试次数（提权会话未收尾时 running 守卫会吞掉修复）
REPAIR_WAIT="${REPAIR_WAIT:-45}"                # 每次触发后等待本 boot 修复留痕的秒数
REPAIR_POLL="${REPAIR_POLL:-3}"                 # 留痕/终态轮询间隔(秒)
REPAIR_SETTLE="${REPAIR_SETTLE:-5}"             # 留痕出现后等终态稳定的秒数
SOFTREBOOT_WAIT="${SOFTREBOOT_WAIT:-120}"      # 框架软重启后等新 zygote 出现的上限(秒)
REPAIR_VERIFY_TIMEOUT="${REPAIR_VERIFY_TIMEOUT:-90}"  # 终态（ZN/工行/闩锁）验证轮询上限(秒)
SESSION_WAIT="${SESSION_WAIT:-90}"             # 等提权会话写出最终 verdict 的上限(秒)

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

# ---------- 提权后：ZN 一键修复 + 结果验证 ----------
# 等提权会话收尾：execute() 每轮开头删 .gr_verdict，finally 才重写；
# 文件里重新出现 verdict= 行说明 usvc 已收尾，此时触发修复不会被
# GhostRootService.running 守卫吞掉（v50 修复后 finish() 才会清 running）。
wait_session_finished() {
    local waited=0 v
    while [ "$waited" -lt "$SESSION_WAIT" ]; do
        v=$(a shell 'su -c "grep -c \"^verdict=\" /data/local/tmp/.gr_verdict 2>/dev/null"' 2>/dev/null | tr -d '\r')
        if [ "${v:-0}" -ge 1 ]; then
            log "提权会话已收尾（等待 ${waited}s）"
            return 0
        fi
        sleep "$REPAIR_POLL"; waited=$((waited+REPAIR_POLL))
    done
    warn "提权会话 ${SESSION_WAIT}s 内未写出 verdict（可能仍在收尾），继续尝试修复"
    return 1
}

# 触发走 am start -a ACTION_ADB_REPAIR（GhostRoot >= v51 的对称入口），
# 不用 UI 点击：uiautomator 会被 GKD 抢占（POSTMORTEM 踩坑 #7）。
trigger_repair() {
    local n out
    for n in 1 2 3; do
        if [ "$(adb_state)" != "device" ]; then
            warn "修复触发前 adb 不在线（第 $n 次），5s 后重试"
            sleep 5
            continue
        fi
        # -f 0x14000000 = NEW_TASK|CLEAR_TOP：提权后 MainActivity 仍在前台时，
        # 裸 am start 会复用现有 Activity 而**不重新投递 intent**（实测
        # 2026-11-04：第二次触发没跑修复链、App 日志无新记录）。CLEAR_TOP
        # 强制重建 Activity → onCreate/onNewIntent 必收 action。
        out=$(a shell "am start -a $ADB_REPAIR_ACTION -n $PKG/.MainActivity -f 0x14000000" 2>&1 | tr -d '\r')
        echo "$out" | tee -a "$TEE_LOG" >/dev/null
        if echo "$out" | grep -q "Starting"; then
            log "→ 已触发 ZN 修复（第 $n 次尝试）"
            return 0
        fi
        warn "修复触发未成功（第 $n 次）：$out"
        sleep 5
    done
    return 1
}

# 本 boot 新鲜的修复留痕 → 输出 "ts outcome"；否则返回 1。
# 时钟与 uptime 全部取设备侧，避免 PC/设备时钟偏差；留痕 ts 是 epoch
# （RootUserService 写 System.currentTimeMillis()/1000），新鲜判据
# age ∈ [-5, uptime]，与 App 的 freshThisBoot 同一语义。
repair_mark() {
    local raw ts outcome dev_now dev_up age
    raw=$(a shell 'su -c "cat /data/local/tmp/.gr_zn_repair 2>/dev/null"' 2>/dev/null | tr -d '\r')
    [ -z "$raw" ] && return 1
    ts=$(echo "$raw" | grep -oE 'ts=[0-9]+' | head -1 | cut -d= -f2)
    outcome=$(echo "$raw" | grep -oE 'outcome=[^[:space:]]+' | head -1 | cut -d= -f2)
    [ -z "$ts" ] && return 1
    dev_now=$(a shell 'date +%s' 2>/dev/null | tr -d '\r')
    dev_up=$(a shell 'cut -d" " -f1 /proc/uptime' 2>/dev/null | tr -d '\r')
    [ -z "$dev_now" ] || [ -z "$dev_up" ] && return 1
    age=$((dev_now - ts))
    if [ "$age" -lt -5 ] || [ "$age" -gt "${dev_up%.*}" ]; then
        return 1
    fi
    echo "$ts ${outcome:-unknown}"
}

# zygiskd status 权威判据：双 zygote 注入 + 两个 Zygisk 模块（64/32）已载 + 0 issues
zn_status_ok() {
    local st zygotes issues m64 m32
    st=$(a shell 'su -c "/data/adb/modules/zygisksu/bin/zygiskd status 2>/dev/null"' 2>/dev/null | tr -d '\r')
    [ -z "$st" ] && return 1
    zygotes=$(echo "$st" | grep -oE 'zygote_states:[0-9]+' | head -1 | cut -d: -f2)
    issues=$(echo "$st" | grep -oE 'modules_with_issue:[0-9]+' | head -1 | cut -d: -f2)
    m64=$(echo "$st" | grep '^modules64:' | head -1)
    m32=$(echo "$st" | grep '^modules32:' | head -1)
    ZN_STATUS_SUMMARY="zygote_states=${zygotes:-?} issues=${issues:-?} ${m64:-?} ${m32:-?}"
    [ "${zygotes:-0}" -ge 2 ] || return 1
    [ "${issues:-1}" -eq 0 ] || return 1
    echo "$m64" | grep -q 'hma_oss_zygisk' || return 1
    echo "$m64" | grep -q 'zygisk_vector' || return 1
    echo "$m32" | grep -q 'hma_oss_zygisk' || return 1
    echo "$m32" | grep -q 'zygisk_vector' || return 1
    return 0
}

# 修复副作用：dex2oat-flags 必须不存在（工行兼容）+ .abort_msg 闩锁必须清掉
# （否则下次 daemon 秒退循环复发，POSTMORTEM 问题5）
repair_side_ok() {
    local latch prop
    latch=$(a shell 'su -c "ls /data/adb/zygisksu/.abort_msg 2>/dev/null"' 2>/dev/null | tr -d '\r')
    prop=$(a shell 'su -c "/data/adb/ksu/bin/resetprop dalvik.vm.dex2oat-flags 2>&1"' 2>/dev/null | tr -d '\r')
    REPAIR_SIDE_SUMMARY="abort_latch=${latch:-absent} dex2oat_flags=${prop:-unreadable}"
    [ -z "$latch" ] || return 1
    echo "$prop" | grep -q 'not found' || return 1
    return 0
}

verify_repair_terminal() {
    local waited=0
    while [ "$waited" -lt "$REPAIR_VERIFY_TIMEOUT" ]; do
        if zn_status_ok && repair_side_ok; then
            return 0
        fi
        sleep "$REPAIR_POLL"; waited=$((waited+REPAIR_POLL))
    done
    return 1
}

# 框架软重启（stop/start）的恢复判据：zygote64 pid 变化。
# 只看 adb 在线/boot_completed 会假阳性（stop 期间两者可能都还是旧值）。
wait_framework_recovery() {
    local boot0="$1" z0 z1 waited=0 b
    z0=$(a shell 'pidof zygote64 2>/dev/null' 2>/dev/null | tr -d '\r')
    log "软重启前 zygote64 pid=${z0:-?}，等待新 zygote（上限 ${SOFTREBOOT_WAIT}s）…"
    while [ "$waited" -lt "$SOFTREBOOT_WAIT" ]; do
        if [ "$(adb_state)" = "device" ]; then
            b=$(get_boot_id)
            if [ -n "$b" ] && [ "$b" != "$boot0" ]; then
                warn "等待期间 boot_id 变化($boot0 -> $b)：整机重启而非框架软重启"
                return 1
            fi
            z1=$(a shell 'pidof zygote64 2>/dev/null' 2>/dev/null | tr -d '\r')
            if [ -n "$z1" ] && [ "$z1" != "${z0:-}" ]; then
                log "框架已重生（zygote64 pid ${z0:-?} -> ${z1}，耗时 ${waited}s）"
                return 0
            fi
        fi
        sleep 2; waited=$((waited+2))
    done
    warn "框架软重启后 ${SOFTREBOOT_WAIT}s 内未见新 zygote"
    return 1
}

# 主流程：触发修复 → 等本 boot 留痕 → （必要时软重启复查）→ 终态验证 → 报告
run_repair_and_verify() {
    local boot0="$1" try waited mark="" ts="n/a" outcome="no-mark" recheck_ok=0
    local baseline_ts=0 fresh_mark=0 evidence="terminal-only" new_ts="" pre=""
    log "---- 提权后 ZN 修复 + 结果验证 ----"

    # 本 boot 可能已有留痕（会话自愈成功/用户手点过修复）。记录触发前 ts
    # 作为基线，只认「本次触发产生的新留痕」—— 否则旧留痕会让验证 0s
    # 假通过（实测 2026-11-04：第二次裸 am start 未投递 intent，脚本却因
    # 旧留痕判定通过，App 日志里根本没有第二次修复链）。
    if pre=$(repair_mark); then
        baseline_ts=$(echo "$pre" | awk '{print $1}')
        log "本 boot 已有修复留痕 ts=${baseline_ts}，本次触发需产生更新留痕"
    fi

    # 提权会话收尾前 GhostRootService.running=true 会吞掉修复请求，
    # 先等最终 verdict 落地再发（失败不阻塞，后面还有触发重试兜底）
    wait_session_finished || true

    # 「触发 → 等新留痕」重试：任何一轮被吞/失败都能自愈
    try=1
    while [ "$try" -le "$REPAIR_TRIGGERS" ]; do
        if trigger_repair; then
            waited=0
            while [ "$waited" -lt "$REPAIR_WAIT" ]; do
                if mark=$(repair_mark); then
                    new_ts=$(echo "$mark" | awk '{print $1}')
                    if [ "${new_ts:-0}" -gt "$baseline_ts" ]; then
                        fresh_mark=1
                        evidence="fresh-mark"
                        log "  捕获本次触发的新留痕（第 $try 次触发后 ${waited}s）"
                        break 2
                    fi
                fi
                sleep "$REPAIR_POLL"; waited=$((waited+REPAIR_POLL))
            done
            warn "第 $try 次触发后 ${REPAIR_WAIT}s 内未见新留痕（可能仍被 running 守卫吞掉）"
        else
            warn "第 $try 次修复触发失败"
        fi
        try=$((try+1))
        sleep 5
    done

    if [ -n "$mark" ]; then
        ts=$(echo "$mark" | awk '{print $1}')
        outcome=$(echo "$mark" | awk '{print $2}')
        [ -n "$outcome" ] || outcome="unknown"
        log "修复留痕: ts=$ts outcome=${outcome}（evidence=${evidence}）"
    fi
    if [ "$fresh_mark" -ne 1 ]; then
        warn "本次触发未产生新留痕（App 可能已处于已修复态或服务忙碌）→ 以终态判据为准"
    fi

    # 过程证据分支：softreboot 需等框架恢复后复查；无留痕/异常留痕不直接
    # 判负 —— 终态（ZN status + 工行/闩锁）才是「修复结果」的最终判据
    case "$outcome" in
        zn-ok*)
            :
            ;;
        *softreboot*)
            log "修复安排了框架软重启（~40s），等待恢复后再触发一次复查…"
            if ! wait_framework_recovery "$boot0"; then
                warn "框架软重启未恢复或 boot_id 变化，修复验证失败"
                return 1
            fi
            sleep "$REPAIR_SETTLE"
            # 复查同样要求「新于复查前基线」的 zn-ok 留痕
            if pre=$(repair_mark); then
                baseline_ts=$(echo "$pre" | awk '{print $1}')
            fi
            try=1
            while [ "$try" -le "$REPAIR_TRIGGERS" ]; do
                if trigger_repair; then
                    waited=0
                    while [ "$waited" -lt "$REPAIR_WAIT" ]; do
                        if mark=$(repair_mark); then
                            new_ts=$(echo "$mark" | awk '{print $1}')
                            outcome=$(echo "$mark" | awk '{print $2}')
                            if [ "${new_ts:-0}" -gt "$baseline_ts" ] \
                                    && echo "${outcome:-}" | grep -q '^zn-ok'; then
                                recheck_ok=1
                                evidence="fresh-mark(recheck)"
                                break 2
                            fi
                        fi
                        sleep "$REPAIR_POLL"; waited=$((waited+REPAIR_POLL))
                    done
                fi
                try=$((try+1))
                sleep 5
            done
            if [ "$recheck_ok" -ne 1 ]; then
                warn "软重启后复查未得到新的 zn-ok 留痕（outcome=${outcome:-?}）"
                return 1
            fi
            log "软重启后复查: outcome=$outcome"
            ;;
        *)
            warn "修复留痕缺失或非成功态（outcome=${outcome}），转以终态判据验证"
            ;;
    esac

    sleep "$REPAIR_SETTLE"
    if verify_repair_terminal; then
        log "✓ 修复结果验证通过（evidence=${evidence}）"
        log "  ZN:        ${ZN_STATUS_SUMMARY:-unknown}"
        log "  工行/闩锁: ${REPAIR_SIDE_SUMMARY:-unknown}"
        {
            echo "==== ZN REPAIR VERIFIED at $(date) ===="
            echo "boot_id:     $boot0"
            echo "repair_mark: ts=$ts outcome=$outcome evidence=$evidence"
            echo "zn_status:   ${ZN_STATUS_SUMMARY:-unknown}"
            echo "side_checks: ${REPAIR_SIDE_SUMMARY:-unknown}"
        } | tee -a "$TEE_LOG"
        return 0
    fi
    warn "✗ 修复终态验证失败：ZN=${ZN_STATUS_SUMMARY:-unknown} 工行/闩锁=${REPAIR_SIDE_SUMMARY:-unknown}"
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
REPAIR_RESULT=""
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
        # v51: 提权成功 ≠ 全栈可用 —— ZN 注入/Vector/dex2oat 副作用还要
        # 靠修复链闭环，且必须验证结果（留痕 + status + 工行/闩锁）
        if run_repair_and_verify "$BOOT"; then
            REPAIR_RESULT="OK"
        else
            REPAIR_RESULT="FAIL"
            warn "ZN 修复验证未通过（root 已到手，不回滚；详情见上方日志）"
        fi
        break
    else
        warn "本 boot 未提权"
        # 崩溃已自动重启 → 下一轮直接测新 boot；未崩溃 → 下一轮会强制重启
        a shell 'logcat -d -t 60 -s GhostRoot:V 2>/dev/null' 2>/dev/null | tail -15 | tee -a "$TEE_LOG" >/dev/null
    fi
done

log "===== 循环结束 (rounds=$ROUND, repair=${REPAIR_RESULT:-not-run}) ====="
# 自动化可感知：提权成功但修复未通过 → 非零退出（root 结果日志仍完整保留）
if [ "$REPAIR_RESULT" = "FAIL" ]; then
    exit 2
fi
