# 在原始 SmartTube 上复现 Xray 内置代理 — 分阶段实施提示词

> 用法：准备一份**未修改的 SmartTube 源码**（含 MediaServiceCore、SharedModules 子模块），按 Prompt 0→5 顺序喂给编码代理，每个 Prompt 通过验收后再进入下一个。
> 全部业务代码集中在 `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/xray/` 包内，对上游文件的侵入点最小化。
> 设计依据：`2026-08-21-xray-proxy-design.md`、`2026-08-23-xray-integration-guide.md`、`2026-08-24-xray-startup-optimization-task.md`、仓库根目录 `clash_doh_detection.md`。

## 通用约束（每个 Prompt 都附带）

```text
通用约束：
- 项目是 SmartTube（Android TV 应用，Gradle 多模块，minSdk 17，Java 8）。只做本阶段要求的改动，不顺手重构无关代码。
- 注释风格与周围代码一致（英文、简洁）。
- 每个阶段结束必须能编译：`./gradlew :smarttubetv:assembleStstableDebug`。
- 二进制产物（aar/so/dat）不入 git，加入 .gitignore。
```

---

## Prompt 0：xraycore wrapper 模块（Go 核心接入）

```text
在 SmartTube 项目中新增一个 Android library 模块 :xraycore，封装 Xray-core 的 gomobile 绑定。

背景：Android library 模块不能直接依赖本地 aar 文件，所以要把官方预编译 aar 解包成 jar + jniLibs + assets 的形式组织（项目里 :j2v8 模块有同样做法可参考）。

要求：
1. 在 SharedModules 子模块内新建 xraycore/ 目录，包含：
   - download_xray.sh：从 https://github.com/2dust/AndroidLibXrayLite/releases 下载指定版本（默认 v26.8.20）的 libv2ray.aar，解包后：
     classes.jar → libs/libv2ray-classes.jar（Java 绑定，包名 libv2ray、go.Seq）
     jni/{armeabi-v7a,arm64-v8a,x86,x86_64}/libgojni.so → src/main/jniLibs/
     assets/geoip.dat、geosite.dat → src/main/assets/
     proguard.txt → proguard-libv2ray.txt
   - build.gradle：com.android.library，api files('libs/libv2ray-classes.jar')，consumerProguardFiles 指向上面的 proguard 文件
   - .gitignore 忽略 libs/*.jar、src/main/jniLibs、src/main/assets、下载的 aar
2. SharedModules/core_settings.gradle 中 include ':xraycore' 并指向该目录。
3. common/build.gradle 增加 implementation project(':xraycore')。
4. 运行 download_xray.sh 拉取二进制，编译通过。

验收：模块参与构建；在 common 模块里能 import libv2ray.Libv2ray 和 go.Seq。
```

---

## Prompt 1：XrayManager — core 生命周期最小闭环

```text
实现 Xray 核心的进程内管理器，目标：用硬编码节点验证"本地 SOCKS 代理"闭环能跑通。

新建 common/src/main/java/com/liskovsoft/smartyoutubetv2/common/proxy/xray/XrayManager.java：
1. 单例。常量 LOCAL_HOST=127.0.0.1、LOCAL_PORT=10808、DELAY_TEST_URL="https://www.youtube.com/generate_204"。
2. ensureEnv()（每进程一次，任何 native 调用前必须先调）：
   go.Seq.setContext(context)；在 filesDir 下建 xray/ 目录；Libv2ray.initCoreEnv(assetDir, "")。
3. isSupported()：Build.VERSION.SDK_INT >= 24（官方 libv2ray aar 用 gomobile -androidapi 24 构建）。
4. startSync(outboundJson)：Libv2ray.newCoreController(callback).startLoop(fullConfig, 0)。
   - tunFd 传 0：纯代理模式，不用 VpnService。
   - fullConfig JSON = socks inbound（127.0.0.1:10808, auth noauth, udp true）+ 选中节点 outbound + freedom 直连兜底 outbound，log loglevel=warning。
5. startAsync(outboundJson, onStarted)：后台线程 startSync + waitForPort(10s)（轮询 Socket 连接 10808），成功回主线程回调。
6. stop()：stopLoop 并置空 controller，异常只记日志。
7. 静态 measureNodeDelay(ProxyNode)：Libv2ray.measureOutboundDelay(trimmedConfig, DELAY_TEST_URL)，
   trimmed config 只含 log + outbounds（无 inbound/routing/dns）。返回 RTT ms，失败返回负数。
8. isRunning() 判空 + getIsRunning，包 try/catch。

然后做最小验证：在任意调试入口用一个 vmess 节点 outbound JSON 调 startSync，设备上 curl --socks5 127.0.0.1:10808 https://www.youtube.com/generate_204 应返回 204。

验收：SOCKS 闭环打通；不接入任何 UI。
```

---

## Prompt 2：订阅解析、协议转换与 DoH 域名解析

```text
实现 Clash 订阅的下载、解析、协议转换，以及域名节点的 DoH 解析。

1. common/build.gradle 增加 implementation 'org.yaml:snakeyaml:1.33'（纯 Java，兼容 minSdk 17）。

2. 新建 proxy/xray/ProxyNode.java：节点数据模型（name、server、port、type、outbound JSONObject、
   tcpPing、realDelay 字段），提供 withServer(String newIp) 生成替换 server 的变体副本。

3. 新建 proxy/xray/ClashConfigParser.java：Clash YAML → List<ProxyNode>。
   - 解析 proxies 列表，只支持 vmess / vless / ss / trojan 四种协议，不认识的跳过。
   - 每种协议映射为 Xray outbound JSON（含 ws/grpc/tcp 传输与 tls/reality streamSettings）。
   - 两个必须避开的坑：
     a) trojan 是隐含 TLS，必须强制输出 tls: true，否则 handshake failure；
     b) 新版 Xray-core 已删除 allowInsecure 字段，生成的 JSON 绝不能包含它，否则 config load error。

4. 新建 proxy/xray/DohResolver.java：域名节点的 DoH 解析（参考仓库根目录 clash_doh_detection.md 的方法）。
   - 当节点 server 是域名（非 IP）时启用；server 已是 IP 的订阅自动跳过。
   - DoH 服务器来源：优先从配置 dns.proxy-server-nameserver 段提取（服务方健康路由 DoH，返回当前可用 IP）；
     配置没有该段时回退到内置 FALLBACK_DOH_SERVERS（3 个 huojian DoH 端点，
     形如 https://20.247.42.211:36290/dns-query/clash?site=huojian）。
   - 查询用 RFC 8484 wire format（GET ?dns=<base64url>，Accept: application/dns-message），
     不依赖任何第三方 DNS 库。注意不能用公开 DoH（dns.google/cloudflare 在目标网络不可达）。
   - 每个唯一域名查 2 轮 × 全部 DoH 服务器，合并去重成候选 IP 池（池随健康路由动态变化），
     8 线程并发，整体 30s 封顶。
   - 每个域名节点展开为"每候选 IP 一个变体"；DoH 全部失败时节点原样保留（系统 DNS 兜底）。

5. 单元测试 common/src/test/java/.../proxy/xray/ClashConfigParserTest.java（Robolectric）：
   覆盖四种协议转换、trojan 强制 tls、无 allowInsecure 字段、畸形节点跳过。

验收：./gradlew :common:testDebugUnitTest 通过。
```

---

## Prompt 3：启动自动检测（快速通道 + 三阶段）与流量接管

```text
实现冷启动时无需用户操作的全自动节点检测，并把全 App 流量导入 Xray。

1. 新建 proxy/xray/XrayNodeSelector.java：
   - DEFAULT_SUB_URL 常量（默认订阅地址）。
   - loadSubscription() 订阅加载公共入口，顺序：用户自定义 URL（AppPrefs）→ DEFAULT_SUB_URL →
     APK 内置 asset 兜底 common/src/main/assets/xray_builtin_sub.yaml。
   - 加载后接 DoH 展开（Prompt 2 的 DohResolver）。

2. 新建 proxy/xray/XrayBootstrap.java，冷启动自动执行（全程带 onProgress 回调上报状态文字）：
   - Phase 0 快速通道：若 AppPrefs 的 xrayEnabled 且 xraySelectedOutbound 缓存非空，跳过订阅加载和
     Phase 1/2，先对缓存节点 measureOutboundDelay 实测纯 RTT（目标 youtube.com/generate_204）；
     可达且 ≤ 500ms 直接起完整 core 使用（约 2s）；失败或超时回退完整流程（覆盖节点 uuid/端口轮换）。
     注意：判定必须用 measureOutboundDelay 纯 RTT，不能用"完整 core + OkHttp 计时"
     （含 SOCKS/TLS 冷启动开销，实测 622~946ms，500ms 阈值永远不会命中——这是踩过的坑）。
   - Phase 1：TCP ping 全部节点（含 DoH 展开的 IP 变体），20 并发，3s 超时；
     ping 完按节点名去重，同名只留最快变体。
   - Phase 2：ping 最快的前 60 个节点用 measureOutboundDelay 实测，12 并发，60s 封顶；
     用 ExecutorCompletionService 按完成顺序收结果，3 个节点 ≤ 500ms 即取消剩余测量提前退出；
     测量顺序按上次实测延迟缓存（AppPrefs xray_node_delays）初排，好节点优先，测完写回；
     早停后候选列表重排：已测节点按真实延迟在前，未测节点按 TCP ping 在后。
   - Phase 3：候选列表头部节点 startSync 起完整 core，OkHttp 经 127.0.0.1:10808 代理验证
     https://www.youtube.com/generate_204，失败顺移下一节点（最多 3 个）。
   - 成功：缓存选中节点（名 + outbound JSON）到 AppPrefs，应用代理；失败：直连放行。

3. 流量接管：
   - common/.../proxy/ProxyManager.java 新增 configureProxy(Proxy) 方法：只写 JVM 系统属性
     （socksProxyHost/Port）并 configureSystemProxy()，纯内存、不写持久化 prefs
     （避免污染用户手动 web 代理设置）。
   - Xray 激活时强制播放器数据源为 PLAYER_DATA_SOURCE_OKHTTP（Cronet 不读 JVM 代理属性会绕过 Xray；
     参照 GeneralSettingsPresenter 里手动 web 代理开关的同样处理），切换后 OkHttpManager.unhold() 重建客户端。

4. 启动流程挂起（关键，改动上游文件）：
   - common/.../app/presenters/SplashPresenter.java：initProxy() 启动 XrayBootstrap；
     mXrayBootstrapPending 标志挂起 applyNewIntent（主界面入口）直到检测结束；
     continueStartup() 在检测期间暂停启动其他 Activity——SplashActivity 是 singleInstance，
     任何其他 Activity 启动都会把它挤到后台回桌面，检测就 invisible 了；
     mXrayWatchdog 120s 看门狗超时强制放行。
   - common/.../app/views/SplashView.java 接口新增 updateStatus(CharSequence)。
   - smarttubetv/.../tv/ui/main/SplashActivity.java：setContentView 到新布局
     smarttubetv/src/main/res/layout/activity_splash.xml（logo + ProgressBar + 状态文字），
     实现 updateStatus() 实时显示检测进度。
   - 手动 web 代理优先于 Xray 自动模式：用户已配置手动代理时跳过 bootstrap。

5. common/.../prefs/AppPrefs.java 新增键：xrayEnabled、xraySelectedNodeName、xraySelectedOutbound、
   xray_node_delays。strings.xml（values 和 values-zh）新增 xray_* 系列字符串。

验收：模拟器 pm clear 后冷启动，logcat 过滤 XrayBootstrap 能看到各阶段日志；
有缓存时 ~2s 进主界面；无缓存完整流程 5~15s；看门狗 120s 兜底。
```

---

## Prompt 4：设置页 UI

```text
在设置页提供手动覆盖项。

common/.../app/presenters/settings/GeneralSettingsPresenter.java 的 appendInternetCensorship() 下
新增「内置代理 (Xray)」区，只两个选项（无开关）：
1. 「订阅地址」：SimpleEditDialog 输入自定义 Clash 订阅 URL，存 AppPrefs，留空恢复默认。
2. 「选择节点」：触发两阶段测速（TCP ping → measureOutboundDelay 实测），
   结果按延迟排序弹单选对话框，高亮最快节点；选中立即 startSync 重启 core 切换并更新 AppPrefs 缓存。

坑：弹 SimpleEditDialog 前必须先 closeDialog()，否则在 AppDialog 独立 Activity 场景下 BadToken 崩溃。

验收：设置页两项可用；手动切换节点后播放流量走新节点。
```

---

## Prompt 5：真机连通性仪器测试与整体验证

```text
补齐自动化验证。

1. common/build.gradle 增加 androidTest 依赖（junit、androidx.test runner/rules）。
2. common/src/androidTest/AndroidManifest.xml。
3. 新建 common/src/androidTest/java/.../proxy/xray/XrayNodeConnectionTest.java：
   真机/模拟器上跑完整三阶段：加载订阅（参数化，支持内置 asset 和外部 yaml 文件两种来源）→
   DoH 展开 → TCP ping → measureOutboundDelay 实测 → 起完整 core 验证 YouTube generate_204。
   断言每阶段有可用节点、最终 YouTube 204。
4. 测试资产 common/src/androidTest/assets/clash_config.yaml（一份真实订阅配置）。

运行注意（写进测试注释）：
- 跑仪器测试前先 am force-stop 主 App，否则 App 的 Xray core 占着 10808 端口导致测试 Phase 3 bind 失败。
- 测试机上退出其他代理/VPN 软件（TUN/fake-ip 模式会污染结果造成假阴性）。

验收：./gradlew :common:connectedDebugAndroidTest 中该测试类全部通过；随后手动冷启动 App 验证播放正常。
```

---

## 复现完成后的对照清单

- [ ] `SharedModules/xraycore/` 模块 + `download_xray.sh`，二进制 gitignore
- [ ] `proxy/xray/` 6 个类：XrayManager / ClashConfigParser / DohResolver / XrayNodeSelector / XrayBootstrap / ProxyNode
- [ ] 快速通道（measureOutboundDelay 纯 RTT 判定，≤500ms）+ 三阶段 + 120s 看门狗
- [ ] Phase 2 早停（3 个 ≤500ms 即退出，60s 封顶）+ `xray_node_delays` 缓存排序
- [ ] DoH：配置提取 → FALLBACK_DOH_SERVERS 兜底；RFC 8484；每 IP 变体展开 + Phase 1 后按名去重
- [ ] ProxyManager.configureProxy 纯内存应用；强制 OkHttp 数据源
- [ ] Splash 挂起逻辑（singleInstance 陷阱）+ activity_splash.xml + updateStatus
- [ ] 设置页两项；SimpleEditDialog 前 closeDialog
- [ ] trojan 强制 tls、无 allowInsecure
- [ ] 单测 + 仪器测试通过；ststableDebug 构建通过
