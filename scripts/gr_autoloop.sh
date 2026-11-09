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
#        · 提权会话自安排的软重启（verdict zn= / .gr_zn_softreboot）先行等待恢复
#      验证失败不回滚 root（提权结果已保留），但脚本以退出码 2 告知自动化
#
# 关键正确性（针对之前测试的几处坑）：
#   - adb reboot 后**必须等真正掉线**再等上线，避免打在旧 boot 上（竞态）
#   - boot_id 用**全文比对**，不依赖 .fired 文件（可能跨 boot 残留 / 死亡轮次误写）
#   - 提权判定只用 `su -c id` 真值；验证窗口内若 boot_id 变化=崩溃重启，判负
#   - 内核不匹配（uname -r 与预期不符）直接 refuse-to-fire，避免白打浪费 boot
#   - Shizuku server 存活只认 shizuku_server 进程（管理器 App 进程名含
#     shizuku，ps grep 全字匹配是假阳性；软重启后 binder 必死须重启 server）
#
# 用法:
#   bash gr_autoloop.sh                 # 默认循环（MAX_ROUNDS=300）
#   MAX_ROUNDS=0 bash gr_autoloop.sh    # 无限循环直到拿到 root
#   VERIFY_TIMEOUT=120 SHIZUKU_WAIT=12 bash gr_autoloop.sh
#   REPAIR_WAIT=60 SOFTREBOOT_WAIT=150 bash gr_autoloop.sh
#
# 退出码: 0=提权(+修复验证)完成或正常结束；2=提权成功但修复验证未通过
# 依赖: GhostRoot >= v57（ADB_REPAIR 入口 + Shizuku 有界等待；低版本 am start
#       会静默忽略 ACTION_ADB_REPAIR，且软重启后修复请求会被 App 静默吞掉）
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
# v60: App 端 znRepair 同 boot 软重启冷却 20min → 2min（.gr_zn_softreboot
# mtime）+ 25s 余量；冷却期内触发被 App 链路吞掉（zero 留痕），复查必须等
# 它过期。20min 旧值实证 2026-11-05 13:32 三次触发全灭、白等 20 分钟。
ZN_COOLDOWN_MARGIN="${ZN_COOLDOWN_MARGIN:-145}"
RECHECK_ATTEMPTS="${RECHECK_ATTEMPTS:-4}"      # 冷却感知复查引擎的最多尝试次数

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

# v58: server 存活判据收窄 —— 只认 adb 启动的 shizuku_server 进程。
# 之前 `ps -A | grep -i shizuku` 会把管理器 App 进程（moe.shizuku.privileged.api、
# 进程名含 shizuku）也当在线 —— 实测 2026-11-05 10:52：框架软重启杀掉 server
# 后，脚本因假阳性"已在线（幂等跳过）"跳过重启，App 端 pingBinder 永远失败。
shizuku_server_alive() {
    local p
    p=$(a shell 'pidof shizuku_server' 2>/dev/null | tr -d '\r')
    echo "${p:-}"
}

start_shizuku() {
    # $1 = "force"：先 kill 旧 server 再重启。修复相位入口/软重启恢复后
    # 必须用 force —— 软重启/exploit reset 后旧进程名可能残存或管理器 App
    # 兜底存活，ps 级判据无法证明 binder 可用，强拉新实例是唯一可靠路径。
    local force="${1:-}" bin srvpid
    bin=$(find_shizuku_bin)
    if [ -z "$bin" ]; then
        warn "找不到 Shizuku 库路径（$SHIZUKU_PKG 未安装？）"
        return 1
    fi
    # 幂等快路径（仅非 force）：server 专有 pidof 在线就不重启
    srvpid=$(shizuku_server_alive)
    if [ -z "$force" ] && [ -n "$srvpid" ]; then
        log "  Shizuku server 已在线（pid=${srvpid}，幂等跳过）"
        return 0
    fi
    if [ -n "$force" ] && [ -n "$srvpid" ]; then
        log "→ 强制重启 Shizuku server（旧 pid=${srvpid}）：软重启/reset 后 binder 不可信"
        a shell "kill $srvpid 2>/dev/null" 2>/dev/null
        sleep 1
    fi
    log "→ 启动 Shizuku: $bin"
    # 后台启动，避免 adb shell 被常驻 server 阻塞
    a shell "nohup '$bin' >/dev/null 2>&1 &" 2>/dev/null
    sleep "$SHIZUKU_WAIT"
    srvpid=$(shizuku_server_alive)
    if [ -n "$srvpid" ]; then
        log "  Shizuku server 已存在（pid=${srvpid}）"
    else
        warn "  未检测到 shizuku_server 进程（可能启动失败，本回合 GhostRoot 可能无法绑定时权）"
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
        # 判据收紧（2026-11-04 17:26 教训）：framework stop 窗口内 am start
        # 会先打 "Starting: Intent{...}" 再抛 IllegalStateException("Too early
        # to start activity")，只看到 Starting 就算成功会把 3 次重试额度
        # 全部烧在不投递的调用上。必须「有 Starting 且无失败特征」。
        if echo "$out" | grep -q "Starting" \
                && ! echo "$out" | grep -qE "Exception|Can't find service|Failure|Error:"; then
            log "→ 已触发 ZN 修复（第 $n 次尝试）"
            return 0
        fi
        warn "修复触发未真正投递（第 $n 次），输出见上方，5s 后重试"
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

# ---- v57: 提权会话自行安排的框架软重启 · 信号消费（只读，纯调度） ----
# 提权链收尾时 zygiskAutoActivate（RootUserService，late-load 自愈）会安排
# `sleep 6; stop; sleep 4; start` 的框架软重启，并写两处留痕：
#   1) /data/local/tmp/.gr_zn_softreboot（ts=... zygotes=...）
#   2) .gr_verdict 的 zn=zn-softreboot-scheduled 行
# 修复相位若不消费这个信号，触发会直接撞进 stop/start 窗口（2026-11-04
# 17:26 实测：三次 am start 全灭，其中一次带 IllegalStateException 但
# 脚本误判成功），随后框架恢复但 Shizuku server 已被软重启杀死、App 端
# onRepairClicked 在 pingBinder 处静默退出 —— 整轮修复零执行。
# 这里只做检测与等待恢复，不做任何修复动作，仍属纯调度。

# .gr_zn_softreboot 本 boot 新鲜 → 输出 ts，返回 0
softreboot_mark_fresh() {
    local raw ts dev_now dev_up age
    raw=$(a shell 'su -c "cat /data/local/tmp/.gr_zn_softreboot 2>/dev/null"' 2>/dev/null | tr -d '\r')
    [ -z "$raw" ] && return 1
    ts=$(echo "$raw" | grep -oE 'ts=[0-9]+' | head -1 | cut -d= -f2)
    [ -z "$ts" ] && return 1
    dev_now=$(a shell 'date +%s' 2>/dev/null | tr -d '\r')
    dev_up=$(a shell 'cut -d" " -f1 /proc/uptime' 2>/dev/null | tr -d '\r')
    [ -z "$dev_now" ] || [ -z "$dev_up" ] && return 1
    # v58: 负向由 -5 放宽到 -60 —— 2026-11-05 10:52 实测：phase 入口读到
    # verdict 时其 ts 可"在未来"约 9 设备秒（ts 与文件对 wait_session_finished
    # 可见存在写序/时钟差），[-5] 窗口曾误拒本 boot 合法信号。跨 boot 旧
    # 信号仍由 age > uptime 兜底，负向放宽无越界影响。
    # v58: 正向窗口收紧到 120s —— 调度后 ~15s 内即执行（sleep 6; stop;
    # sleep 4; start），phase 入口又是紧跟 wait_session_finished 之后，
    # 当下读到的合法信号 age 必然很小。窗口放宽到 uptime 的旧语义会
    # 把「早前已执行完的软重启」（age 可达 20min，2026-11-05 11:11 实证）
    # 也当待办 → 白等一轮。负向 -60 保持（ts 落盘前的短暂先见容差）。
    age=$((dev_now - ts))
    if [ "$age" -lt -60 ] || [ "$age" -gt 120 ]; then
        return 1
    fi
    echo "$ts"
    return 0
}

# .gr_verdict 的 zn= 行（v52 起回写）含 softreboot 且 verdict 本 boot 新鲜
verdict_zn_fresh_softreboot() {
    local raw ts zn dev_now dev_up age
    raw=$(a shell 'su -c "cat /data/local/tmp/.gr_verdict 2>/dev/null"' 2>/dev/null | tr -d '\r')
    [ -z "$raw" ] && return 1
    ts=$(echo "$raw" | grep -oE '^ts=[0-9]+' | head -1 | cut -d= -f2)
    zn=$(echo "$raw" | grep -oE '^zn=[^[:space:]]+' | head -1 | cut -d= -f2)
    [ -z "$ts" ] || [ -z "$zn" ] && return 1
    # v58: 必须是「已安排」语义。之前用 'softreboot' 子串匹配，把
    # zn-zygote0-softreboot-cooldown(18m)（明确表示本轮不再安排，等冷却）
    # 也当成了「已调度」—— 实证 2026-11-05 11:11：白等 120s（不会来新
    # zygote）。cooldown 值来自会话收尾 zn 字段，只有 exact match 才可信。
    [ "$zn" = "zn-softreboot-scheduled" ] || return 1
    dev_now=$(a shell 'date +%s' 2>/dev/null | tr -d '\r')
    dev_up=$(a shell 'cut -d" " -f1 /proc/uptime' 2>/dev/null | tr -d '\r')
    [ -z "$dev_now" ] || [ -z "$dev_up" ] && return 1
    # v58: 与 softreboot_mark_fresh 同语义负向放宽（见其注释）
    age=$((dev_now - ts))
    if [ "$age" -lt -60 ] || [ "$age" -gt "${dev_up%.*}" ]; then
        return 1
    fi
    return 0
}

# 本 boot 是否「软重启待办」： zn= 信号（znRepair 收尾会 sed 把
# zn= 改写为 zn-ok*，此时软重启已不再待办，不应再等一轮）
softreboot_scheduled_boot() {
    local pre out
    if pre=$(repair_mark); then
        out=$(echo "$pre" | awk '{print $2}')
        echo "$out" | grep -q '^zn-ok' && return 1
    fi
    if softreboot_mark_fresh >/dev/null 2>&1; then
        return 0
    fi
    verdict_zn_fresh_softreboot
}

# zygiskd status 权威判据：双 zygote 注入 + 两个 Zygisk 模块（64/32）已载 + 0 issues
zn_status_ok() {
    local st zygotes issues m64 m32 znpid
    st=$(a shell 'su -c "/data/adb/modules/zygisksu/bin/zygiskd status 2>/dev/null"' 2>/dev/null | tr -d '\r')
    # v57: status 不可读 ≠ 状态不通过，两种死因分开报告 —— zn-daemon
    # （zygiskd 的进程名，按 "zygiskd" grep 永远查不到）死了 vs 活着但
    # 没接驾新 zygote，后续决策完全不同。
    if [ -z "$st" ]; then
        znpid=$(a shell 'su -c "pidof zn-daemon 2>/dev/null"' 2>/dev/null | tr -d '\r')
        ZN_STATUS_SUMMARY="zygiskd-status=unreachable zn-daemon=${znpid:-absent}"
        return 1
    fi
    zygotes=$(echo "$st" | grep -oE 'zygote_states:[0-9]+' | head -1 | cut -d: -f2)
    issues=$(echo "$st" | grep -oE 'modules_with_issue:[0-9]+' | head -1 | cut -d: -f2)
    m64=$(echo "$st" | grep '^modules64:' | head -1)
    m32=$(echo "$st" | grep '^modules32:' | head -1)
    ZN_STATUS_SUMMARY="zygote_states=${zygotes:-?} issues=${issues:-?} ${m64:-?} ${m32:-?}"
    [ "${zygotes:-0}" -ge 2 ] || return 1
    [ "${issues:-1}" -eq 0 ] || return 1
    echo "$m64" | grep -q 'hma_oss_zygisk' || return 1
    echo "$m64" | grep -q 'zygisk_lsposed' || return 1
    echo "$m32" | grep -q 'hma_oss_zygisk' || return 1
    echo "$m32" | grep -q 'zygisk_lsposed' || return 1
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
    local waited=0 zn_ok=1 side_ok=1
    while [ "$waited" -lt "$REPAIR_VERIFY_TIMEOUT" ]; do
        # v57: 两侧判据无条件各采一次 —— 原来的 `zn_status_ok &&
        # repair_side_ok` 短路会在 ZN 不通时跳过工行/闩锁检测，
        # 失败日志里只剩一半诊断信息（2026-11-04 17:30 "工行/闩锁=unknown"
        # 其实是根本没采）。
        zn_ok=1; side_ok=1
        zn_status_ok || zn_ok=0
        repair_side_ok || side_ok=0
        if [ "$zn_ok" -eq 1 ] && [ "$side_ok" -eq 1 ]; then
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

# ---------- 修复触发（纯手机端行为） ----------
# 修复链完全由手机端 GhostRoot 执行（RootUserService.znRepair，经 Shizuku
# usvc 调用）。脚本侧只做三件事：
#   1) 启动 Shizuku（adb 启动 server，用户已授权的场景内允许；修复相位
#      入口强制重拉、每轮触发前幂等确保 —— 软重启/exploit reset 会杀掉
#      adb 启动的 server）
#   2) am start 投递 ACTION_ADB_REPAIR intent 触发修复
#   3) 消费提权会话的软重启调度信号（.gr_zn_softreboot / verdict zn=
#      行）并等待框架恢复 —— 只读检测 + 等待，不产生修复动作
# 不做任何脚本侧修复动作：不清闩锁、不拉 daemon、不动 dex2oat-flags、
# 不调度软重启、不写留痕、不回写 verdict —— 与用户在手机上点「修复」
# 按钮完全同语义（等待与重启 server 属调度，不属修复动作）。
# ---------- ZN 冷却感知复查引擎（v58，纯调度） ----------
# App 端 znRepair → zygiskAutoActivate 的软重启调度在同一 boot 有 20 分钟
# 冷却（.gr_zn_softreboot mtime，RootUserService ZN_SOFTREBOOT_COOLDOWN_MIN
# =20）。冷却期内再点修复：链路内部走 cooldown 分支直接返回、不安排重启、
# 不写留痕（实证 2026-11-05 11:15~11:17 三次触发零留痕，全被吃掉）。因此
# 「几分钟内连点」永远不可能成功，必须等冷却过期。本函数：
#   1) 若当下就有新于 baseline_ts 的 zn-ok 留痕 → 直接成功
#   2) 否则等冷却过期（.gr_zn_softreboot mtime + ZN_COOLDOWN_MARGIN s）
#   3) 触发（前先 force 重拉 Shizuku）→ 等留痕：
#      · zn-ok*           → 成功
#      · *softreboot-scheduled*（链路又安排了一轮重启）→ 等框架恢复，
#        更新 baseline，回到 1) 复查（消耗一次尝试额度）
#      · 其他（cooldown/无留痕）→ 回到 2) 继续等
# 最多 RECHECK_ATTEMPTS 次尝试；期间持续监控 boot_id。
# 复用调用方的 boot_lost / baseline_ts，直接写回调用方的 outcome/mark。
recheck_until_znok() {
    local attempt=0 cd_ts now age due slept waited new_ts
    while [ "$attempt" -lt "$RECHECK_ATTEMPTS" ]; do
        attempt=$((attempt+1))
        # 1) 现成 zn-ok
        if mark=$(repair_mark); then
            new_ts=$(echo "$mark" | awk '{print $1}')
            outcome=$(echo "$mark" | awk '{print $2}')
            if [ "${new_ts:-0}" -gt "$baseline_ts" ] && echo "${outcome:-}" | grep -q '^zn-ok'; then
                log "复查: 已有新留痕 zn-ok（ts=${new_ts}）"
                return 0
            fi
        fi
        if boot_lost; then
            die "复查期间 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
        fi
        # 2) 冷却等待
        cd_ts=$(a shell 'su -c "stat -c %Y /data/local/tmp/.gr_zn_softreboot 2>/dev/null"' 2>/dev/null | tr -d '\r')
        now=$(a shell 'date +%s' 2>/dev/null | tr -d '\r')
        due=0
        if [ -n "$cd_ts" ] && [ -n "$now" ]; then
            age=$((now - cd_ts))
            [ "$age" -lt "$ZN_COOLDOWN_MARGIN" ] && due=$((ZN_COOLDOWN_MARGIN - age))
        fi
        if [ "$due" -gt 0 ]; then
            log "ZN 软重启冷却未过期（App 端拒绝再安排），等 ${due}s 后触发复查（第 $attempt 次尝试）"
            slept=0
            while [ "$slept" -lt "$due" ]; do
                sleep 15; slept=$((slept+15))
                if boot_lost; then
                    die "等冷却期间 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
                fi
            done
        fi
        if boot_lost; then
            die "复查触发前 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
        fi
        # 3) 触发 + 等留痕（软重启可能杀死 server，force 重拉）
        start_shizuku force || warn "Shizuku 启动失败，复查触发可能到不了 usvc"
        trigger_repair
        waited=0
        while [ "$waited" -lt "$REPAIR_WAIT" ]; do
            if boot_lost; then
                die "复查等留痕期间 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
            fi
            if mark=$(repair_mark); then
                new_ts=$(echo "$mark" | awk '{print $1}')
                outcome=$(echo "$mark" | awk '{print $2}')
                if [ "${new_ts:-0}" -gt "$baseline_ts" ]; then
                    if echo "${outcome:-}" | grep -q '^zn-ok'; then
                        log "复查成功: outcome=${outcome}（ts=${new_ts}，第 $attempt 次尝试）"
                        return 0
                    fi
                    if echo "${outcome:-}" | grep -q 'softreboot-scheduled'; then
                        # 链路又安排了一轮软重启：更新基线，等框架恢复后复查
                        baseline_ts=$new_ts
                        log "复查触发又安排了软重启（ts=${new_ts}），等框架恢复…"
                        if ! wait_framework_recovery "$boot0"; then
                            warn "等待框架重启恢复失败/超时（第 $attempt 次尝试）"
                        else
                            sleep "$REPAIR_SETTLE"
                        fi
                        break   # 回到外层消耗下一次尝试额度
                    fi
                fi
            fi
            sleep "$REPAIR_POLL"; waited=$((waited+REPAIR_POLL))
        done
        warn "第 $attempt 次复查尝试未得 zn-ok（当前 outcome=${outcome:-none}）"
    done
    return 1
}

run_repair_and_verify() {
    local boot0="$1" try waited mark="" ts="n/a" outcome="no-mark" recheck_ok=0
    local baseline_ts=0 fresh_mark=0 evidence="terminal-only" new_ts="" pre=""
    # 提权 boot 丢失检测：LKM root 不跨重启，崩溃重启后 su 必消失，
    # 继续触发修复只会空转（实测 2026-11-04 13:36：落地 14s 后崩溃重启，
    # 脚本在新 boot 上又盲试了 3 次修复，全被 znRepair root 门禁拒绝）
    # 注意：boot_id 读到空串 ≠ boot 丢失 —— adb 瞬断/框架软重启期间
    # shell 可能短暂失败（实测 13:46 误报）。空值视为 unknown，重试；
    # 只有读到「非空且 != boot0」才判定丢失。
    boot_lost() {
        local v n
        for n in 1 2 3; do
            v=$(get_boot_id)
            if [ -n "$v" ]; then
                [ "$v" != "$boot0" ] && return 0
                return 1
            fi
            sleep 2
        done
        return 1   # 连续空读：unknown，按未丢失处理（宁可多试不误杀）
    }
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
    if boot_lost; then
        die "提权后等待会话收尾期间 boot_id 变化（崩溃重启），root 已随重启丢失，修复无从谈起"
    fi

    # v57: 提权会话收尾时可能已自行安排框架软重启（late-load 自愈信号，
    # 见 softreboot_scheduled_boot）。这里只消费调度信号并等待恢复——
    # 检测/等待属纯调度，修复链 100% 仍由手机端 GhostRoot 执行。不等就
    # 触发的实证：2026-11-04 17:26 三次 am start 全灭（其中一次打印
    # "Too early to start activity" 却被旧判据记为触发成功）。
    if softreboot_scheduled_boot >/dev/null 2>&1; then
        log "检测到本 boot 已调度框架软重启（提权会话 late-load 自愈），先等框架恢复再触发修复"
        if ! wait_framework_recovery "$boot0"; then
            # v57: 保守降级不 die —— 超时 ≠ 整机重启实证：信号可能来自一次
            # 早已完成的软重启（上次 autoloop 中断后重跑），此时没有任何
            # 新 zygote 会出现。继续走触发流程：boot_id 是否真的变了由
            # 下方/循环内的 boot_lost 检查定向裁决，修复结果仍由终态判据
            # 说了算（宁可多试不误杀，与 boot_lost 空值语义一致）。
            warn "等待窗口内未见新 zygote（信号可能来自已完成的软重启），继续触发流程"
        else
            sleep "$REPAIR_SETTLE"
        fi
    fi

    # v58: 修复相位入口强制重启 server —— 提权会话收尾（uapi 换装/ZN 自愈
    # 的软重启调度/exploit reset）都可能重创 adb 启动的 server；此时不再
    # 信任 ps 级存活，直接 kill + relaunch 拿新 binder 实例。授权白名单由
    # Shizuku 服务侧持久化（重启 server 无需重新授权，2026-11-05 实证：
    # 提权相位连续多轮 am start 触发均未要求重新授权）。
    if boot_lost; then
        die "修复前 boot_id 变化（崩溃重启），root 已丢失，修复无从谈起"
    fi
    log "确保 Shizuku server 在线（修复相位强制重拉）…"
    start_shizuku force || warn "Shizuku 启动失败，修复触发可能到不了 usvc"
    # 「触发 → 等新留痕」重试：任何一轮被吞/失败都能自愈
    try=1
    while [ "$try" -le "$REPAIR_TRIGGERS" ]; do
        if boot_lost; then
            die "修复触发期间 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
        fi
        # v57: 每轮触发前幂等确保 Shizuku 在线（v58 起判据为 server 专有
        # pidof）—— 软重启杀死的 server 不能只靠相位开头那一次拉起兜住
        # （2026-11-04 17:27/17:28 两次触发到达 App 但 pingBinder 失败）
        start_shizuku || warn "Shizuku 启动失败，修复触发可能到不了 usvc"
        if trigger_repair; then
            waited=0
            while [ "$waited" -lt "$REPAIR_WAIT" ]; do
                if boot_lost; then
                    die "等留痕期间 boot_id 变化（崩溃重启），root 已丢失，停止修复验证"
                fi
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
            log "修复安排了框架软重启（~40s），等待恢复后再触发复查…"
            if ! wait_framework_recovery "$boot0"; then
                warn "framework 软重启未恢复或 boot_id 变化，修复验证失败"
                return 1
            fi
            sleep "$REPAIR_SETTLE"
            # 复查同样要求「新于复查前基线」的 zn-ok 留痕（v58 起交给
            # recheck_until_znok 统一引擎：等冷却、软重启处置、重试全包）
            if pre=$(repair_mark); then
                baseline_ts=$(echo "$pre" | awk '{print $1}')
            fi
            if ! recheck_until_znok; then
                warn "软重启后复查未得到新的 zn-ok 留痕（outcome=${outcome:-?}）"
                return 1
            fi
            evidence="fresh-mark(recheck)"
            log "软重启后复查: outcome=$outcome"
            ;;
        *)
            warn "修复留痕缺失或非成功态（outcome=${outcome}），转以终态判据验证"
            ;;
    esac

    sleep "$REPAIR_SETTLE"
    if boot_lost; then
        die "终态验证前 boot_id 变化（崩溃重启），root 已丢失，修复结果作废"
    fi
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

# ---------- 落地后守卫：ksud UAPI（只读检测，不做任何替换/注入） ----------
# 2026-11-03 教训：落地后 /data/adb/ksud 可能是 uapi2 —— 后果：ksud module
# install 报 UAPI mismatch、vivo_net_fix 的 sepolicy 规则静默不注入、bszip
# 安装器内刷三件套全挂。脚本侧只检测并告警，替换动作（如需要）留给手机端/
# 用户手动处理 —— 保持「完全模拟手机端行为」的约束。
uapi_guard() {
    local u
    u=$(ksud_uapi)
    if [ "$u" = "$EXPECTED_UAPI" ]; then
        log "uapi guard: /data/adb/ksud uapi$u ok"
        return 0
    fi
    warn "uapi guard: ksud uapi${u:-?} != 期望 uapi${EXPECTED_UAPI}（请在手机端处理，脚本不代劳替换）"
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
    log "shizuku_server: $(shizuku_server_alive || echo absent)"
    log "zn-daemon: $(a shell 'su -c "pidof zn-daemon 2>/dev/null"' 2>/dev/null | tr -d '\r' | head -1)"
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
        # v51: 提权成功 ≠ 全栈可用 —— ZN 注入/Lsposed/dex2oat 副作用还要
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
