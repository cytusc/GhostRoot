# GhostRoot APK 工程

手机端一键提权 App，通过 **Shizuku UserService** 拿到 adb shell 等价权限，
在手机内直接跑 exploit 链（不依赖 PC）。

## 版本

```
versionCode  81
versionName  2.65
UserService  47       ← 改 RootUserService 逻辑必须同步 +1
```

### v81（2026-11-10，ZN 修复自毒死循环根治）

实机取证：设备 `zygiskd status` 已 `zygote_states:2 / inject_state:1 / modules_with_issue:0`（ZN 其实修好了），但 `.gr_zn_repair` 永远停在 `zn-softreboot-scheduled`，autoloop 每 ~170s 重排一次软重启。app 日志逐轮 `zygote_states=2 zn-daemon=7` → `single-instance daemon -> zn-daemon=8 zygote_states=0`。

| 根因（v80 引入） | 修正 |
|---|---|
| `rootShell()` = `su -c "<SH_ENV 赋值串> <cmd>"`，cmd 以 `for ...` 开头触发 `/system/bin/sh: syntax error: unexpected 'do'`，kill 收敛命令从未执行，daemon 每轮 +1 | 改单条简单命令 `kill -9 $(pidof zn-daemon)` |
| 收敛后重读「新建 daemon」的 status 覆盖判据，把真值 2 换成 0（新 daemon 未接驾 zygote） | 删除重读覆盖，zygotes 沿用收敛前只读值 |
| 成功判据 `zygotes>0 && daemons==1` 叠加失效的 kill → 恒 false，zn-ok 成死代码 | 成功只由**前置只读终态**判定，且排在所有 kill / 软重启之前 |
| 软重启后无任何路径重读 status 写 `zn-ok`；`scheduled` 被当成功形态；只有 2min 冷却无次数上限 | 新增 `assets/zn_verify_after_softreboot.sh`（root 域 setsid，+45s 起只读复查回写 zn-ok）；`scheduled` 降级 pending（exit=5）；同 boot 软重启上限 2 次 |
| App 与脚本判据不一致 | `znInjectionVerified()` 与脚本 `zn_status_ok()` 同构：`zygote_states>=2` + `issues=0` + 64/32 位各载两模块 |

配套：`gr_autoloop.sh` 冷却轮询期间消费自动回写的 zn-ok，不再白等 129s 触发新一轮。版本三齐 `81 / 2.65 / 47`。

### v76（2026-11-09，uapi5 签发者与 UI 证据链修正）

| 修复项 | 位置 | 备注 |
|---|---|---|
| `uapiGuard()` 加 ksud-gk MD5 门禁（只认 `2432bd63…a0c5a`，不符拒绝替换） | `RootUserService.java` | 防坏 ksud 刷入后续模块链与 sepolicy 静默失效；uapi 已正确则不影响主流程 |
| 软重启按 uapi 选签发者：`/data/adb/ksud` 为 uapi2 时改用 `ksud-gk`（uapi5）发 `soft-reboot` | `RootUserService.java` | uapi2 发软重启会静默失败（模块/sepolicy 注入跳过）；回落逻辑保留原路径，属尽力而为分支 |
| `MainActivity.onResume()` 先 `showInterruptedBanner()` 再 `attach()` 回放结论 | `MainActivity.java` | `attach()` 会置 `lastDoneConsumed=true`，先回放会覆盖横幅 |
| 版本三齐 `76 / 2.60 / 44`（`GhostRootService` 与 `MainActivity` 两处 `version(44)`） | 构建配置 + Java | 强制新 UserService，不复用旧进程 |

### v78（2026-11-10，实机 373 会话分析 —— 修正 v77 的两处严重误判）

对 v77 安装后实机日志（`ghostroot-run.log`，373 个 exploit 会话）做了逐会话切分分析，发现并修正：

**实机失败分布（373 会话）**：

| 终点阶段 | 次数 | 占比 | 判读 |
|---|---|---|---|
| `route_done status=0`（成功路由） | 162 | 43% | 与 v76 基线 43.7% 持平 |
| spray 完成后**静默 exit=1**（route 入口前，无任何错误输出） | 159 | 42% | 本征随机失败，失败快（p50 3.6s），多轮重试是唯一杠杆 |
| CMP 后无 route_done（卡死） | 28 | 7% | v77 目标场景，实际占比远小于预估 |
| 碰撞筛选/payload 阶段失败 | 21 | 6% | — |

**v77 的两处修正**：

| 修正项 | 原因 | v78 改法 |
|---|---|---|
| fire.sh 死锁窗口 10s → **60s**（100→600 迭代） | 成功会话 exploit start→route_done p50=3.5s / p90=9.4s / **max=16.1s**，落地（enforce=0）中位 9-13s、最长 35s+。10s 窗口会把近半数成功会话在落地前一刻杀掉 —— 典型"越改成功率越低"回归。60s 仍早于实测 ~60-90s watchdog 崩溃重启 | `scripts/device-artifacts/fire.sh` v10，已 adb push 到设备 |
| `.fire.log` ROUTE_DEADLOCK 判定 `contains()` → **增量计数** | `.fire.log` 跨 boot/跨轮累积，历史残留标记会让新一轮第 1 轮就误判 break，整轮循环报废 | `RootUserService.countIn()` 快照轮前计数，仅本轮新增才 break |
| `profile-tuned-reference.conf` 修改回滚 | ghostlock CLI 只接受 `--load-prebuilt-profile/--force-attack/--enable-status-record`，**无任何执行参数入口**，conf 改动无生效路径（纸面修改）；conf 必须如实反映 proven bin（MD5 门禁 `b0aa22e0…`） | `git checkout` 恢复 300000 原值，删 fallback_cpus |

> 修正后离线门禁重跑通过。v78 已编译安装（`adb install -r`），fire.sh v10 已推送到 `/data/local/tmp/fire.sh`（6483B, 755）。

### v77（2026-11-10，route_done 卡死断路 + fallback CPU + 30s route 超时）

事故背景：实机 357 次攻击中，`CMP_REQUEUE_PI ret=-1` 是正常中间态（100% 成功都经此）；真正的失败形态是 `multicast route status=0` 在 CMP 后永不出现，exploit 卡在 `waiting route_done` 直到 CLI 240s 超时 → watchdog 硬重启。v77 的核心是「快速失败、不累积 240s 空转」。

| 优化项 | 位置 | 备注 |
|---|---|---|
| `profile-tuned-reference.conf`：`route_done_timeout_ms` 300000→30000 | `scripts/tests/profile-tuned-reference.conf` | 避免 exploit 在 `waiting route_done` 处空转 240s 触发 watchdog |
| `profile-tuned-reference.conf`：新增 `fallback_cpus` (cpu2+cpu3 / cpu4+cpu5) + `kernelsnitch_fallback_cpu = 6` | 同上 | 主路由 (cpu0+cpu1) 路由卡死后自动切换备选 CPU 组，避免与主路由冲突 |
| `fire.sh` v9：新增 `route_deadlock` 检测——ghostlock 跑但 10s 无 landing → `pkill -9 ghostlock`，exit 1 | `scripts/device-artifacts/fire.sh`（需推设备） | 10s 主动 kill 替代 240s 空转，避免 watchdog 硬重启 |
| `RootUserService.java`：round loop 新增 route_deadlock 断路——读 `.fire.log` 含 "ROUTE_DEADLOCK" → `break`，不再进入下一轮 | `RootUserService.java` round 循环 | 堆已污染，继续下一轮等于 polluted heap 上重复 lottery（06:59 事故同款 wedge）；break 后提示用户重启手机再试 |
| 版本三齐 `77 / 2.61 / 45`（`build.gradle.kts`、`GhostRootService version(45)`、`MainActivity version(45)`） | 构建配置 + Java | 强制新 UserService，不复用旧进程 |

> 离线门禁 `scripts/tests/offline_profile_test.py` 通过：`profile.bin` MD5 `b0aa22e0351dbf3871f5997a8f2847cd` 与已验产物一致、multicast_waiter 偏移可复现、执行参数与实测 tuned reference 对齐。v77 未改提权时序/亲和性/重试轮数/profile 字节，仅在 route_done 卡死时提前退出。

### v45（2026-11-03，06:59 硬重启事故修复 —— 同 boot 二次开火）

事故链：06:57:41 第一发 LANDED+KSU 上线 → 06:58:37 第二发未被 alive-dedup
拦截（第一发 watcher 已退出）→ polluted heap 二次写 → RCU stall →
`panic_on_rcu_stall=1`（cmdline）→ 06:59:52 PS_HOLD 硬复位。
根因：该 KSU 移植为运行时 hook，`/proc/modules` 不可见，全链的 KSU 判据全盲。

| 修复项 | 位置 |
|---|---|
| fire.sh v7：`.fired` 记 boot_id（旧版只写 "FIRED"，无 boot 作用域）；watcher 启动时同 boot 匹配即拒绝开火 | `scripts/device-artifacts/fire.sh`（需推设备） |
| fire.sh v7：ksu_wait 加 `su -c id` 早退判据（模块表盲区下不再空等 20s） | 同上 |
| run 入口门禁扩展：preSu / ksu 模块 / `.fired` boot_id / 98260 跨工具驱动 四判据任一命中即跳过攻击 | `RootUserService`（already-rooted 路径分流） |
| 循环冷却判据升级：kernelsu 模块 ∨ `su -c id` uid=0 ∨ `.fired` boot_id，任一命中终止循环（单发守卫） | `RootUserService` round 循环 |
| 落地检测补盲区：`.fired` 匹配也按 LANDED 处理（enforce 可能被 fixup 恢复、模块不可见） | `RootUserService` round END 判定 + 耗尽后判定 |
| 跨工具门禁：98260.pid（timer 系驱动器）存活（cmdline 含 profile.conf）则拒绝攻击 | `RootUserService.foreignExploitRunning()` |

> 参考：timer-release.apk（单发 + parked 门禁模型）与干净轮次日志
> （成功形态 = `ksud daemon poll: module/install visible`）。

### v42（2026-11-02，13:2x 连续复位事故修复）

| 修复项 | 位置 |
|---|---|
| fire.sh v6：b0 加 10s 硬超时，chain_fired/exploit_killed 必然执行 | `scripts/device-artifacts/fire.sh`（已推设备） |
| fire.sh v6：`.fire.pid` 记 "pid boot_id"，跨 boot PID 复用 + 同秒双实例竞态修复 | 同上 |
| one.sh v6：W1c 失败诊断（dfroot_vr.ko size/md5/uname） | `scripts/device-artifacts/one.sh`（已推设备） |
| armFireWatcher：boot_id + /proc cmdline 存活校验 | `RootUserService` |
| 管理器门禁：magica 通过 且 exploit 已终止才提示可开管理器；firek 字段进 verdict/UI | `RootUserService` + `GhostRootService` |
| 落地后归档 /data/tombstones → /data/local/tmp/gr-tomb-\<ts\>/ | `RootUserService.archiveTombstones` |

## 已构建产物

`GhostRoot-v17-versionCode17-2.6-debug.apk`（62KB，debug 签名，可直接 adb install）

## 代码结构

| 文件 | 行数 | 职责 |
|---|---|---|
| `MainActivity.java` | 244 | UI。**只做两件事**：检查 Shizuku 是否安装/运行；点按钮才启动服务。不做任何自动执行 |
| `GhostRootService.java` | 286 | 前台服务，只响应 `ACTION_RUN` / `ACTION_REBOOT` intent，日志走通知 |
| `RootUserService.java` | 463 | ⭐ 核心。md5 门禁 → 预检 → 等 uptime → 10 轮循环 → 调 ghostlock CLI |
| `CliMain.java` | 60 | CLI 入口（`adb shell am` 调试用） |
| `IRootRunner.aidl` | — | `run()` / `rebootDevice()` |
| `ILogCallback.aidl` | — | 日志回调 |

## 执行流程（RootUserService）

```
1. md5 校验 profile.bin == b0aa22e0351dbf3871f5997a8f2847cd
   └─ 不匹配直接拒绝执行（防用坏 profile 打砖）
2. 预检 fire.sh / one.sh / b0 是否存在
3. 等 uptime >= 40s（太早没自启完，太晚命中率下降）
4. 10 轮循环：
     每轮  ghostlock --force-attack（240s 看门狗，超时 destroyForcibly）
     轮间  20s 冷却，冷却中检测到 KSU 就提前退出
     温度  只记录不拦截（遥测用）
5. 成功后等 90s 让 fire 链把 KSU 装进去
6. suCheck() 执行 su -c id 验证
```

## 构建

**注意 AGP 版本坑**：原工程写 AGP 8.4.0，配 Gradle 9.7.1 会报
`Cannot determine architecture from kernel architecture name`。
本机验证可用组合：**AGP 9.1.0 + Gradle 9.7.1**。

```bash
# 方式一：直接用本机已装的 gradle 9.7.1 wrapper（从 03-exploit-source 拷）
cp -R ../03-exploit-source/ghostlock-app/gradle .
cp ../03-exploit-source/ghostlock-app/gradlew .
sed -i '' 's/version "8.4.0"/version "9.1.0"/' build.gradle.kts
./gradlew assembleDebug

# 方式二：装 Gradle 8.x 保持 AGP 8.4.0 不变
brew install gradle@8
```

`local.properties` 里的 `sdk.dir` 要改成新机器路径。

## 使用前提

1. Shizuku 已安装并启动（`moe.shizuku.privileged.api`）
2. 已给 GhostRoot 授权 Shizuku 权限
3. 设备已部署 `05-device-artifacts/` 里的 6 个文件

## 设计红线（改代码时别破坏）

- ❌ 不要在 `onCreate` 里启动服务 —— 曾因自动执行导致"一开 App 就重启"
- ❌ 不要加 `am kill-all` —— 会杀掉 fire 链依赖的进程
- ❌ 不要加重试循环在单轮内部 —— 一轮一次，失败靠外层 10 轮
- ❌ 不要用温度做硬门禁 —— 与复位无因果关系，只会拖慢节奏
- ✅ 改 `RootUserService` 逻辑 → 必须 `serviceArgs.version(n+1)`

## 目录里的快照

`snapshot-20261030-1611/` 是 10-30 16:11 的完整设备状态存档（含 README、
device-state.txt、黄金文件备份、全天运行日志）。**排查历史问题时先看这里。**
