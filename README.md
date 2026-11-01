# GhostRoot APK 工程（v17）

手机端一键提权 App，通过 **Shizuku UserService** 拿到 adb shell 等价权限，
在手机内直接跑 exploit 链（不依赖 PC）。

## 版本

```
versionCode  17
versionName  2.6
UserService  8        ← 改 RootUserService 逻辑必须同步 +1
```

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
