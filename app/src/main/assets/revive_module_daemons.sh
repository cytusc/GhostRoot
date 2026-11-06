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

# --- ksuGrantToast watchdog（v64 新增）---
# 看门狗守护循环（service.d 脚本 detach 出来的 --daemon）与 SuToaster 一样
# 会被框架软重启杀死，且 service.d 不随软重启重跑 —— watchdog 死后
# SuToaster 再自发死亡（原生拿不到 sulog fd 直接退出，watchdog 日志实测
# 2026-11-05 22:12 后无任何恢复记录）就无人接手，模块 description 停在 ❌。
# service.d 脚本自带 pidfile 单实例守卫，重复拉起安全。
if pgrep -f 'ksuGrantToast-watchdog' >/dev/null 2>&1; then
    echo "ksuGrantToast: watchdog alive"
else
    if [ -f /data/adb/service.d/99-ksuGrantToast-watchdog.sh ]; then
        echo "ksuGrantToast: watchdog dead -> relaunch service.d script"
        setsid sh /data/adb/service.d/99-ksuGrantToast-watchdog.sh \
          >/dev/null 2>&1 </dev/null &
        REVIVED=1
        sleep 2
        if pgrep -f 'ksuGrantToast-watchdog' >/dev/null 2>&1; then
            echo "ksuGrantToast: watchdog relaunched"
        else
            echo "ksuGrantToast: watchdog relaunch failed"
        fi
    else
        echo "ksuGrantToast: watchdog script missing, skip"
    fi
fi

# --- zygisk_vector（安全治理：保工行属性清理与模块脚本自愈）---
# 参考 GitHub JingMatrix/Vector #878：软重启下 vectord 重复由上游 R8 混淆
# strip 锁句柄导致，已在上游 PR #1049 修复。
# 在系统运行时强杀 vectord 会导致活跃 Zygote/System Server 抛出 DEAD_OBJECT
# 进而发生死锁或崩溃；因此本脚本绝不主动强杀或异步重拉 vectord。
# 此处：1) 若之前写入过单实例守卫补丁，自动剔除以还原官方原生 service.sh
#       2) 幂等清除 dalvik.vm.dex2oat-flags（保工行）
if [ -d /data/adb/modules/zygisk_vector ]; then
    # 1. 还原 service.sh 原版，清除 GhostRoot 曾注入的单实例补丁
    if [ -f /data/adb/modules/zygisk_vector/service.sh ]; then
        if grep -q "GhostRoot: single-instance guard" /data/adb/modules/zygisk_vector/service.sh 2>/dev/null; then
            sed -i '/GhostRoot: single-instance guard/d' /data/adb/modules/zygisk_vector/service.sh 2>/dev/null
            sed -i '/\[v\]ectord/d' /data/adb/modules/zygisk_vector/service.sh 2>/dev/null
            echo "Vector: service.sh restored to original upstream version"
        fi
    fi

    # 2. 幂等清除 dex2oat-flags（保工行）
    /data/adb/ksu/bin/resetprop --delete dalvik.vm.dex2oat-flags 2>/dev/null
fi

echo "revived=$REVIVED"
