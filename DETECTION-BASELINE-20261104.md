# 检测基线报告 · 2026-11-04

> 环境：vivo V2227A（PD2227，Android 16 / API 36，kernel 5.15.197-g346b1c36bd5c-dirty）
> KernelSU：KernelSU_越狱（uapi5，LKM 模式）｜SELinux：Enforcing

## 一、环境状态快照

| 项 | 值 | 说明 |
|---|---|---|
| 提权 | ✅ `uid=0(root) context=u:r:ksu:s0` | `scripts/gr_autoloop.sh` 第 1 轮成功（08:43:59→08:44:32，22s） |
| ksud | 3.3.1 (uapi: 5) | uapi guard 已把 uapi2 替换为 uapi5 |
| SELinux | Enforcing | 全局强制模式保持开启 |
| 网络 canary | OK | Enforcing 下网络正常（与 v1.7 结论不同，需复测） |
| 模块（5，全部 enabled） | bszip v0.02.20 / hma_oss_zygisk / vivo_net_fix 1.7 / zygisk_vector v2.2 / zygisksu 1.5.2 | `ksud module list` 确认 |
| Zygisk 注入 | ❌ **未生效** | 见下节「重大发现」 |

## 二、重大发现：Zygisk 注入实际从未生效

**证据链**：

1. `znctl status`：`inject_state:1`、`zygote_states:2`（已连上 2 个 zygote）、
   `modules64:2,hma_oss_zygisk,zygisk_vector`、`modules_with_issue:0`
2. `znctl dump-zn`：**`Injected Processes: 0`**、`Found Services: 34`
3. app 进程 maps（Hunter 主进程）：无 `memfd:*` / `lspd` / `libzygisk` / `hmal` 任何注入痕迹
4. 内核侧：`grep -ic zygisk /proc/kallsyms` → **0**（无 zygisk 符号）

**根因**：本机 KernelSU 为定制「越狱」版，其内核侧**不提供 Zygisk 接口**。
Zygisk Next 1.5.2 能识别 KSU（`root_status:✅KernelSU`）但无内核接口可用，故无法完成注入。

**影响**：
- `zygisk_vector`（Vector，LSPosed 延续 fork）→ **未注入，等同未安装**
- `hma_oss_zygisk`（HMA-OSS）→ **未注入，隐藏应用列表未生效**
- 昨日记录的「32/64 位 zygote 注入成功」实为 `modules_info` 的**期望清单**，
  非实际注入状态 —— 该结论需要修正。
- `bszip` 不依赖 Zygisk（纯 KernelSU 模块 + post-fs-data 系统级隐藏）→ **不受影响，仍生效**

**已排除的修复尝试**（均无效）：
- `ksud post-fs-data` 触发 zygiskd → daemon 存活但仍 0 注入
- `ksud soft-reboot` 重启 zygote（daemon 先于 zygote 就绪）→ 仍 0 注入
- `znctl linker builtin` / `znctl stop|start` 重启 daemon → 仍 0 注入
- 硬重启 → post-fs-data 时机正确，但 KSU 内核模块当时未加载（LKM 模式）→ 仍 0 注入
- `ksud feature list` → 无 zygisk 相关 feature 开关

## 三、检测器基线结果

### Momo 3.5.0（io.github.vvb2060.mahoshojo）

**结论：环境异常 — 检测到对环境的修改**（截图：`backups/detection-baseline-20261103/base_momo_v2.png`）

| 类别 | 命中项 | 根因 |
|---|---|---|
| 环境异常 | SELinux 规则异常，允许了 neverallow 条目 | `vivo_net_fix` 的 **190 条域级 `permissive`** 规则放宽了 neverallow |
| 环境异常 | ART 参数异常 | `dalvik.vm.dex2oat-flags=--inline-max-code-units=0`（非出厂值；与 GhostRoot exploit 的 ART 布局稳定化相关，设备 `/data/local/tmp/gr-tomb-*/tombstone_*.pb` 中出现同一字符串） |
| 可疑痕迹 | 已开启调试模式 | `persist.sys.usb.config=adb`（当前 USB 调试开启所致） |

> **关键正向结论**：Momo **未**命中任何 root / su / Xposed 检测项
> → `bszip`（悲伤压缩包环境隐藏 v0.02.20）**隐藏层已生效**。
> 与 Momo「背景」页给出的社区倡议一致：
> ① su 只挂载到需要 root 的应用；② 禁止关闭 SELinux 强制模式、只加最小化允许规则；
> ③ Xposed 只注入目标进程。

### Hunter 6.72（com.zhenxi.hunter）

**结论：高风险设备，可能存在攻击行为！**

- 检测完成 · **133 项**（Main 95 · ISO 22 · TWIN 16）
- **高风险 11** · 未评估 2 · 不可用 0 · 超时 1

与昨日对比：132 项（Main 94 / 高风险 11）→ 133 项（Main 95 / 高风险 11），
多出 1 项 Main，疑与 Zygisk 未注入后暴露项变化有关，待定位。

高风险 11 项的具体条目**未能在 UI 中定位**：Hunter 为 Compose 渲染，不向
uiautomator 暴露语义树；列表项以表情图标区分状态，且无红色文字标记，
故当前无法从 UI 稳定反查。后续需改走「逐项展开读取」或实验对照法。

### Ruru（roro.stellar.manager）

实为 **Stellar 工具箱**（Root 启动 / 无线调试 / ADB 启动 服务端），非环境检测器。
当前「服务状态：未运行」。

## 四、工具链建设（本次新增）

| 工具 | 位置 | 用途 |
|---|---|---|
| `scripts/uidump.sh` | 项目内 | 无障碍节点树读取前台界面文本；`--scroll N` 滚动逐屏；`--raw` 原始 XML |
| `scripts/ocr.swift` | 项目内 | macOS Vision OCR 批量提取截图文本（带 y/x 坐标），替代逐张视觉识别 |
| `scripts/ocr_icons.swift` | 项目内 | 提取列表项左侧图标并生成带编号网格图 |
| `scripts/ocr_outlier.swift` | 项目内 | 图标离群检测（像素距离），用于找出非标准状态标记 |
| `scripts/ocr_color.swift` | 项目内 | 按文字颜色识别风险标记项 |

编译产物在 `/tmp`（`/tmp/ocr`、`/tmp/ocr_icons_bin`、`/tmp/ocr_outlier`、`/tmp/ocr_color`）。

## 五、踩坑记录

1. **`uiautomator dump` 持续失败**：`IllegalStateException: UiAutomationService ... already registered!`
   - 根因：**`li.songe.gkd`（GKD 跳广告）** 启动后抢占 UiTestAutomationService 注册位
   - 解决：`pkill -9 -f li.songe.gkd` 后 dump 恢复
   - 残留注册需重置 AMS（`kill -9 <system_server>` 软重启 framework）才能清除
2. **KSU 模块目录上下文不一致**：KSU 安装的模块为 `u:object_r:system_file:s0`，
   自建的 `vivo_net_fix` 为 `adb_data_file`。`su` 域（`u:r:ksu:s0`）**无法 cd 进
   system_file 目录** → `zygiskd daemon` 手动启动失败。解法：`chcon -R
   u:object_r:adb_data_file:s0 /data/adb/modules/zygisksu`，或改用 `ksud post-fs-data`
   （以正确上下文执行）。
3. **vivo LSM 对 `/data/adb` 访问间歇性拒绝**：同一命令重试即通，不能当作路径不存在。
4. **`su` 二进制位置**：KSU LKM 模式下 su 挂载在 `/system/bin/su`（提权时生成），
   不在 `/data/adb/ksu/bin/`；argv[0] 伪装成 `su` 调 ksud 无效。
5. **Zygisk Next 进程名伪装**：daemon 进程名被随机化（machikado/mazoku），
   `ps | grep zygiskd` 查不到，须按 `/proc/*/exe` 链接扫描。

## 六、待办（按优先级）

| P | 事项 | 依据 |
|---|---|---|
| ✅ P0 | ~~`vivo_net_fix` 移除 `untrusted_app*` permissive~~ **已完成（09:32）**：sepolicy.rule 190→184 条（移除 untrusted_app/_25/_27/_29/_30/_32 共 6 条），重启+重新 exploit 生效。备份：`backups/sepolicy.rule.bak-190` | Momo 环境异常 #1 → **已消除** |
| ✅ P0 | ~~ART 参数 `dalvik.vm.dex2oat-flags` 去留~~ **已解决（09:26）**：该 prop 由 zygisk_vector daemon 运行时设置（非 persist），删除后**exploit 依然成功**（不依赖 ART 布局，原担忧解除）。**它正是工行闪退的根因**（见第七节） | Momo 环境异常 #2 → **已消除** |
| P1 | 调试痕迹：收尾阶段关闭 USB 调试 / 还原 `persist.sys.usb.config` | Momo 可疑痕迹 #3（唯一剩余项）+ 千镜弹窗 |
| P1 | 决定 Vector/HMA 去留：当前无法注入。选项：(a) 放弃 Zygisk 路线、只保留 bszip；(b) 换用带 zygisk 支持的 KSU 内核；(c) 改用 Magisk 提供 zygisk。**注意：vector daemon 是 dex2oat-flags 的设置源，若保留需去掉该行为，否则重启后工行再闪退** | 本文第二节 + 第七节 |
| P2 | 用「逐项展开 + OCR」或「模块禁用对照法」定位 Hunter 的 11 项高风险 | Hunter 明细反查失败 |
| P2 | 修正 `scripts/gr_autoloop.sh`：增加 GKD 抢占 UiAutomation 的处理；把 Zygisk 激活纳入落地后自检 | 本次踩坑 1 |
| P3 | 把「Zygisk 未生效」写入 `EXECUTION-PLAN` 的结论区，修正昨日「注入成功」的错误结论 | 本文第二节 |

## 七、银行 App 闪退修复（2026-11-04 09:24–09:45）

**现象**：工商银行（com.icbc）冷启动必崩：`SIGSEGV SEGV_MAPERR fault addr 0x88c`，
`pc=0x88c sp=0 lr=0`（栈全清零，backtrace 全 `<unknown>`）→ **风控 SDK 判定环境篡改后的主动自毁**，非普通 bug。100% 可复现（同地址）。

**根因定位（控制变量实验）**：

| 实验 | 操作 | 结果 |
|---|---|---|
| 1 | `resetprop --delete dalvik.vm.dex2oat-flags` → 冷启工行 | **不再闪退**（主因确认） |
| 2 | sepolicy.rule 移除 untrusted_app* + 重启 + 重新 exploit → 冷启工行 | 稳定运行，无崩溃 |

**千镜可信引擎**（vivo 自家安全引擎，工行内嵌）弹窗原文：
> "千镜可信引擎检测到您的设备开启USB调试并已连接，存在被远程控制、泄露隐私的风险"

即闪退修复后仅剩 USB 调试一项提示（关调试即消失）。

**其他发现**：
- dex2oat-flags 设置源：`zygisk_vector` daemon（`daemon.apk` 含 `inline-max-code-units` 字符串，service.sh 用 unshare 隔离 ns 做 dex2oat bind-mount）。删除后重启它也未恢复（daemon 未再设置）。
- 工行进程内还观察到 `me.weishu.reflection`（FreeReflection）+ code_cache 匿名 dex（17 个 AnoymousDex），为风控的判定输入之一，非崩溃直接原因。
- **exploit 不依赖 dex2oat-flags**：新 boot（prop 为空）下 gr_autoloop.sh 第 5 轮成功提权。
- 网络：移除 untrusted_app permissive 后无 socket/packet 类 avc 拒绝（仅 /proc/fas/render 等 vivo 调度接口，与网络无关）。

**Momo 复测（09:45，截图 `momo_after_fix_20261104.png`）**：环境异常 3 项 → **1 项**（仅剩「已开启调试模式」）。SELinux neverallow、ART 参数两项均消除；root/su/Xposed 依旧未命中（bszip 隐藏层有效）。

**adb 主机侧踩坑**：macOS 下 adb server 崩溃（`EXC_GUARD / GUARD_TYPE_MACH_PORT`，崩在 `usb_kick→IOUSBInterfaceClass::~IOUSBInterfaceClass→mach_port_deallocate`，IOUSBLib 已知缺陷）。解法：`export ADB_LIBUSB=1` 改用 libusb 后端。
