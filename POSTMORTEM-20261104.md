# 故障排查归档 · 2026-11-04

> 会话范围：vivo V2227A（PD2227，Android 16，kernel 5.15.197）GhostRoot 环境
> 关联文档：`DETECTION-BASELINE-20261104.md`（完整基线与证据链）、`NETFIX-RESTORE-PLAN-20261103.md`

---

## 一、问题与解决方案

### 问题 1：工行 App 打开必闪退（银行类 App 全军覆没的前兆）

**现象**
- com.icbc 冷启动 100% 复现 `SIGSEGV SEGV_MAPERR fault addr 0x88c`
- 崩溃特征：`pc=0x88c, sp=0, lr=0`，寄存器栈全清零，backtrace 两帧全 `<unknown>`

**判断依据（结论先行）**
- 正常 native bug 不会把 SP 清零；`pc=0x88c` 且 sp/lr 同时归零是**风控 SDK 检测到环境篡改后的主动自毁**（反取证设计：故意让分析者拿不到调用栈）
- 崩溃前日志的判定输入：`me.weishu.reflection` 调 `setHiddenApiExemptions` 被拒、code_cache 匿名 dex（17 个 `AnoymousDex`）、`DexFile in boot class path but not in a known location`

**根因（控制变量实验定位）**

| 实验 | 操作 | 结果 |
|---|---|---|
| 1 | `resetprop --delete dalvik.vm.dex2oat-flags` → 冷启工行 | **不再闪退** → 主因确认 |
| 2 | sepolicy.rule 移除 `untrusted_app*` + 重启 + 重新 exploit → 冷启工行 | 稳定运行（无叠加因素） |

**修复命令**
```bash
adb shell su -c "/data/adb/ksu/bin/resetprop --delete dalvik.vm.dex2oat-flags"
```
- 该 prop 由 `zygisk_vector` daemon 运行时设置（`daemon.apk` 内含 `inline-max-code-units` 字符串，service.sh 用 `unshare -m` 隔离 ns 做 dex2oat bind-mount），**非 persist**，不在 `/data/property/persistent_properties`
- 修复后工行稳定运行；千镜可信引擎弹窗仅剩「USB 调试已连接」一项

**回滚方式**：`resetprop dalvik.vm.dex2oat-flags --inline-max-code-units=0`（或重启让 daemon 重设）

---

### 问题 2：千镜可信引擎检测到环境异常

**现象**：工行弹窗「支付风险」。引擎原文：*"千镜可信引擎（vivo 安全风险检测引擎）检测到您的设备开启USB调试并已连接"* —— **千镜是 vivo 自家引擎**，非第三方。

**处理**：闪退修复后仅剩 USB 调试一项（关闭调试即清零，与问题 4 合并处理）

---

### 问题 3：Momo 检测「环境异常，检测到对环境的修改」（3 项）

**修复前 3 项**：① SELinux neverallow 被放宽 ② ART 参数异常 ③ 已开启调试模式

**修复 ①（SELinux）**
- `vivo_net_fix/sepolicy.rule` 190→184 条：删除 `permissive untrusted_app{,_25,_27,_29,_30,_32}` 6 条
- live policy 无法运行时移除 permissive，必须**重启**让 KSU 重新加载，随后 `gr_autoloop.sh` 重新 exploit
- 网络验证：avc 拒绝中无任何 socket/packet 项，vivo LSM 拦截未复发

**修复 ②（ART 参数）**：同问题 1

**修复 ③（调试模式）**：留待收尾（adb 在用）

**结果**：Momo 3 项 → **1 项**（仅剩调试模式）；root/su/Xposed 始终未命中（bszip 隐藏层有效）。复测截图：`backups/detection-baseline-20261103/momo_after_fix_20261104.png`

---

### 问题 4（主机侧）：adb 完全失联（macOS）

**现象**：`adb devices` 空、`adb start-server` 反复 `ADB server didn't ACK`，server 起来即死
**根因**：macOS `EXC_GUARD / GUARD_TYPE_MACH_PORT` 强杀 server——崩溃栈 `usb_kick → IOUSBInterfaceClass::~IOUSBInterfaceClass → mach_port_deallocate`，IOUSBLib 已知缺陷（崩溃报告在 `~/Library/Logs/DiagnosticReports/adb-*.ips`）
**修复**：
```bash
export ADB_LIBUSB=1   # 改用 libusb 后端，绕开 IOUSBLib
```
**注意**：`nohup adb nodaemon server &` 的进程会被 shell 回收收，后台常驻需用任务管理方式

---

## 二、踩坑清单（避免重复）

### 设备侧
1. **SELinux policy 只能整体替换**：permissive 域一旦 load 进内核无法单独移除，改 `sepolicy.rule` 后必须重启 + 重新 exploit（LKM 模式 root 会丢，需 autoloop 重跑）
2. **模块目录写入三连坑**：
   - `cd` 进目录可能失败 → `grep ... > file` 重定向落在错误位置报 `Read-only file system`（误导性极强），用绝对路径可写
   - toybox sed 对部分 pattern 报 `bad pattern`（adb 引号拆解加剧）→ 复杂编辑**一律 push 脚本到 /data/local/tmp 再 `su -c sh` 执行**
   - 覆盖已有文件报 `Permission denied`，但 `touch` 新文件成功 → `rm` 后 `cp` 绕过
3. **adb 引号拆解**：`adb shell su -c "grep 'a b' file"` 中空格会被拆参数（`grep: untrusted_app: No such file`）→ 设备侧脚本化是唯一稳妥路径
4. **resetprop 位置**：KSU 下无全局 resetprop，用 `/data/adb/ksu/bin/resetprop`；`setprop` 传空值 `''` 会被引号拆解吃掉（传 `0` 或用 resetprop --delete）
5. **exploit 与 ART 参数解耦（修正旧结论）**：`dalvik.vm.dex2oat-flags=--inline-max-code-units=0` **不是** exploit 前提——新 boot 空值下 gr_autoloop.sh 第 5 轮成功。旧文档"ART 布局前提"作废，该参数只服务 zygisk_vector 的 dex2oat hook
6. **Zygisk 未生效但痕迹还在**：zygisk_vector 注入从未成功（`znctl dump-zn: Injected Processes: 0`），但其 daemon 仍会设置系统级 prop、sepolicy.rule 仍有 `allow * xposed_data *` 宽松规则——**禁用功能 ≠ 消除痕迹**
7. **uiautomator dump 静默失败**：`li.songe.gkd`（GKD）抢占 UiAutomation 注册 → `pkill -9 -f li.songe.gkd` 后恢复；旧 boot 残留注册需 kill system_server 软重启 framework
8. **风控自毁型崩溃的识别**：`pc` 为小值常量 + `sp=0` + backtrace 全 `<unknown>` ≈ 反篡改主动跳转，不要按普通空指针排查

### 主机侧（macOS）
9. adb server 崩溃先查 `~/Library/Logs/DiagnosticReports/adb-*.ips`，`EXC_GUARD` 类直接换 `ADB_LIBUSB=1`，不必反复 kill-server
10. 工作区后台进程（`nohup ... &`）随命令结束被回收，常驻服务用 run_in_background 任务

### 方法论
11. **控制变量实验是定位根因的最快路径**：两个可疑因素（ART 参数 / SELinux）分别单独切换验证，各一步锁定主因，比静态分析 APK 风控逻辑快一个数量级
12. **先验证 live policy 再改规则文件**：avc 日志里 `permissive=1` 标志是确认域级 permissive 生效的直接证据（磁盘 cil 的 neverallow 计数完好，放宽发生在内存 policy）

---

## 三、遗留事项

| P | 事项 | 说明 |
|---|---|---|
| P1 | 关闭 USB 调试 | Momo 最后一项 + 千镜弹窗，收尾时做 |
| P1 | zygisk_vector 去留 | 若保留必须移除其 dex2oat-flags 设置行为；本次重启后 daemon 未重设 prop（Zygisk 未注入使其 hook 不生效），但存在恢复风险 |
| P2 | Hunter 复测 | 确认 11 项高风险是否随 SELinux 修复下降 |
| P2 | sepolicy.rule v1.8 正式化 | 当前 184 条为"删减版"，module.prop 版本号仍是 1.7；后续可将 6 条 app 域 permissive 改为最小定向 allow 以保留网络兜底 |
| — | git revert 序列残留 | 工作区有一个 11-03 遗留的 revert in progress（revert v49 误标修复 + v48 调优），补丁未实际应用，处理前先与负责人确认意图 |
