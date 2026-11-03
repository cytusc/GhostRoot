# 内核离线分析报告 — 2026-11-03

来源：`/Users/hoyo/Downloads/20260821165517a9daef51ef9cbd772f4c3e9f4872e12b.zip`
（vivo PD2227 全量 OTA，payload.bin v2，用 payload-dumper-go 2.1.0 提取 boot/vendor_dlkm/system_dlkm）

## 一、结论（离线测试完成，全程未碰设备）

### 1. OTA boot 内核 = 设备运行内核（同一构建）
```
Linux version 5.15.197-g346b1c36bd5c-dirty ... clang 14.0.7 ... #1 SMP PREEMPT Thu Aug 20 07:36:46 UTC 2026
vermagic: 5.15.197-g346b1c36bd5c-dirty SMP preempt mod_unload modversions vivo aarch64
```
OTA 即本机内核的权威参照物。

### 2. PathMask 上次 insmod panic 的根因（铁证）
| 模块 | `.gnu.linkonce.this_module` 大小 = sizeof(struct module) |
|---|---|
| pathmask.ko（GKI 5.15.202 构建） | **1024** |
| vivo 官方 stock ko（全部 621 个） | **960** |

差 64 字节 → vivo 内核修改过 `struct module`。加载时内核按 960 布局写、模块按 1024 布局读 → 字段错位 → panic。
**vermagic 补丁只能过第一道门，结构性不兼容无解。**

排除项：
- ~~符号 CRC 不匹配~~：pathmask.ko `__versions` 段为空，内核跳过 CRC 校验
- ~~符号缺失~~：32 个 UND 符号在内核 kallsyms（191,910 个）中全部存在

### 3. vendor_dlkm 有两套模块 ABI
- `lib/modules/`（311 个）与 `lib/modules/5.15-gki/`（310 个）
- 共同符号 3594，CRC 一致仅 1555、不一致 2039（如 module_layout: 0x5ec51e3c vs 0x222dd63）
- 两套 `struct module` 均为 960 → 待设备侧确认运行内核加载哪套（/proc/modules 对目录）

## 二、产物（backups/kernel-analysis-20261103/，已 git 入库）
- `kernel.Image`（48MB，arm64 原始内核，来自 OTA boot 分区）
- `kernel.elf`（vmlinux-to-elf 重建，191,910 符号，kallsyms 查询/符号存在性分析用）
- `stock-crc-db.json`（621 ko 的 __versions CRC 库，按目录分 top/gki）
- `vendor_dlkm-ko-20261103.tar.gz`（48.8MB，621 个官方 stock ko 原文件；解压：`tar xzf vendor_dlkm-ko-20261103.tar.gz`）
- `system_dlkm.img`（340KB，erofs，system 侧 GKI 模块镜像——另一套 ABI 的对照物）
- 注：stock ko 的 .BTF 为 split-BTF（依赖内核 base BTF 去重，vivo 内核不发布 BTF），
  424 个中仅 89 个可解析且无带成员结构体，**结构体布局分析不可走此路**；
  struct module 大小以 `.gnu.linkonce.this_module` 段大小为准（可靠）
- 产物均提取自 OTA `20260821165517a9daef51ef9cbd772f4c3e9f4872e12b.zip`（9.1GB，未入库）

## 三、对 PathMask 路线的最终判定
**官方 5.15 构建在本机不可用，离线测试即已判死，不要再上设备试。**
可行替代：
1. 用 vivo kernel source + 本机内核 config 重编 pathmask（内核为 dirty build，源码可得性存疑）
2. 放弃 LKM 路线，用用户态遮蔽（Zepto/NoHello 类、ZN-AuditPatch、DirtySepolicy_Bypass）— 与 Phase 4 选型合并
3. vivo "内核融合/双内核" 机制待查：若运行内核可切到与 GKI ABI 兼容的一侧，需设备验证

## 四、复现实操（供复用）
```bash
# 提取分区
payload-dumper-go -no-verify -o <dir> -partitions boot,vendor_dlkm <ota.zip>
# boot.img v4 头：kernel@page_size, header@40=header_version
# vendor_dlkm 是 erofs: brew install erofs-utils && fsck.erofs --extract=<dir> vendor_dlkm.img
# CRC 解析：ko 的 __versions 段，64B 条目（u64 crc + char name[56]）
# struct module 大小：.gnu.linkonce.this_module 段的 sh_size
```
