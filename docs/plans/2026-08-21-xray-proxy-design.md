# SmartTube 内置 Xray 代理设计

日期：2026-08-21

## 目标

在 SmartTube 内集成 Xray-core，使 App 全部流量（YouTube API、ExoPlayer 视频流）经由用户自选的代理节点出站，无需外部 VPN/代理 App。

## 总体方案

Xray-core 以 **隐藏本地 SOCKS 代理** 模式运行（监听 `127.0.0.1:10808`，无 VpnService、无授权弹窗），App 通过现有 `ProxyManager` 把 JVM 系统属性 `socksProxyHost/Port` 指向该端口，OkHttp / HttpURLConnection 流量自动进入 Xray。

节点配置来源：用户输入 Clash 格式订阅 YAML 的 URL，App 拉取解析、对每个节点做真实延迟测试，UI 展示结果并高亮最快节点，用户确认选定。

## 模块划分

### `:xraycore`（新建 wrapper 模块，位于 `SharedModules/xraycore/`）

- 来源：AndroidLibXrayLite 官方预编译 `libv2ray.aar`（GitHub Releases，约 59MB）。
- Android library 模块不支持本地 aar 文件依赖（见 `youtubeapi/build.gradle:92` 注释），故**解包** aar（仿 `:j2v8` 先例）：
  - `classes.jar` → `xraycore/libs/`（Java 绑定，包 `libv2ray`、`go.Seq`）
  - `jni/{armeabi-v7a,arm64-v8a,x86,x86_64}/libgojni.so` → `src/main/jniLibs/`
  - `assets/geoip.dat`、`geosite.dat` → `src/main/assets/`（并入 APK，Xray 可直接读取）
- `common` 依赖 `project(':xraycore')`。
- aar/so/dat 二进制不入 git，`download_xray.sh` 脚本按版本号拉取并解包。

### XrayManager（`common/.../proxy/xray/XrayManager.java`）

- 初始化（每进程一次）：`go.Seq.setContext(ctx)`、`Libv2ray.initCoreEnv(assetPath, key)`。
- 运行：`Libv2ray.newCoreController(callback).startLoop(fullConfigJson, 0)`（tunFd=0 纯代理模式）。
- 完整 config = socks inbound(`127.0.0.1:10808`) + 选中节点 outbound + freedom 直连兜底。
- 测速：静态 `Libv2ray.measureOutboundDelay(trimmedConfigJson, "https://www.gstatic.com/generate_204")`，内部起临时 core 拨号测 RTT（v2rayNG 同款）。trimmed config 去掉 inbound/routing/dns。

### 订阅解析（`common/.../proxy/xray/`）

- 新增依赖 `org.yaml:snakeyaml`（纯 Java，兼容 minSdk 17）。
- 下载并解析 Clash YAML 的 `proxies` 列表。
- 转换器把 vmess / vless / ss / trojan 节点映射为 Xray outbound JSON（含 ws/grpc/tcp、tls/reality streamSettings）。首版只支持这四种协议，不认识的节点跳过。

### 流量接管

- 端口就绪后复用 `ProxyManager`：写入 `socks://127.0.0.1:10808` 并 `configureSystemProxy()`。
- Xray 开启时强制播放器数据源为 `PLAYER_DATA_SOURCE_OKHTTP`（Cronet 不读 JVM 代理属性，会绕过 Xray；与现有 web 代理开关 `GeneralSettingsPresenter.java:652` 同样处理）。
- 切换开关后 `OkHttpManager.unhold()` 重建客户端。

### 启动顺序（自动模式，最终版）

启动时无需用户操作，由 `XrayBootstrap` 自动完成：

0. **快速通道**：若上次启动已选定节点（`xraySelectedOutbound` 缓存非空），跳过 Phase 1/2，先用临时 core 对该节点 `measureOutboundDelay` 实测纯 RTT（目标 `youtube.com/generate_204`）；可达且 ≤ 500ms 即起完整 core 直接使用（约 1~2s），失败或超时则回退完整流程（覆盖节点 uuid/端口轮换）
1. Phase 1：TCP ping 全部节点（内置 asset 或自定义订阅，20 并发，3s 超时）
2. Phase 2：对 ping 最快的前 60 个节点用临时 core 实测真实延迟（12 并发，测速目标即 `youtube.com/generate_204`，实测结果同时是 YouTube 可达性验证）；按上次实测延迟（`xray_node_delays` 缓存）排序优先测好节点，一旦有 3 个节点实测 ≤ 500ms 即取消剩余测量提前进入 Phase 3，整体 60s 封顶；实测节点按真实延迟排在候选列表前部
3. Phase 3：候选列表头部节点启动完整 core（本地 SOCKS 10808），OkHttp 经代理验证 `https://www.youtube.com/generate_204`，失败则尝试后续节点（最多 3 个）
4. 成功后应用代理并进入主界面

`SplashPresenter.initProxy()` 启动检测，主界面入口（`applyNewIntent`）被挂起直到检测结束，看门狗 90s 超时强制放行；手动 web 代理优先于 Xray 自动模式。代理通过 `ProxyManager.configureProxy()` 以**纯内存方式**应用，不污染手动 web 代理的持久化配置。

### 设置 UI

`GeneralSettingsPresenter.appendInternetCensorship()` 下的「内置代理 (Xray)」区只保留两个手动覆盖项（无开关）：

- 「订阅地址」输入项（默认内置节点列表）
- 「选择节点」项：两阶段测速后按延迟排序的单选对话框，选中立即重启 core 切换

状态持久化到 `AppPrefs`：订阅 URL、选中节点名、选中节点 outbound JSON、xray 激活标志。

## 风险与限制

- APK 体积 +15~25MB/ABI（Xray core 本体大小，ABI 分包可缓解）。
- 订阅格式兼容：各种魔改 Clash 配置只保证四种主流协议。
- `measureOutboundDelay` 起临时 core，批量测速时内存翻倍，需限并发。
- minSdk 17 项目 vs aar 官方 gomobile 构建（androidapi 24）：运行时需验证低版本设备加载 `libgojni.so` 是否正常；如有问题，stfdroid flavor(minSdk 21) 及主流 TV 设备(>=21)不受影响，必要时将 Xray 功能限制在 API 21+。

## 实施顺序

1. `:xraycore` 模块 + 依赖接线
2. XrayManager + 硬编码节点最小闭环（验证代理生效）
3. 订阅解析与节点转换
4. 设置 UI（开关/订阅/测速选择）
5. 启动顺序与编译验证
