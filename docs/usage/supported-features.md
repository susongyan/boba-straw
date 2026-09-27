# 版本能力表

适用 SDK：0.1.0-SNAPSHOT；仓库版以源码提交及工作树为准，离线 manifest 包尚未发布。
“基础已验证”不等于整个 v1 已完成或适合任意生产负载。

| 能力 | 当前边界 | 验证依据（仓库测试类） |
| --- | --- | --- |
| Standalone sync / CompletionStage | 基础已验证，Client 长期复用 | RedisCompatibilityTest |
| RESP2/RESP3、HELLO 协商、显式 RESP2 | 基础已验证 | BobaStrawProtocolNegotiationTest / RespCodecTest |
| 重连、取消、超时、容量、共享 Resources | 不自动重放命令，已写取消需排空 | BobaStrawConnectionLifecycleTest / BobaStrawClientResourcesTest |
| Pipeline | 真正批量写，保序但不原子；一次性 builder | NioConnectionIoTest / RedisCompatibilityTest |
| Pub/Sub | 异步订阅确认，close 发起退订；不是等待全部回调完成的同步屏障 | BobaStrawProtocolNegotiationTest |
| 事务 helper、懒加载池 | AutoCloseable；取消/失败销毁、成功归还；WATCH 冲突保留空列表兼容行为 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest |
| String / binary / Lua | 常用命令子集；binary 异步 GET/SET/DEL，新增 MGET/MSET/MSETNX、SET 选项、APPEND/STRLEN/GETRANGE/SETRANGE；不等于完整二进制接口 | RedisCompatibilityTest / RespCodecTest / BinaryStringCommandsTest / BinaryStringCompatibilityTest；见[命令覆盖](../implementation/command-coverage.md) |
| Cluster | 普通主节点命令：多 seed、Slot/Hash Tag、退避重连、周期/事件刷新、MOVED、独占 ASK、已知多 Key 校验；专用命令和生产长稳仍待完成 | ClusterSlotTest / ClusterLifecycleTest / opt-in ClusterIntegrationTest；详见核心收尾计划 |
| Sentinel | 普通 String Raw 命令、多 Sentinel 发现、独立认证、ROLE 校验、切换和退避重发现；专用组合待完成 | SentinelLifecycleTest / opt-in SentinelIntegrationTest；详见核心收尾计划 |
| TLS | 未实现，明确后置 | 无 |
| 阻塞命令专用管理 | Standalone 同步/异步 BLPOP、BRPOP；有界按需单次连接，更多阻塞命令待扩展 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest |
| Spring Boot | 基础单客户端配置；示例工程及 Boot 版本矩阵尚未验收 | 尚无 SpringContextTest，不作示例已验证声明 |
| Codec SPI / 完整命令 / Health / Metrics / 多客户端自动配置 | 未完成 | 不生成虚构 API |

Raw API 是未封装普通命令的出口，不是任意状态型命令安全执行的保证。
禁止通过共享 Raw/Pipeline 发起 MULTI、WATCH、SUBSCRIBE、SELECT 等改变连接状态的命令。
缺少合适公开接口时报告能力缺口，不绕过 internal 包。
