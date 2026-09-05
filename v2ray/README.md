# V2Ray Java (v2ray-java)

基于 **Java 11+** 与 **Netty 4.1+** 从零构建的纯 Java 模块化工业级 V2Ray Core 核心实现。

---

## 1. 模块架构设计

本项目参考 Go 官方实现 `v2fly/v2ray-core` 的核心设计哲学，将其完全解耦并移植为符合现代 Java 规范的 Maven 多模块工程：

| 模块名称 | 功能职责 | 对齐 Go 核心包 |
| :--- | :--- | :--- |
| **`v2ray-common`** | 核心数据模型 (`Destination`, `Network`)、生命周期 (`Lifecycle`, `Feature`)、双向背压流控中继器 (`RelayHandler`, `PendingBufferHandler`)、统计计数器 (`Counter`, `StatsManager`, `TrafficStatsHandler`) 与 DNS 客户端 (`DnsClient`, `DefaultDnsClient`) | `common/net`, `common/buf`, `common/task`, `features/stats`, `features/dns` |
| **`v2ray-transport`**| 传输层设置与连接抽象 (`StreamSettings`)、WebSocket 传输框架 (`WebSocketFrameCodec`, `WebSocketClientTransport`, `WebSocketServerTransport`) | `transport/internet`, `transport/internet/websocket` |
| **`v2ray-router`**   | 规则路由引擎 (`Router`, `DefaultRouter`, `RoutingRule`)，支持 域名全匹配/后缀/关键字/正则匹配 (`full:`, `domain:`, `keyword:`, `regexp:`)、`geosite:cn`、`geosite:category-ads-all`、`geoip:private`、IPv4/IPv6 CIDR 子网掩码匹配、端口与入站 Tag 分流 | `app/router`, `features/routing` |
| **`v2ray-proxy`**    | 核心协议体系支持：<br>• **VMess AEAD** (`VMessKdf`, `VMessAead`, `VMessHeader`, `VMessChunkCodec`, `VmessInboundHandler`, `VmessOutboundHandler`)<br>• **Shadowsocks AEAD (SIP008)** (`ShadowsocksCrypto`, `ShadowsocksChunkCodec`, `ShadowsocksInboundHandler`, `ShadowsocksOutboundHandler`)<br>• **VLESS** (`VlessHeader`, `VlessInboundHandler`, `VlessOutboundHandler`)<br>• **Trojan** (`TrojanHeader`, `TrojanInboundHandler`, `TrojanOutboundHandler`)<br>• **Dokodemo-door** (`DokodemoInboundHandler`)<br>• **SOCKS5** (`Socks5InboundHandler`)<br>• **HTTP Proxy** (`HttpProxyInboundHandler`)<br>• **Freedom** (`FreedomOutboundHandler`)<br>• **Blackhole** (`BlackholeOutboundHandler`) | `proxy/vmess`, `proxy/shadowsocks`, `proxy/vless`, `proxy/trojan`, `proxy/dokodemo`, `proxy/socks`, `proxy/http`, `proxy/freedom`, `proxy/blackhole` |
| **`v2ray-core`**     | 运行容器 (`V2RayInstance`)、管理器 (`InboundManager`, `OutboundManager`)、调度中枢 (`DefaultDispatcher`) | `core.Instance`, `app/dispatcher` |
| **`v2ray-config`**   | 基于 Jackson 的标准 V2Ray `config.json` 解析与组件依赖注入组装器 (`ConfigLoader`) | `infra/conf` |
| **`v2ray-app`**      | CLI 命令行启动器 (`Main`)、参数解析 (`-c`, `-t`, `-v`, `-h`)、JVM 优雅停机 Hook 与全依赖 Shaded Fat Jar 打包 | `main` |

---

## 2. 协议与功能支持矩阵

| 协议 / 功能模块 | 入站 (Inbound) | 出站 (Outbound) | 核心特性与技术实现 |
| :--- | :---: | :---: | :--- |
| **VMess AEAD** | ✅ | ✅ | 递归 HMAC-SHA256 KDF 密钥派生、AES-128-ECB AuthID 鉴权校验、AEAD Header 加解密、SHAKE-128 长度掩码分块流、AES-128-GCM 载荷传输 |
| **Shadowsocks** | ✅ | ✅ | SIP008 AEAD 规范、HKDF-SHA1 子密钥派生、Little-Endian 自增 Nonce、AES-128-GCM / AES-256-GCM / ChaCha20-Poly1305 |
| **VLESS** | ✅ | ✅ | 官方标准协议格式、16 字节 UUID 校验、直接透明无加密流转发、TLS 握手整合 |
| **Trojan** | ✅ | ✅ | 56 字节 SHA-224 密码散列验证、CRLF 帧对齐、TLS 客户端握手 (SNI + InsecureTrust) 与服务端终止 |
| **Dokodemo-door** | ✅ | - | 任意门/透明端口转发，将任意 TCP 连接直接重定向到指定目标地址和端口 |
| **SOCKS5** | ✅ | ✅ | RFC 1928 认证协商、`CONNECT` 命令、IPv4/IPv6/域名解析目标 |
| **HTTP Proxy** | ✅ | - | RFC 7230/7231，支持 HTTPS `CONNECT` 隧道与普通 HTTP 明文代理 |
| **Freedom** | - | ✅ | 直连公网，带 `PendingBufferHandler` 连接建立期数据无损缓冲与流控 |
| **Blackhole** | - | ✅ | 阻断拦截，丢弃数据或优雅关闭连接 |
| **WebSocket** | ✅ | ✅ | 基于 Netty 原生 `WebSocketFrameCodec` 的二进制流帧封装与拆装 |
| **Router 分流** | 引擎 | 引擎 | `full:`, `domain:`, `keyword:`, `regexp:`, `geosite:cn`, `geosite:category-ads-all`, `geoip:private`, IPv4/IPv6 CIDR，预编译高性能缓存匹配 |
| **Stats 流量统计** | 监控 | 监控 | 基于原子无锁 `LongAdder` 的双向上行/下行实时流量统计 |

---

## 3. 快速开始与使用指南

### 3.1 编译与打包
```bash
cd v2ray-java
mvn clean package
```
打包成功后，将在 `v2ray-app/target/` 目录下生成可独立运行的 Shaded Fat Jar：`v2ray-app-1.0.0-SNAPSHOT.jar`。

### 3.2 命令行参数支持
```bash
# 查看帮助
java -jar v2ray-app/target/v2ray-app-1.0.0-SNAPSHOT.jar -h

# 查看版本
java -jar v2ray-app/target/v2ray-app-1.0.0-SNAPSHOT.jar -v

# 仅测试/校验配置文件语法
java -jar v2ray-app/target/v2ray-app-1.0.0-SNAPSHOT.jar -t -c config.json

# 启动核心服务
java -jar v2ray-app/target/v2ray-app-1.0.0-SNAPSHOT.jar -c config.json
```

### 3.3 验证连通性示例
如果配置了 SOCKS5 入站 `127.0.0.1:10808` 与 HTTP 入站 `127.0.0.1:10809`：
```bash
# 通过 SOCKS5 代理测试 YouTube
curl -x socks5h://127.0.0.1:10808 -I https://www.youtube.com

# 通过 HTTP 代理测试 YouTube
curl -x http://127.0.0.1:10809 -I https://www.youtube.com
```

---

## 4. 测试套件覆盖与结果

工程内置全量单元测试与端到端闭环集成测试，包含：
- `VMessKdfTest`（对齐 Go 官方测试向量）
- `VMessAeadTest`（AuthID 与 Header 加密解密往返）
- `VMessHeaderTest`（请求头序列化与反序列化）
- `VMessChunkCodecTest`（掩码/非掩码流分块编解码）
- `VmessEndToEndTest`（VMess 端到端闭环转发）
- `ShadowsocksCryptoTest`（AES-128-GCM, AES-256-GCM, ChaCha20-Poly1305）
- `ShadowsocksChunkCodecTest`（SIP008 分块编解码）
- `ShadowsocksEndToEndTest`（Shadowsocks 端到端闭环转发）
- `DokodemoInboundTest`（任意门端口转发）
- `VlessHeaderTest`（VLESS 帧解析）
- `TrojanHeaderTest`（Trojan SHA-224 鉴权）
- `RouterTest` & `IpCidrMatcherTest`（正则、域名、GeoIP、CIDR 匹配）
- `WebSocketTransportTest`（WebSocket 二进制流传输）
- `TrafficStatsHandlerTest` & `CounterTest`（流量统计）
- `ConfigLoaderTest`（多协议 JSON 装配）
- `ComprehensiveProtocolTest`（HTTP, VLESS, Trojan, VMess, Shadowsocks, Dokodemo 六大协议同实例全覆盖）
- `EndToEndProxyTest`（SOCKS5 代理闭环）

执行 `mvn test` 结果：
```
[INFO] Reactor Summary for v2ray-java 1.0.0-SNAPSHOT:
[INFO] v2ray-java ......................................... SUCCESS
[INFO] v2ray-common ....................................... SUCCESS
[INFO] v2ray-transport .................................... SUCCESS
[INFO] v2ray-router ....................................... SUCCESS
[INFO] v2ray-proxy ........................................ SUCCESS (21 tests)
[INFO] v2ray-core ......................................... SUCCESS
[INFO] v2ray-config ....................................... SUCCESS
[INFO] v2ray-app .......................................... SUCCESS (7 tests)
[INFO] BUILD SUCCESS
```

---

## 5. 移动端 (Android) 与生产级性能优化特性

本库针对 Android 操作系统（ART 运行时、ARM big.LITTLE 拓扑、电池功耗敏感、弱网抖动）进行了专项深度调优：

1. **Socket 写入系统调用合并 (Syscall Batching)**：
   - 在 [`RelayHandler`](file:///Users/linmingfeng/Project/v2ray/v2ray-java/v2ray-common/src/main/java/com/v2ray/common/relay/RelayHandler.java) 中实现读写分离：`channelRead` 触发 `targetChannel.write(msg)`，在 `channelReadComplete` 时批量执行 `targetChannel.flush()`。
   - 大幅减少频繁 `writev`/`send` 系统调用和内核上下文切换，降低 CPU 唤醒次数，显著提升吞吐量并延长手机电池续航。

2. **全局共享 EventLoopGroup 线程池**：
   - 彻底避免多入站独立创建线程池导致的线程膨胀（收敛为单 Boss 线程与 2~4 个 Worker 线程），保护 ARM 大小核调度；
   - 出站连接直接复用入站当前 EventLoop，实现真正意义上的**零跨线程上下文切换 (Zero Context Switch)**。

3. **原生 Native Transport 智能探测与安全回退**：
   - [`TransportHelper`](file:///Users/linmingfeng/Project/v2ray/v2ray-java/v2ray-common/src/main/java/com/v2ray/common/net/TransportHelper.java) 动态探测 Linux `Epoll` 与 macOS `KQueue`；
   - 在 Android ART 环境缺少 JNI `.so` 或 glibc 时进行异常拦截并安全平滑回退至纯 NIO，杜绝 ClassNotFound 或 Linkage 异常崩溃。

4. **零拷贝堆外直接内存加解密与 Cipher 实例复用**：
   - [`VMessChunkCodec`](file:///Users/linmingfeng/Project/v2ray/v2ray-java/v2ray-proxy/src/main/java/com/v2ray/proxy/vmess/VMessChunkCodec.java) 与 [`ShadowsocksChunkCodec`](file:///Users/linmingfeng/Project/v2ray/v2ray-java/v2ray-proxy/src/main/java/com/v2ray/proxy/shadowsocks/ShadowsocksChunkCodec.java) 会话级复用 `Cipher` 实例，消除每个 16KB 分块重复创建对象的开销；
   - 基于 `Cipher.doFinal(ByteBuffer, ByteBuffer)` 结合 Netty `ByteBuf.nioBuffer()` 实现端到端直接内存加解密，彻底消除中间 `byte[]` 堆分配与 Android 并发 GC 停顿。

5. **蜂窝移动网络参数与防溢出背压流控**：
   - 强制启用 `TCP_NODELAY`（消除移动网络 40ms Delayed ACK）；
   - 启用 `SO_KEEPALIVE`（维持移动基站 NAT 会话）；
   - 适配移动端 32KB/64KB 读写高低水位，自动响应网络切换引起的抖动与背压。

