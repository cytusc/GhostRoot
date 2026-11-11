# 淘宝启动提速：去开屏广告 / 去启动页 / 深链式直进首页

> 项目：`taobao-adblock`（LSPosed 模块，libxposed API 102）
> 实机：vivo V2227A · Android 16 (SDK 36) · LSPosed 2.2.1 (7940)
> 最终版本：**v1.7.0 (vc8)**，冷启动到首页 **~1.5s**（原版 3s+），热启动 **132ms** 直达首页

---

## 一、目标演进

| 阶段 | 用户诉求 | 结论 |
|---|---|---|
| 1 | 跳过开屏广告 | hook 广告 View，自动点击关闭 |
| 2 | 去掉启动动画，尽快进首页 | 压制 bootimage 启动图窗口 |
| 3 | 白底「淘」logo 画面也不要 | 多层清窗（此处走过弯路） |
| 4 | **启动 app 直接进入首页**（像广告深链跳转一样快） | 深链式直跳，最终方案 |

核心转折：目标不是「消除某个过渡画面」，而是**让主 Activity 尽早创建、过渡环节全部跳过**。过渡画面的颜色（白/黑）只是表象。

---

## 二、技术底座（所有模块共用）

- libxposed API 102（`XposedModule`），不改宿主 dex、不用无障碍、不模拟点击框架。
- 三个通用 hook 点：`LayoutInflater.inflate`、`ViewGroup.addView`、`View.onAttachedToWindow`。
- 安全铁律：interceptor 先 `chain.proceed()` 再做事；新增逻辑全在 try/catch；`ThreadLocal` 防重入；类/方法找不到只打日志、模块保持 inert。
- 版本纪律：每个新 APK 同步 bump `versionCode` + `versionName`（build.gradle / AndroidManifest / module.prop 三处）。

---

## 三、迭代过程与关键证据

### v1.1.0 —— 自动关闭开屏广告

抓实机开屏广告 View 树（设备 shell 密集循环 `dumpsys activity top` + uiautomator 补 text），定位关闭按钮 id：`ll_close` / `tv_close`。

- 多轮点击：0 / 200 / 500 / 1000 / 1800 / 3000ms。
- 实测命中 `ll_close`，广告窗口消失，直接见首页。

### v1.2.0 —— 压制启动图窗口

广告窗口由 `com.taobao.bootimage.view.FilletLinearLayout`（`fl_root_view` / `fl_animation_view`）构成。

- 命中 `fl_root_view` 且子树含 bootimage 特征 → 整窗 `GONE`；多轮压制 0~6000ms。
- `fl_animation_view` 提前 GONE；`addView` 前置隐藏（连首帧都不绘制），消除 30 帧 Choreographer 卡顿。
- 注意：淘宝有开屏广告频控，连续冷启常不下发广告，验证时必须区分「没下发」和「被跳过」。

### v1.3.0 —— 针对 Welcome 启动页清窗

实机窗口 dump 修正判断：冷启白底「淘 logo + 阿里云提供计算服务」由 `Welcome`（`com.taobao.tao.welcome.Welcome`，半透明桥接 Activity）承载。

- hook `Welcome.onResume`，proceed 后多轮把 window/decor 背景置透明、`android.R.id.content` 子 View 全部 GONE（不 remove，宿主导航不受影响）。
- 白底时长从 2-3s 降到约 1 帧。

### v1.4.0 —— 清窗时机提前到 onCreate

- hook `android.app.Activity.onCreate(Bundle)`（框架基类；Welcome 未覆写 onCreate，虚拟分发落在基类可被拦截），实例过滤 Welcome。
- proceed 后**同步**清窗（早于首次绘制）。
- 实测：early clear 触发了，但白底仍在，且此时 content 为空 → **证明 logo 不在 content 里**。

### v1.5.0 —— 清 decor 覆盖层（仍未根除）

- 推断 logo 是直接挂在 decorView 上的 splash 覆盖层 → 遍历 decor 直接子 View，凡不在 `decor → android.R.id.content` 祖先路径上的一律 GONE。
- 实测白底缩短到采样间隙以下（一度误判为「零帧残留」，后被 v1.7 修正，见 §四）。
- 用户反馈：白屏没了，但出现**黑屏**——本质是过渡画面换了层皮，启动等待仍在。

### v1.6.0 / v1.7.0 —— 深链式直跳（最终方案）

**思路**：和「广告深链跳转进 app」走同一条路——进程就绪后直接创建主 Activity，跳过 Welcome 的全部等待逻辑（广告倒计时、配置拉取）。

```java
// hook Activity.onCreate，实例过滤 Welcome，在 chain.proceed() 之前：
Intent i = new Intent();
i.setClassName("com.taobao.taobao", "com.taobao.tao.TBMainActivity");
i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);   // 防重复实例
activity.startActivity(i);
activity.finish();                                    // Welcome 一帧都不画
activity.overridePendingTransition(0, 0);             // 去切换动画
```

- v1.6.0：proceed **后**跳。生效、无崩溃，`Displayed +1s419ms`。
- v1.7.0：proceed **前**跳——Welcome.onCreate 一行都不执行，主 Activity 尽早创建。
- 失败兜底：jump 抛异常时自动回退 v1.2–v1.5 的压制逻辑。

**实测（vivo V2227A）：**

| 场景 | 原版 | v1.7.0 |
|---|---|---|
| 冷启动 `am start -W` | ~3s+ | **1482ms**（两次复测，无崩溃，jump 每次触发） |
| 热启动（进程存活） | — | **132ms** 直接回首页 |

---

## 四、重要定性修正：白底 logo 是系统 SplashScreen

时序证据链：jump 发生在 `16.042s`，而白底帧出现在 `~15.9s`——**早于 Welcome 任何一帧可能被绘制的时间**（首帧绘制在 onResume 之后）。说明那帧画面不是 app 画的：

- 它是 **Android 12+ 系统 SplashScreen starting window**，在淘宝进程启动**之前**由 `system_server` 直接从宿主主题资源绘制：
  - `windowSplashScreenAnimatedIcon` = 淘 logo
  - `windowSplashScreenBrandingImage` = 底部「阿里云提供计算服务」
- App 进程内任何 hook 都无法移除它；系统在**主 Activity 首帧就绪后**自动撤销。
- 因此：让主 Activity 尽早就绪 = 让系统 splash 尽早消失（v1.7.0 正是这么做的）。
- 冷启动 ~1s 的系统 splash 是物理下限。

**方法论教训**：「清窗成功但画面没变 → 东西在别处」；单次截帧序列可能漏采亚秒级画面，关键结论要用日志时间戳交叉验证。

---

## 五、死路与坑（避免重蹈覆辙）

1. **system_server 作用域模块不可行**：LSPosed 2.2.1（libxposed 架构）不支持把模块注入 system_server。db 配置全对（modules 表路径、enabled=1、android scope）、`install -r` 刷新缓存、多次 zygote 软重启，`onModuleLoaded` 始终不触发。备用模块 `taobao-nosplash` 已停用卸载，仓库留档。
2. **新装模块 / 新加 scope 只写 db 不生效**：lspd 缓存模块与 scope，必须 `adb install -r` 同一 APK 触发 `PACKAGE_REPLACED` 再重启 zygote。
3. **绝不要 `killall lspd`**：会连带 KernelSU LKM 失效（root 丢失），需用户在 KernelSU Manager 重新提权。
4. **vivo 安装确认页**：`pm install` 会挂起等待弹窗，需截屏后 tap「已了解风险」勾选 + 「继续安装」。
5. **广告频控干扰验证**：连续冷启淘宝/京东常不下发广告，别把「没下发」当「跳过成功」。

---

## 六、最终时序对比

```
原版冷启动（~3s+）：
点图标 → 系统splash → [Welcome: 初始化+广告倒计时 2-3s] → 首页

v1.7.0 冷启动（~1.5s）：
点图标 → 系统splash(~1s，系统绘制，等首帧) → 首页
         └─ 进程初始化 + 主Activity创建（模块直跳，Welcome被完全跳过）

v1.7.0 热启动（132ms）：
点图标 → 首页（无任何过渡画面）
```

**「点开即首页」的现实路径**：让进程保持存活（vivo 设置 → 电池 → 后台高耗电允许淘宝 + 最近任务锁定）。冷启动的 ~1.5s 是进程初始化 + 系统 splash 的物理下限，模块侧已做到极限。

---

## 七、可复用的验证方法

- **帧序列采样**：设备端 shell 脚本「`am start` + 循环 `screencap`」，30 帧 ~0.7s/帧；帧文件大小可快速分类（纯色 <25KB，部分内容 50-400KB，完整首页 >1MB），再用图片查看确认。
- **日志时间轴**：模块自定义 TAG（`TBAdBlock`）打点 jump/clear/suppress/click；对照 `ActivityTaskManager: Displayed` 的 `+Xms`。
- **热/冷对照**：`am start -W` 的 `TotalTime` + `pidof` 确认进程状态，区分 COLD/WARM。
- **定位「谁画的画面」**：用日志时序排除法——若画面出现在 app 进程能画出的最早时间之前，必是 system_server 所绘。
