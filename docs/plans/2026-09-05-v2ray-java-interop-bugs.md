# v2ray-java 互通性 Bug 实录（桌面端实测驱动）

> 背景：v2ray-java（纯 Java 重实现的 Xray-core）的原有自测全是
> Java↔Java 自洽回环，以下问题一个都没暴露。全部用本机真实 Xray
> 服务端 + 真实 huojian 节点逐个逼出来，对照 Xray-core Go 源码修复。
> 第 5 个为 Android 端集成时发现。

## Bug 1：分块长度帧少算 16 字节 GCM tag

- **症状**：VMess AES-128-GCM 加密传输时分块长度与实际密文长度对不上，
  对端解码失败。
- **根因**：Go 源码中 `encryptedSize + paddingSize` 已包含 16 字节的
  GCM tag，Java 实现漏算了这部分。
- **修复**：对照 Xray-core `common/crypto/auth.go`
  （https://raw.githubusercontent.com/XTLS/Xray-core/main/common/crypto/auth.go）
  修正长度计算。

## Bug 2：PendingBufferHandler flush 后不从管道移除

- **症状**：连接建立后，后续客户端数据被持续吞掉，4 个出站协议
  （vmess/vless/ss/trojan）全部中招。
- **根因**：Netty pipeline 中的 PendingBufferHandler 在 flush 完成后
  仍挂在管道里，把之后的数据继续缓存而不转发。
- **修复**：flush 时自动将 handler 从 pipeline 摘除。

## Bug 3：等响应头再发客户端数据 → 死锁

- **症状**：连接建立后双方互等，永久挂起。
- **根因**：Xray 服务端采用惰性写响应头（buffered writer，首批下行
  数据到达时才 flush 响应头）；Java 实现却在收到响应头后才转发客户端
  数据——两边都在等对方先动。
- **修复**：改为连接成功立即转发客户端数据。对照
  `proxy/vmess/inbound/inbound.go` 的 transferResponse
  （https://raw.githubusercontent.com/XTLS/Xray-core/main/proxy/vmess/inbound/inbound.go）。

## Bug 4：SHAKE 掩码流不可回退，跨 TCP 段解码错位

- **症状**：数据分块跨 TCP 段到达时，第二个块起全部解码错误。
- **根因**：SHAKE128 掩码流是单向的、不可回退；解码器在数据不足时
  重读，多消耗了掩码字节，导致流位置错位。
- **修复**：掩码字节缓存到当前块完成，重读时先消费缓存。

## Bug 5：Android 端 SOCKS5 域名应答错误

- **症状**：Android 上应用经本地 SOCKS 入站访问域名目标失败。
- **根因**：SOCKS5 应答中域名类型地址的编码/处理与桌面 JVM 行为不一致。
- **修复**：修正 SOCKS5 应答的域名地址编码。

## 经验教训

1. **回环自测证明不了互通性**。纯 Java 实现的单元测试/集成测试若两端
   都是自己，系统性偏差（双方都错得一样）会被掩盖。必须对真实
   Xray-core（Go 原版）服务端做互操作测试。
2. **逐协议、逐场景逼**：4 个出站协议 + 分块跨段 + 惰性响应头，每个
   bug 都需要特定触发条件。
3. **修法以 Go 源码为准**：协议细节不要凭理解实现，对照
   Xray-core 源码逐字节对齐。
