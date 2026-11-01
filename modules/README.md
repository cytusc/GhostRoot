# KSU 模块（设备端）

随 GhostRoot App 一起工作的两个 KernelSU 模块。安装方式：zip 打包后经
KernelSU 管理器安装，或在 exploit 开火后的解锁窗口直接铺到
`/data/adb/modules/`。

## ghost_hide — 系统级 root 兜底屏蔽（v0.4 / vc4，v0.4 新增：敏感 App 定向加固 + 自同步通道）

- 非 KSU 的 su 残留（xbin/su、sbin/su、su/bin、vendor/bin/su、Superuser.apk）chmod 000
- `/proc/modules` chmod 440（普通应用读不到内核模块列表）
- root 授权审计：ksu.db 懒创建不存在 = KSU 默认拒绝兜底成立；存在则 strings
  抽包名核对白名单（com.hoyo.ghostroot | me.weishu.kernelsu | com.tsng.hidemyapplist），
  vivo/bbk/iqoo 包持 root 记 [ALERT]
- 反干扰守卫（清 netfix disable）、基线快照
- 日志：`/data/adb/ghost_hide/log.txt`

## vivo_net_fix — vivo 反 root 网络解封（v1.7 / vc7，v1.7 新增：网络金丝雀自检）

- 主体：`sepolicy.rule` 190 条域级 permissive（189 条应用/网络域 + kernel 域）
- **v1.6 红线变更（2026-11-01 enforcing 实验结论）**：全局 Enforcing 下
  kernel 域（dp_rx_thread 软中断）+ unlabeled 包类型的数据面会被 LSM 拦截，
  unlabeled 是类型不是域、permissive 放行不了 → 网络必断。
  **exploit 后保持全局 Permissive 是预期稳定态**，service.sh 只记录不判 FAIL
- 域级放行本身有效（AVC 实证 platform_app permissive=1）
- 手动实时策略注入（如做 enforcing 实验）：`/data/local/tmp/ksud-gk sepolicy apply
  /data/adb/modules/vivo_net_fix/sepolicy.rule`（uapi:5 与内核配平，已验证 exit=0）
- 日志：`/data/adb/vivo_net_fix/log.txt`

## ⚠️ 部署纪律（vivo 反 root LSM 防篡改）

vivo 自定义 LSM 对 `/data/adb/modules/` 有**定向防篡改**：

- `service.sh` inode 的覆盖/删除/改名一律 EPERM（与 SELinux 无关，Permissive 也拦）
- 目录访问呈间歇性放行/拒绝翻覆
- **模块文件更新只能在 exploit 开火后 b0 atcid 解锁窗口内做**，平时只读审计
- 三层分工：HMA（com.tsng.hidemyapplist，用户装）= 细粒度按应用屏蔽；
  ghost_hide = 系统级兜底；vivo_net_fix = 网络修复；月虹（YH_YC）已删除
