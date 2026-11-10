#!/system/bin/sh
# v81: 框架软重启后的只读终态复查 + zn-ok 留痕回写。
#
# 背景：znRepair 安排软重启后立刻返回 zn-softreboot-scheduled，App 侧
# 没有任何路径会在框架恢复后重读 zygiskd status 写 zn-ok —— autoloop
# 的 recheck_until_znok 只认 ^zn-ok 留痕，于是每 ~170s 再触发一轮修复，
# 而每轮修复又先杀 daemon 起新的，把刚建立的注入状态判成 0（v80 自毒
# 死循环）。本脚本补上这个缺口。
#
# 由 zygiskAutoActivate 用 setsid 挂在 root 域：框架 stop/start 会杀掉
# App 进程，但 root 域任务不受 AM 管理，仍能存活并完成复查。
# 只做只读 status 采样 + 留痕/verdict 回写，绝不改动 ZN 运行状态。
# 判据与 scripts/gr_autoloop.sh 的 zn_status_ok() 完全同构，保证 App
# 写下的 zn-ok 与脚本终态验证用同一把尺子。

ZN_BIN=/data/adb/modules/zygisksu/bin/zygiskd
MARK=/data/local/tmp/.gr_zn_repair
VERDICT=/data/local/tmp/.gr_verdict

i=0
while [ "$i" -lt 12 ]; do
    st=$("$ZN_BIN" status 2>/dev/null)
    z=$(printf '%s\n' "$st" | grep -oE 'zygote_states:[0-9]+' | head -1 | cut -d: -f2)
    issues=$(printf '%s\n' "$st" | grep -oE 'modules_with_issue:[0-9]+' | head -1 | cut -d: -f2)
    m64=$(printf '%s\n' "$st" | grep '^modules64:' | head -1)
    m32=$(printf '%s\n' "$st" | grep '^modules32:' | head -1)

    if [ "${z:-0}" -ge 2 ] && [ "${issues:-1}" -eq 0 ] \
            && printf '%s' "$m64" | grep -q 'hma_oss_zygisk' \
            && printf '%s' "$m64" | grep -q 'zygisk_lsposed' \
            && printf '%s' "$m32" | grep -q 'hma_oss_zygisk' \
            && printf '%s' "$m32" | grep -q 'zygisk_lsposed'; then
        ts=$(date +%s)
        up=$(cut -d' ' -f1 /proc/uptime)
        printf 'ts=%s uptime=%ss outcome=zn-ok-zygote%s\n' "$ts" "$up" "$z" > "$MARK"
        chmod 666 "$MARK" 2>/dev/null
        if [ -f "$VERDICT" ]; then
            sed -i "s/^zn=.*/zn=zn-ok-zygote$z/" "$VERDICT" 2>/dev/null
            chmod 666 "$VERDICT" 2>/dev/null
        fi
        exit 0
    fi
    sleep 5
    i=$((i + 1))
done
exit 0
