# vivo_net_fix 失效分析与修复方案（2026-11-03）

## 一、断网根因链（13:51 boot 实证）

1. **落地后全局 SELinux = Enforcing**（`/data/adb/vivo_net_fix/log.txt` 13:51:22 条目：`global enforce=1`，canary 6 次重试全 FAIL）
   - 历史稳定态是 exploit 后保持 Permissive（fire.sh v6 时代行为）
   - 现在的 v47 落地链中 enforce 被"链内 fixup"恢复为 Enforcing（RootUserService.java 注释原文：*enforce 可能被链内 fixup 恢复*）——这是刻意的隐藏设计，但 vivo_net_fix 没跟上
2. **Enforcing 下 vivo 反 root LSM 拦截数据面**（模块自己的 v1.6 实验结论）：
   - kernel 域（dp_rx_thread 软中断）scontext + **unlabeled 包类型**
   - unlabeled 是类型不是域，**permissive 语句救不了** → 190 条域级规则结构上注定无效
3. **第二重故障：ksud UAPI 失配**（今天发现的新问题）：
   - `/data/adb/ksud` 是 uapi2，内核侧 KSU 接口是 uapi5 → `module install` 全挂
   - uapi_guard 修复逻辑只存在于 PC 侧 `scripts/autoroot-v2.sh`，**gr_autoloop.sh 和设备端落地链都没有**
   - 推论：daemon 启动时 module sepolicy.rule（190 条）也可能根本没注入成功
   - 今日已手动修复：`cp /data/local/tmp/ksud-gk → /data/adb/ksud`（原 uapi2 备份为 ksud-uapi2.bak2）
4. 实证：`setenforce 0` 后 ping 立即恢复（49ms）——数据面本身没坏，纯粹是 LSM 拦截

## 二、重要新认识：190 条 permissive 规则本身就是检测向量

GitHub 调研结论（DirtySepolicy 检测向量，2026 主流）：
- 检测器读 `/sys/fs/selinux/policy`、查 status seqno、探 contextExists
- **"存在任何 permissive domain"（尤其 untrusted_app）即判 root**——Shopee 等已用此手法
- 即：v1.7 的 190 条域级 permissive = 用可见性换网络，与隐藏目标自相矛盾
- 结论：**vivo_net_fix 的正确终态不是 permissive 域，而是 Enforcing + 定向 allow 规则 + 检测面遮蔽**

## 三、修复方案（按优先级）

### P0 — 恢复网络（立即可用，临时态）
- `su -c setenforce 0`（本次已验证有效）
- 或改 service.sh：canary FAIL && enforce=1 时自动 `setenforce 0` 后复测（v1.6 红线需解除）
- 缺点：全局 Permissive 任何 app 调 getenforce 即见，检测面最大

### P1 — Enforcing 兼容的网络修复（v1.8 方向）
- 思路：不 permissive，改用 **allow 规则**直补数据面：
  ```
  allow kernel unlabeled:packet { send recv };
  allow kernel self:packet *;      # 类名以 live AVC 为准
  allow kernel self:capability net_admin;
  ```
- 方法论：落地窗口（KSU 活着）抓 `dmesg | grep avc`（注意 vivo dmesg_restrict，KSU 活着时可读 /dev/kmsg）→ audit2allow 逐条转 allow → `ksud-gk sepolicy apply` 实测
- 把 190 条 permissive 逐步替换为最小 allow 集，缩小 DirtySepolicy 暴露面

### P2 — 检测面遮蔽（配合 P1）
| 模块 | 作用 | 位置 |
|---|---|---|
| DirtySepolicy_Bypass (flipphoneguy) | Zygisk 拦截 /sys/fs/selinux 探针（contextExists/checkSELinuxAccess/seqno） | github.com/flipphoneguy |
| oMasko/Dirty_Selinux_Bypass | KPM 版：log_guard + dsp_bypass（syscall hook，LTO 免疫） | github.com/oMasko |
| ZN-AuditPatch (aviraxp) | hook read 拦截 audit/logcat 中 avc:denied 等关键词 | github.com/aviraxp |
| AuditPatch (silvzr) | linjector 进 logd，PLT hook 清洗 audit 日志 | magisk.yssn.tech |

- P1 + P2 组合 = Enforcing 稳态 + 网络通 + policy/log 侧信道遮蔽

### P3 — UAPI 守卫常态化
- gr_autoloop.sh / RootUserService 落地链补 uapi_guard（autoroot-v2.sh 82-104 行逻辑）
- 或直接固定 `/data/adb/ksud` = ksud-gk（今日已做，需验证跨 boot 存续 + GhostRoot 链不会换回）
- 验证：`ksud -V` 输出 `uapi: 5`

## 四、GitHub 隐藏环境生态备忘（2026-11 现状）

| 组件 | 版本/状态 | 备注 |
|---|---|---|
| Zygisk Next | v1.5.2-864 (Dr-TSNG/ZygiskNext)，已闭源 | bszip 常规方案内置同版；ReZygisk v1.0.0 (PerformanC) 为开源替代 |
| Shamiko | v1.2.5-414 (LSPosed) | 需 ZN，DenyList 不开 enforce |
| HMA-OSS | oss-161+ (frknkrc44) | 应用列表隐藏，bszip 方案已含 |
| Vector | JingMatrix/Vector (LSPosed fork) | 之前装的 zygisk_vector 就是它 |
| TrickyStore / TEESimulator-RS | keybox 证明 | bszip webroot 已带 keybox.xml |
| PlayIntegrityFix(inject) | KOWX712 | GMS 伪装 |
| 检测新向量 | DirtySepolicy / Duck Detector | 见上表遮蔽方案 |

## 五、手机重连后的执行清单

1. `ksud-gk -V` 确认 uapi:5（上次中断时 HMA 正在装，检查 modules_update/hma_oss_zygisk 完整性，不完整则重跑 bszip 安装器）
2. 检查 bszip 安装器中断后三件套（ZN/HMA/LSPosed）刷写状态，缺啥补啥
3. `setenforce 0` 立即恢复网络 → 用环境检测软件验证
4. vivo_net_fix v1.8：按 P1 方向改（先抓 AVC，再逐条 allow，最后上 P2 遮蔽）
5. 落地链补 uapi_guard（P3）
