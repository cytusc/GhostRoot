#!/system/bin/sh
# revive_module_daemons.sh — v58（GhostRoot 修复链布署）
# 背景纯事实：框架软重启（stop/start）会杀死 root app_process 守护类模块
# 进程（SuToaster 等）；KernelSU 的 boot-completed 模块脚本只在整机开机
# 重跑，纯 stop/start 不触发 —— 守护无人接手，模块自写 "(❌Disabled)"。
# 本脚本等价「再执行一次模块自带的 boot-completed.sh」，只补进程不存在
# 的模块；守护已经活着的直接跳过（幂等）。修复动作全部来自模块作者
# 自己的启动脚本 —— GhostRoot 不改任何模块内部的配置或逻辑。
REVIVED=0

# --- ksuGrantToast（SuToaster）---
if pidof SuToaster >/dev/null 2>&1; then
    echo "ksuGrantToast: SuToaster alive (pid=$(pidof SuToaster))"
else
    if [ -f /data/adb/modules/ksuGrantToast/boot-completed.sh ]; then
        echo "ksuGrantToast: SuToaster dead -> running module boot-completed.sh"
        ( cd /data/adb/modules/ksuGrantToast && \
          KSU_MODULE=ksuGrantToast setsid sh ./boot-completed.sh ) \
          >/dev/null 2>&1 </dev/null &
        REVIVED=1
        sleep 4
        if pidof SuToaster >/dev/null 2>&1; then
            echo "ksuGrantToast: SuToaster revived (pid=$(pidof SuToaster))"
        else
            echo "ksuGrantToast: SuToaster still missing (module script may have failed)"
        fi
    else
        echo "ksuGrantToast: module missing, skip"
    fi
fi

echo "revived=$REVIVED"
