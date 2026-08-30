# Xray 冷启动检测优化 — 任务总结

日期:2026-08-23~24
分支:主仓库 `xray-proxy`(SharedModules 子模块本轮无改动)

## 目标

缩短冷启动三阶段检测耗时(优化前典型 40~60s,Phase 2 实测 60 节点是主要瓶颈)。

## 实施的优化

### P0:缓存节点快速通道

- 冷启动时若 `xrayEnabled` 且 `xraySelectedOutbound` 缓存非空,跳过订阅加载与 Phase 1/2。
- 先用临时 core 对缓存节点 `measureOutboundDelay` 实测纯 RTT(目标 `youtube.com/generate_204`);
  可达且 ≤ 500ms 则直接起完整 core 使用;失败或超时回退完整三阶段流程(覆盖节点 uuid/端口轮换)。
- 判定方式经历过一次修正:最初用完整 core + OkHttp 计时(含 SOCKS/TLS 冷启动开销,实测 622~946ms,
  500ms 阈值几乎不可能命中),后改为 `measureOutboundDelay` 纯 RTT,阈值恢复有效。

### P1:Phase 2 流式早停 + 测速目标合并

- `measureRealDelay()` 改用 `ExecutorCompletionService` 按完成顺序收结果:
  3 个节点实测 ≤ 500ms 即取消剩余测量,整体 60s 封顶。
- 测量顺序用上次实测延迟(新增 `xray_node_delays` prefs 缓存)做初排,好节点优先被测,测完写回。
- 早停后候选列表重排:已测节点按真实延迟在前,未测节点按 TCP ping 在后。
- `XrayManager.DELAY_TEST_URL` 由 gstatic 改为 `youtube.com/generate_204`,
  Phase 2 实测同时即 YouTube 可达性验证(消灭"gstatic 通但 YouTube 被拦"的失败模式)。

## 改动文件

- `common/.../proxy/xray/XrayBootstrap.java` — 快速通道 `tryCachedNode()`、`checkYouTube()` 返回延迟、
  `measureRealDelay()` 早停重写、`readLastDelays/writeLastDelays`
- `common/.../proxy/xray/XrayManager.java` — `DELAY_TEST_URL` 改为 YouTube
- `common/.../prefs/AppPrefs.java` — 新增 `xray_node_delays` 键
- `common/src/main/res/values/strings.xml` + `values-zh/strings.xml` — 新增 `xray_checking_last_node`
- `docs/plans/2026-08-21-xray-proxy-design.md`、`2026-08-23-xray-integration-guide.md` — 同步流程描述

## 测试结果(test_tv 模拟器,API 30 arm64)

| 轮次 | 场景 | 结果 | 冷启动到主界面 |
|---|---|---|---|
| 1 | 无缓存完整流程 | Phase 1: 105/113;Phase 2 早停 0.8s;Phase 3 首节点通过 | ~5s |
| 2 | 快速通道(OkHttp 计时版) | 946ms > 500ms,正确回退 | ~9s |
| 3 | 快速通道(OkHttp 计时版) | 622ms > 500ms,再次回退 → 暴露阈值问题 | ~8.6s |
| 4 | 改纯 RTT 后,快速通道 | 326ms ≤ 500ms,Fast path OK | **~2.3s** |
| 5 | 改纯 RTT 后,快速通道 | 1448ms > 500ms,正确回退,Phase 3 选回同一节点 | ~15s |

结论:两条路径行为均正确。快速通道命中时冷启动 ~2.3s;回退时完整流程 ~9~15s(早停生效)。

已知现象:
- 节点 RTT 有波动(同一节点 326ms ↔ 1448ms),快速通道偶发"误伤"回退,代价是多花 ~10s 且常选回同一节点;当前保守策略可接受。
- 早停后被取消的在途测量会在 logcat 留下 `Delay measure failed ... closed pipe` 噪音,无害。

## 提交状态

- 提交 `3e09f0dbd` "Speed up Xray startup detection: cached-node fast path + Phase 2 early exit"
- Tag:`xray-proxy-v1.1`(初版实现 `d1b6db295` 视为 v1.0)
- 未 push;单元测试 `ClashConfigParserTest` 通过;`:smarttubetv:assembleStstableDebug` 构建通过

## 测试环境注意事项

- 测试前必须退出 Mac 上的 FlClash(TUN/fake-ip 会污染结果),测完恢复。
- 模拟器启动:`emulator -avd test_tv -no-snapshot-load -no-boot-anim`(日志重定向到文件,勿接管道)。
- 冷启动验证:`pm clear` 后 `am start` SplashActivity,logcat 过滤 `XrayBootstrap|XrayManager`。

## 后续可选事项

- 快速通道失败判定可改为"测 2 次取最好成绩"再决定回退,降低瞬时抖动误伤。
- P2 待办(未实施):订阅下载落盘缓存不阻塞 Phase 1;Phase 1 超时 3s→1.5s、并发 20→32;`REAL_TEST_TOP_N` 60→25~30。
