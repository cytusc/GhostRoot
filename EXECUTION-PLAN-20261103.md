# GhostRoot 环境恢复与 vivo_net_fix 修复实施计划（2026-11-03）

> 状态基准：root 在线（boot f445af51）/ ksud 已换 uapi5 / bszip 安装中断于 HMA 半途 / 全局 SELinux=Enforcing 时断网（已证 setenforce 0 可恢复）
> 关联文档：`NETFIX-RESTORE-PLAN-20261103.md`（根因分析）、`backups/modules-20261103/`（全部备份）

---

## 一、目标与范围

### 最终目标
在 V2227A（vivo，Android 16，内核 5.15.197-g346b1c36bd5c-dirty，KSU runtime uapi5）上建立**纯净、完整、可持续**的环境隐藏栈，且网络稳定可用：

1. bszip 悲伤一键隐藏环境 v2.2 完整安装（含其常规方案三件套：Zygisk Next 1.5.2 + HMA-OSS + LSPosed 2.2.1），全部经官方渠道纯净获取
2. vivo_net_fix 升级 v1.8：从 permissive 域级方案迁移到 **Enforcing 兼容方案**，消除"提权后断网"
3. 检测面收敛：通过环境检测软件（一键安装环境检测软件2.0 / Momo 类）验证隐藏有效性

### 范围内
- 设备端模块安装/修复、SELinux 策略设计、GhostRoot 落地链 uapi_guard 补丁、本仓库 vivo_net_fix 模块代码
### 范围外
- 内核重编译（uapi5 内核已就位）、exploit 本体修改、其他厂商检测对策（Shopee 等单 App 适配）

### 明确的前置约束（风险红线）
- **vivo 反 root LSM 对 `/data/adb/modules/` 有防篡改**：模块文件更新只能在 exploit 开火后的 b0 atcid 解锁窗口内做，或走 `ksud module install`（写 modules_update，开机迁移）
- 全局 Enforcing = 当前稳定态（隐藏设计），**不可为图省事回退 Permissive 作为终态**
- 手机当前没电，一切设备操作需等重连

---

## 二、任务分解与执行步骤

### Phase 0 — 设备重连与状态盘点（P0，无依赖，最先做）
| # | 步骤 | 命令/动作 | 通过判据 |
|---|---|---|---|
| 0.1 | 确认 adb 重连、电量 | `adb devices; adb shell dumpsys battery \| grep level` | level ≥ 30% 再做刷写类操作 |
| 0.2 | 确认 root 与 boot_id | `adb shell su -c id; cat /proc/sys/kernel/random/boot_id` | uid=0 ksu:s0；记录新 boot_id |
| 0.3 | ksud UAPI 复核 | `su -c '/data/adb/ksud -V'` | 输出含 `uapi: 5`；若被链换回 uapi2 → 执行 uapi_guard 手动换 ksud-gk |
| 0.4 | 模块现状盘点 | `su -c 'ls -1 /data/adb/modules/ /data/adb/modules_update/; ls /data/adb/modules/*/disable'` | 记录 bszip/ZN/HMA/LSPosed 各自所在位置与 disable 状态 |
| 0.5 | SELinux/网络现状 | `su -c getenforce; ping -c1 223.5.5.5` | 记录基线（预期 Enforcing + 断网，若是干净 reboot 则 root 已失，需重跑 gr_autoloop） |

**检查点 CP0**：状态矩阵填写完毕（boot_id / uapi / enforce / 模块表 / 网络），Phase 1 的输入确定。

---

### Phase 1 — bszip 三件套补完（P0，依赖 CP0）
| # | 步骤 | 命令/动作 | 通过判据 |
|---|---|---|---|
| 1.1 | 检查上次中断的 HMA 刷写 | `su -c 'ls -la /data/adb/modules_update/hma_oss_zygisk/ /data/adb/modules_update/zygisksu/ 2>/dev/null'` | 目录完整（含 module.prop + zygisk/ 或 bin/）；不完整 → 删除后走 1.2 |
| 1.2 | 重跑 bszip 安装器补齐 | `su -c '/data/local/tmp/ksud-gk module install "/sdcard/下载/悲伤一键隐藏环境_2.2版本.zip"'`（8s 超时默认常规方案） | 三件套全部 `✓ 刷写成功`（上次 ZN 已成功，重跑会自动跳过/覆盖 ZN，以汇总行为准） |
| 1.3 | 恢复 vivo_net_fix 使能 | `su -c 'rm -f /data/adb/modules/vivo_net_fix/disable'` | vivo_net_fix 无 disable flag |
| 1.4 | 网络就绪后重启 | `su -c 'setenforce 0'` → 确认 ping 通 → `adb reboot` | 设备完整起机 |
| 1.5 | 开机后验证 | 等待 1-3 分钟（bszip 提示首启耗时）→ `su -c 'ls /data/adb/modules/; getenforce'` + 确认 ZN WebUI 里 zygisk 状态 + HMA status.json 出现（`/data/adb/hma*` 或模块内 status 目录） | 三件套全部在 modules/ 且无 disable；zygiskd 进程存在（`ps -A \| grep zygisk`）；HMA 已激活 |

**检查点 CP1**：三件套全部 live。失败分支：若 HMA"模块未激活"（ZN zygote 注入竞态，RootUserService 注释记录的已知问题）→ 触发 ZN daemon 单例 + framework 软重启（`stop; start`）后复验。

**风险**：重跑安装器会再次临时 disable 现有模块并可能再次"卸载旧版 HMA 软件"（正常行为）；若 bszip 云端再失败 → 检查网络（Phase 2 的 setenforce 0 先行）。

---

### Phase 2 — 网络恢复与基线检测（P0，依赖 CP1）
| # | 步骤 | 命令/动作 | 通过判据 |
|---|---|---|---|
| 2.1 | 网络金丝雀 | `su -c 'ping -c1 223.5.5.5; ping -c1 www.baidu.com'` | 双通 |
| 2.2 | vivo_net_fix 日志核对 | `su -c 'tail -20 /data/adb/vivo_net_fix/log.txt'` | 本 boot canary PASS（记录 enforce 状态） |
| 2.3 | 环境检测基线 | 安装/运行 `一键安装环境检测软件2.0`（电脑备份 zip 内含 customize.sh，可作模块刷入或按其 README 运行 Click.sh） | 输出完整检测报告并**存档截图/文本作为 baseline** |
| 2.4 | 问题项分类 | 按 baseline 分类：SELinux 类 / 路径类 / 包名类 / policy 类 | 得出 Phase 3/4 的工作清单 |

**检查点 CP2**：baseline 检测报告归档（路径：`backups/modules-20261103/detection-baseline-<date>.txt`），明确"哪些项红"。

---

### Phase 3 — vivo_net_fix v1.8（Enforcing 兼容修复）（P1，依赖 CP2；核心工程项）
| # | 步骤 | 内容 | 通过判据 |
|---|---|---|---|
| 3.1 | AVC 取证 | 落地 boot 上：`su -c 'cat /proc/kmsg \| grep avc'` 或 `dmesg`（vivo dmesg_restrict，KSU 活着时可读 /dev/kmsg）；重点抓 kernel 域 + unlabeled tclass=packet 的 denied | 拿到完整 denied 列表（scontext/tcontext/tclass/perm 四元组） |
| 3.2 | 规则设计 | 逐条 audit2allow 风格转换，产出最小 allow 集（预计骨架：`allow kernel unlabeled:packet { send recv };`、`allow kernel self:packet *;`、netd/clatd/dnsmasq 相关补齐）；**同步删除对应 permissive 语句**（每转一条删一条，收敛 DirtySepolicy 暴露面） | 新 sepolicy.rule 通过 `ksud-gk sepolicy check`（若有）/ 注入 exit=0 |
| 3.3 | 实时注入验证 | `su -c '/data/local/tmp/ksud-gk sepolicy apply /tmp/netfix-v1.8.rule'`（Enforcing 下直接验证，勿 setenforce 0） | canary IP+DNS 双通且 getenforce=1 |
| 3.4 | 模块更新 | 改 `modules/vivo_net_fix/`（本仓库）：v1.8 module.prop + service.sh（保留金丝雀，新增：enforce=1 && canary FAIL 时自动尝试 sepolicy apply 本模块规则）→ zip 打包 → `ksud-gk module install`；**若 vivo LSM 拦直接铺 modules/，改在 b0 解锁窗口操作** | 模块 versionCode=8；下次 reboot 后 canary 在 Enforcing 下 PASS |
| 3.5 | 落地链 uapi_guard 补丁（P1 附带） | 在 `RootUserService` 落地后处理链（magica 步骤附近）或 gr_autoloop.sh 中加入 autoroot-v2.sh 的 uapi_guard 逻辑（82-104 行） | 新 boot 上 `ksud -V` 恒为 uapi:5，无需手动干预 |

**检查点 CP3**：连续 2 个完整 boot（落地 → 重启）上，Enforcing 状态下 canary 全 PASS，且检测软件无新增红项。

**风险**：
- allow 规则遗漏 → 部分链路仍断（DNS 解析、clatd IPv6），需 3.1 取证完整
- allow kernel unlabeled 规则同样可被 DirtySepolicy 探测（policy 里出现非原厂规则）→ 由 Phase 4 遮蔽兜底
- vivo LSM 拦模块文件更新 → 走 modules_update 标准路径或 b0 窗口

---

### Phase 4 — 检测面遮蔽（P1，依赖 CP3；与 Phase 3 可部分并行）
| # | 步骤 | 内容 | 通过判据 |
|---|---|---|---|
| 4.1 | 方案选型 | 候选：DirtySepolicy_Bypass（flipphoneguy，Zygisk）/ oMasko Dirty_Selinux_Bypass（KPM：log_guard+dsp_bypass）/ ZN-AuditPatch（aviraxp）/ AuditPatch（logd 清洗）。**选型原则：与 bszip 自带组件不冲突、优先 KPM/syscall 层（LTO 免疫）** | 选定 ≤2 个组件并记录理由 |
| 4.2 | 下载与纯净安装 | 全部从官方 GitHub release 下载 → ksud-gk 安装 → reboot | 模块 live，无 disable |
| 4.3 | 检测复测 | 重跑 Phase 2.3 同一检测软件 | SELinux/policy/audit 类红项转绿，且**无新增红项** |
| 4.4 | 终态决策 | 对比两终态：A) 全局 Permissive（简单、网络稳、检测面最大）B) Enforcing+allow+遮蔽（本次路线）。按 4.3 结果决定固化哪一个 | 决策记录写入 modules/README.md |

**检查点 CP4**：检测报告对比 baseline 的 delta 清单确认，终态决策落档。

---

### Phase 5 — 验收与归档（P2，依赖 CP4）
| # | 步骤 | 内容 | 通过判据 |
|---|---|---|---|
| 5.1 | 端到端回归 | 完整流程演练：`adb reboot` → gr_autoloop 提权 → uapi 自动就位 → 网络自动恢复（无手动 setenforce）→ 三件套 live → 检测软件通过 | 全流程 ≤10 分钟、零手动干预 |
| 5.2 | 稳定性观察 | 24h 内 3+ 次 reboot 循环 | canary 持续 PASS，无 bootloop、无新红项 |
| 5.3 | 归档 | vivo_net_fix v1.8 commit + push；备份更新（新模块表 tar）；本文档勾结；memory 更新 | git log 干净、远端同步 |

**检查点 CP5（最终验收）**：5.1 + 5.2 全过。

---

## 三、依赖关系图

```
CP0(状态盘点) ──► Phase1(三件套) ──► CP1 ──► Phase2(网络+基线) ──► CP2 ──┬─► Phase3(netfix v1.8) ──► CP3 ──┬─► Phase5(验收归档)
                                                                    │                                    │
                                                                    └────────────► Phase4(检测遮蔽) ──► CP4 ┘
Phase3.5(uapi_guard) 可与 Phase2 并行；Phase4.1(选型) 可与 Phase2/3 并行
```

优先级总览：**P0 = Phase 0/1/2**（恢复可用），**P1 = Phase 3/4**（根治+加固），**P2 = Phase 5**（固化）。

---

## 四、所需资源

| 类别 | 清单 |
|---|---|
| 硬件 | 手机（电量 ≥30% 再刷写）、数据线、PC |
| 软件资产（已在手） | `/tmp/zygisk-next-1.5.2-864.zip`（官方）、`backups/modules-20261103/`（bszip 2.2 官方 zip、环境检测 2.0 zip、全模块 tar）、`/data/local/tmp/ksud-gk`（uapi5）、bszip 三件套 zip（安装器云端缓存自取） |
| 待下载（Phase 4） | DirtySepolicy_Bypass / ZN-AuditPatch / AuditPatch 官方 release（届时从 GitHub 直下） |
| 知识/工具 | audit2allow 方法论、b0 atcid 解锁窗口操作规程（scripts/device-artifacts/one.sh 参考）、RootUserService 落地链代码（app/src/main/java/com/hoyo/ghostroot/RootUserService.java） |
| 时间预算 | 见第五节 |

## 五、时间安排（以手机重连时刻 T 起算）

| 时段 | 内容 | 里程碑 |
|---|---|---|
| T+0 ~ T+15min | Phase 0 盘点 + 1.1/1.2 三件套补完 | CP0、CP1 |
| T+15 ~ T+40min | 1.3-1.5 重启验证 + Phase 2 基线检测 | CP2 |
| T+40 ~ T+2h | Phase 3.1-3.3（AVC 取证 + 注入验证，当日可见效） | 临时版规则跑通 |
| 当日晚 | Phase 3.4 模块 v1.8 打包 + 3.5 uapi_guard 代码 | CP3 |
| T+1d | Phase 4 选型/安装/复测 + 终态决策 | CP4 |
| T+1d ~ T+2d | Phase 5 回归与 24h 稳定性 | CP5 |

弹性说明：Phase 3.1 取证质量决定 3.2 迭代次数，预留 2-3 轮；若 exploit 落地成功率波动（今日 1/1，昨日下午曾 14 死 2 落地），Phase 3/4 的 reboot 验证环节时间翻倍。

## 六、风险登记表

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | vivo LSM 拦截模块文件直写（防篡改） | 高 | 模块更新受阻 | 一律走 ksud module install（modules_update）；必要时 b0 解锁窗口操作 |
| R2 | allow 规则不全 → 网络半通（IP 通 DNS 断） | 中 | 3.3-3.4 反复 | 3.1 取证必须覆盖 WiFi+数据+IPv6(clatd) 三链路 |
| R3 | 规则注入后仍被 DirtySepolicy 类检测抓到 | 中 | 检测红项不降 | Phase 4 遮蔽兜底；必要时回退 P0 临时态（setenforce 0）保网络 |
| R4 | reboot 后 ksud 被链换回 uapi2 | 中 | 模块管理全瘫 | 3.5 uapi_guard 补丁；短期用 autoroot-v2.sh 手动跑 |
| R5 | bszip 安装器交互超时误选方案（默认常规） | 低 | 与预期方案不符 | 需要极客方案时用 `adb shell input keyevent 25`（音量下）在 8s 内选择 |
| R6 | bootloop / 环境恶化 | 低 | 设备不可用 | 全量模块 tar 备份在手（backups/modules-20261103/）；exploit 可重新落地；终兜底 = 清 modules/ 仅留 vivo_net_fix 重启 |
| R7 | exploit 落地率波动导致验证窗口拉长 | 中 | Phase 3/4 进度 | 验证 boot 尽量复用崩溃重启产生的干净 boot（gr_autoloop 已内置） |

## 七、验收标准（汇总）

1. **功能**：三件套 live（zygiskd 进程在、HMA status.json 在、LSPosed 可入管理器）；bszip WebUI 正常
2. **网络**：Enforcing 下 IP+DNS canary 双通，连续 3 boot 稳定，零手动 setenforce
3. **UAPI**：任意落地 boot 上 `ksud -V` = uapi:5，模块安装命令可用
4. **隐藏**：环境检测软件较 baseline 无红项恶化，SELinux/policy 类红项清零（或终态决策文档化例外）
5. **可持续**：vivo_net_fix v1.8 + uapi_guard 代码入库，端到端演练（5.1）零手动干预
6. **可回滚**：全量备份有效（tar 可复原），回滚步骤写入 README
