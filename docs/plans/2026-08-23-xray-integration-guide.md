# Xray 内置代理功能 — 上游更新后的整合指南

> 目的：SmartTube 上游发布新版本后，把本次 Xray 代理改动一次性整合进去。
> 本文档是改动全集 + 冲突点清单 + 整合步骤。设计细节见 [2026-08-21-xray-proxy-design.md](2026-08-21-xray-proxy-design.md)。

## 功能概要

App 冷启动时在启动页自动执行检测，全程无需用户操作：

0. **快速通道** — 若上次已选定节点（`xraySelectedOutbound` 缓存非空），跳过 Phase 1/2，先用临时 core `measureOutboundDelay` 实测纯 RTT（目标 YouTube `generate_204`）；可达且 ≤ 500ms 即起完整 core 直接使用（约 1~2s），失败或超时才回退完整三阶段流程
1. **Phase 1** — TCP ping 全部节点（20 并发，3s 超时）
2. **Phase 2** — ping 最快的前 **60** 个节点用 `Libv2ray.measureOutboundDelay` 实测真实转发延迟（12 并发，测速目标即 YouTube `generate_204`）；按上次实测延迟缓存（`xray_node_delays`）排序优先测好节点，3 个节点实测 ≤ 500ms 即提前退出，整体 60s 封顶；实测节点按延迟排在候选列表前部
3. **Phase 3** — 候选列表头部节点启动完整 Xray core（本地 SOCKS `127.0.0.1:10808`），OkHttp 经代理验证 `https://www.youtube.com/generate_204`，失败则顺移下一节点（最多 3 个）

成功后通过 JVM 系统属性把全 App 流量（OkHttp/HttpURLConnection）导入本地 SOCKS，并强制播放器数据源为 OkHttp（Cronet 不读 JVM 代理属性）；失败则直连进入主界面。启动期间 SplashActivity 保持前台并实时显示进度，看门狗 120s 强制放行。

订阅加载顺序：用户自定义 URL → 内置默认订阅 URL → APK 内置 asset 兜底（`xray_builtin_sub.yaml`，113 节点：90 trojan + 23 vless）。

## 改动全集

### A. 新增文件（上游更新后可直接整体拷贝，无冲突）

| 路径 | 说明 |
|---|---|
| `SharedModules/xraycore/`（整个目录，**在 SharedModules 子模块仓库内**） | Xray-core gomobile 绑定 wrapper 模块 |
| `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/xray/`（5 个文件） | 全部业务代码，见下 |
| `common/src/main/assets/xray_builtin_sub.yaml` | 内置订阅节点列表（兜底） |
| `common/src/test/java/.../proxy/xray/ClashConfigParserTest.java` | 单元测试（Robolectric） |
| `common/src/androidTest/java/.../proxy/xray/XrayNodeConnectionTest.java` | 真机三阶段连通性测试 |
| `smarttubetv/src/main/res/layout/activity_splash.xml` | 启动页布局（logo + ProgressBar + 状态文字） |

`proxy/xray/` 包内文件：

- `XrayManager.java` — core 生命周期、`ensureEnv()`（go.Seq.setContext + initCoreEnv，**任何 native 调用前必须先调**）、端口等待、`measureNodeDelay()`
- `ClashConfigParser.java` — Clash YAML → Xray outbound JSON（snakeyaml）。要点：trojan 隐含 TLS 必须强制 `tls: true`；新版 Xray-core 已删除 `allowInsecure` 字段，**不能输出**，否则 config load error
- `XrayNodeSelector.java` — 手动选节点 UI；`loadSubscription()` 是订阅加载公共入口；`DEFAULT_SUB_URL` 是内置默认订阅地址
- `XrayBootstrap.java` — 启动三阶段自动检测（含 onProgress 回调）；`REAL_TEST_TOP_N = 60`
- `ProxyNode.java` — 节点数据模型

### B. 修改的上游文件（整合时需重新合并，冲突点都在这）

| 文件 | 改动 |
|---|---|
| `SharedModules/core_settings.gradle`（子模块仓库） | include 增加 `:xraycore` 并指向 `xraycore` 目录（2 行） |
| `common/build.gradle` | 依赖：`project(':xraycore')`、`org.yaml:snakeyaml:1.33`、androidTest 的 junit/androidx.test（共 8 行） |
| `common/.../prefs/AppPrefs.java` | 新增 `xrayEnabled` / `xraySelectedNodeName` / `xraySelectedOutbound` 等 prefs 键 |
| `common/.../proxy/ProxyManager.java` | 新增 `configureProxy(Proxy)` 纯内存应用方法（**不写持久化 prefs**，避免污染手动 web 代理设置） |
| `common/.../app/presenters/SplashPresenter.java` | `initProxy()` 启动 bootstrap；`mXrayBootstrapPending` 挂起 `applyNewIntent`；`continueStartup()` 在检测期间暂停其他 Activity 启动（**关键**：SplashActivity 是 singleInstance，任何其他 Activity 启动都会让它退后台回桌面）；`mXrayWatchdog` 120s 看门狗 |
| `common/.../app/views/SplashView.java` | 接口新增 `updateStatus(CharSequence)` |
| `smarttubetv/.../tv/ui/main/SplashActivity.java` | `setContentView(R.layout.activity_splash)` + 实现 `updateStatus()` |
| `common/src/main/res/values/strings.xml` + `values-zh/strings.xml` | `xray_*` 系列字符串 |
| `common/.../settings/GeneralSettingsPresenter.java` | 设置页「内置代理 (Xray)」入口（订阅地址/选择节点）。注意：弹 SimpleEditDialog 前必须先 `closeDialog()`，否则 AppDialog 独立 Activity 场景下 BadToken |

### C. 大体积二进制（不入 git，需重新拉取）

`SharedModules/xraycore` 下的 `libs/libv2ray-classes.jar`、`src/main/jniLibs/`（4 个 ABI 的 libgojni.so）、`src/main/assets/geoip|geosite.dat` 已 gitignore（目录共 ~591MB，含 build 缓存）。重新拉取：

```bash
cd SharedModules/xraycore && ./download_xray.sh          # 默认 v26.8.20
cd SharedModules/xraycore && ./download_xray.sh vX.Y.Z   # 指定版本
```

来源：`github.com/2dust/AndroidLibXrayLite` 的 `libv2ray.aar`。因 Android library 模块不能直接依赖本地 aar，故解包成 jar + jniLibs + assets 组织。

## 上游更新后的整合步骤

1. **先保护现场**：确保当前改动已提交到独立分支（主仓库和 SharedModules 子模块**各自提交**，例如 `xray-proxy` 分支）。未提交就去拉上游等于裸奔。
2. 主仓库 `git fetch upstream && git merge/rebase` 上游新版；SharedModules 子模块同样更新（上游若 bump 了 submodule 指针，先切回自己的 xray 分支再合上游）。
3. A 类新文件基本不会冲突；B 类文件逐个解决冲突，重点核对：
   - `SplashPresenter` / `SplashActivity` / `SplashView` — 上游最常动的启动流程文件，`updateStatus` 接口和启动挂起逻辑要保住
   - `AppPrefs` — 键名冲突直接保留新增键即可
   - `common/build.gradle` — 依赖块追加，注意上游可能改了 androidx.test 版本变量
   - `ProxyManager.configureProxy` — 上游若重构代理逻辑，需保证存在「只应用、不持久化」的路径
4. 重跑 `download_xray.sh`（或拷贝保留的二进制），确认 geoip/geosite.dat 在位。
5. 构建验证：`./gradlew :smarttubetv:assembleStstableDebug`，产物在 `smarttubetv/build/outputs/apk/ststable/debug/`。
6. 跑 `ClashConfigParserTest`（`./gradlew :common:testDebugUnitTest`）+ 真机/模拟器冷启动一次，logcat 过滤 `XrayBootstrap` 确认三个阶段全过。

## 已知注意事项

- **ABI**：xraycore 带 4 个 ABI 的 so，每种 ABI 的 APK 体积 +15~25MB。
- **新版 Xray-core 兼容性**：升级 libv2ray 版本时若 config load 报错，先查是否有字段被上游删除（`allowInsecure` 就是这么踩过的）。
- **测速耗时**：快速通道命中时冷启动仅 ~2s；完整流程下 Phase 2 有早停（3 个节点 ≤ 500ms 即退出，60s 封顶），通常 5~15s；120s 看门狗兜底。
- **本机构建环境**（与代码整合无关，仅记录）：`local.properties` 指向 Homebrew 的 `sdk.dir=/opt/homebrew/share/android-commandlinetools`；`~/.gradle/init.gradle` 补过 `gradlePluginPortal()`。
- 网络测试时若 Mac 上跑着 Clash 类代理（fake-ip 模式）会污染结果，先退出再测。
