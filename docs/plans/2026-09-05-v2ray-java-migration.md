# 纯 Java v2ray 核心（v2ray-java）迁移与构建指南

日期：2026-09-05
分支：主仓库 `xray-proxy`(tag `v2ray-java.38`)；SharedModules 子模块 `xray-proxy`

## 概述

SmartTube 内置代理的核心从 **gomobile 编译的 Go Xray-core(libv2ray.aar)** 替换为
**纯 Java 实现 v2ray-java**（位于主仓库 `v2ray/` 目录，Maven 多模块，Netty 4.1 + Jackson + BouncyCastle)。

收益：

- 无 native `.so`,APK 每架构体积减少约 12MB(universal 减少约 24MB)
- 不再受 gomobile `libgojni.so` 的 androidapi 24 限制（纯 Java，门槛降至 API 21)
- 无需 geoip.dat / geosite.dat 数据文件（我们的配置只用 freedom 直连兜底，不走规则路由）
- 核心协议栈可读可调试，slf4j 日志直达 logcat

支持协议与 Go 版对齐：VMess(AEAD)/VLESS/Trojan/Shadowsocks、WebSocket 传输、SOCKS5/HTTP 入站、路由引擎。当前订阅节点为 VMess 裸 TCP，已实测全通。

## 完整构建流程（从零克隆）

```bash
git clone -b xray-proxy --recursive https://github.com/lawwhites/SmartTube.git
cd SmartTube

# 构建 v2ray-java fat jar 并拉取 R8 override（JDK 11+ 与 Maven 必需）
SharedModules/xraycore/build_v2ray.sh

# 构建 APK
./gradlew :smarttubetv:assembleStstableRelease   # release（自签）
./gradlew :smarttubetv:assembleStstableDebug     # debug
```

`build_v2ray.sh` 做的事：

1. `cd v2ray && mvn clean package`(**强制 JAVA_HOME 指向 JDK 11**，原因见下）
2. 把 `v2ray-app` 的 shaded fat jar 拷贝到 `SharedModules/xraycore/libs/v2ray-java.jar`，并剥离：
   - `META-INF/versions/**`（多版本条目，Jetifier/D8 不支持）
   - 签名文件（`*.SF/RSA/DSA/EC`)
   - `org/slf4j/**`、`ch/qos/logback/**`(App 已有 slf4j-api 1.7.25，避免类冲突；v2ray-java 只用到 1.7 兼容的基础 Logger API)
3. 若 `gradle/r8-8.3.37.jar` 不存在则从 dl.google.com 下载（见"构建链"一节）

### fat jar 内容构成（剥离后）

| 依赖 | 用途 | 未压缩体积 |
|---|---|---|
| netty | 网络引擎（事件循环/Channel) | ~8M |
| bouncycastle | SHAKE128 掩码等加密原语 | ~8M |
| jackson | 解析 v2ray JSON 配置 | ~4.9M |
| v2ray-java 本体 | 协议栈与核心 | ~0.24M |

剥离掉的部分：slf4j/logback（与 App 已有 slf4j-api 1.7.25 冲突）、byte-buddy
(~8.4M,netty-all 的可选传递依赖，运行时不用）、`META-INF/native/**`(netty 的
epoll/kqueue 原生库，Android 上走纯 NIO)、多版本条目与签名文件。

release 构建里 R8 会进一步裁掉未引用类。最终 arm64 release APK 约 **33M**,
比 Go AAR 版（46M）小约 13M。

大文件均不入 git（fat jar、R8 jar),`.gitignore` 已配置。

## 对 v2ray-java 源码的互通性修复（重要）

原始 v2ray-java 的测试全是 Java↔Java 自洽回环，**与真实 Go 服务端（Xray 26.x / huojian 生产节点）不互通**。以下 5 处修复均已对照 [Xray-core 源码](https://github.com/XTLS/Xray-core)逐一验证：

| # | 文件 | 问题 | 修复 |
|---|---|---|---|
| 1 | `VMessChunkCodec` | 分块长度帧未包含 16 字节 GCM tag(Go:`encryptedSize = payload + auth.Overhead()`),chunk 解密全部 tag mismatch | 长度字段 = 明文 + 16；解码按线长直读 |
| 2 | `PendingBufferHandler` | flush 后不从 pipeline 移除自己，持续吞掉握手后所有客户端数据（vmess/trojan/shadowsocks/freedom 全部中招） | flush 时自动摘除 |
| 3 | `VmessOutboundHandler` | 等响应头校验后才转发客户端数据；而 Xray 服务端是惰性写响应头（首批下行数据才 flush)→ 客户端先发言的协议（TLS）死锁 | 连接成功即转发，响应头异步校验 |
| 4 | `VMessChunkCodec` 解码器 | SHAKE128 掩码流不可回退：分块跨 TCP 段时重读导致多消耗掩码字节，第二个 chunk 起全部错位 | 掩码字节缓存到当前块完成 |
| 5 | `Socks5InboundHandler` | SOCKS5 成功应答按 RFC 回显域名型地址；Android libcore 的 SocksSocketImpl 按 IPv4 定长读取，残留 7 字节被 Conscrypt 当成 TLS 记录 → "Unable to parse TLS packet header",OkHttp 全灭 | 应答固定 `0.0.0.0:0`（与 Go xray 一致） |

调试方法备忘（可复用）：本机跑真 Xray 二进制做服务端看握手日志；怀疑链路被污染时检查残留 TUN(`netstat -rn | grep 198.18`,FlClash 的 fake-ip 段）；响应字节级验证用手写 SOCKS5 握手探针（`XrayNodeConnectionTest#testRawSocksProbe`)。

## Android 侧改造

- `XrayManager` 重写：`Libv2ray/CoreController/go.Seq` → `ConfigLoader.load` + `V2RayInstance.start()/close()`，公开接口（`startSync/stop/isRunning/measureNodeDelay/isSupported`）不变，调用方（XrayBootstrap 等）零改动。
  - `measureNodeDelay` = 临时实例（独立 SOCKS 端口 23200+)+ OkHttp 计时 `youtube.com/generate_204`。
  - `isSupported()` 门槛从 API 24(gomobile）降为 API 21。
- `common/src/main/java/org/slf4j/impl/`:slf4j 1.7 → logcat 绑定（v2ray-java 内部日志在 logcat 可见）。
- 快速通道/早停阈值按新测量口径重校准：Java 版测量是完整 HTTPS 请求（SOCKS+VMess 握手+TLS+HTTP)，约为 gomobile 纯 RTT 的 3~4 倍：`FAST_PATH_MAX_DELAY_MS` 500→2000,`MEASURE_EARLY_EXIT_DELAY_MS` 500→1500。

## 构建链注意事项（坑都已踩过）

1. **JDK 版本**：必须用 **JDK 11** 编译 v2ray-java。JDK 21+ 的 javac 会把枚举编译成 `$values()` 模式，AGP 自带 D8(4.0.52）和 8.2 都会 NPE。`build_v2ray.sh` 已自动处理。
2. **R8 override**：根 `build.gradle` 用 `classpath files('gradle/r8-8.3.37.jar')` 锁定新版 R8。该 jar 不入 git，由 `build_v2ray.sh` 拉取（Gradle 的 JVM 在本网络访问 dl.google.com TLS 握手失败，只能 curl)。
3. **fat jar 清理**:BouncyCastle 的多版本条目（Java 21 类）会让 Jetifier 崩；slf4j 与 App 已有依赖冲突——都在拷贝时剥离。
4. 发布版构建走 R8 全量压缩，`SharedModules/xraycore/proguard-v2ray.txt` 保留 com.v2ray/netty/jackson/bouncycastle。

## 实测结果（模拟器 test_tv,API 30)

- 冷启动完整流程：Phase 1 全通(196/196)→ Phase 2 实测 59/60 可用（早停阈值内 3 节点即退出）→ Phase 3 通过，全程 ~4~10s
- 快速通道：缓存节点 737ms ≤ 2000ms，命中秒进
- 播放：视频正常渲染（SABR/DASH 均验证）,generate_204 经节点返回 204
- release(R8 全量压缩）与 debug 均端到端验证通过

## 已知限制

- 无 REALITY/uTLS 指纹伪装（Java TLS 栈限制）;huojian 节点为 VMess+TCP，不受影响，但若订阅方未来切换 REALITY 需另评估。
- v2ray-java 全量 `mvn test` 在同一 JVM 内顺序跑时 3 个 E2E 用例偶发超时（测试框架端口/线程争用，与功能无关；单独运行全过）。
- 节点延迟波动较大时快速通道可能误回退完整流程（多花 ~10s)，行为保守可接受。
