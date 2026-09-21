# 版本能力表

适用 SDK：0.1.0-SNAPSHOT；实际源码提交以包内 manifest 为准。
“基础已验证”不等于整个 v1 已完成或适合任意生产负载。

| 能力 | 当前边界 | 验证依据（仓库测试类） |
| --- | --- | --- |
| Standalone sync / CompletionStage | 基础已验证，Client 长期复用 | RedisCompatibilityTest |
| RESP2/RESP3、HELLO 协商、显式 RESP2 | 基础已验证 | BobaStrawProtocolNegotiationTest / RespCodecTest |
| 重连、取消、超时、容量、共享 Resources | 不自动重放命令，已写取消需排空 | BobaStrawConnectionLifecycleTest / BobaStrawClientResourcesTest |
| Pipeline | 真正批量写，保序但不原子；一次性 builder | NioConnectionIoTest / RedisCompatibilityTest |
| Pub/Sub | 异步订阅确认，close 发起退订；不是等待全部回调完成的同步屏障 | BobaStrawProtocolNegotiationTest |
| 事务 helper、懒加载池 | 部分实现；取消、异常归还、WATCH 场景需继续验收 | RedisCompatibilityTest 仅覆盖基础 EXEC |
| String / binary / Lua | 常用命令子集；binary facade 为异步 GET/SET/DEL，其他可用 Raw | RedisCompatibilityTest / RespCodecTest |
| Cluster | 实验性 seeds、Slot、Hash Tag、MOVED/ASK；完整拓扑与多 Key 策略不足 | ClusterSlotTest 不能代替真实故障切换验收 |
| TLS / Sentinel | 未实现 | 无 |
| 阻塞命令专用管理 | 未完成，不要在共享 Raw/Pipeline 连接发送 BLPOP 等阻塞命令 | 无完整验收 |
| Spring Boot | 基础单客户端配置；示例验证 Boot 2.7.18 | 示例 SpringContextTest |
| Codec SPI / 完整命令 / Health / Metrics / 多客户端自动配置 | 未完成 | 不生成虚构 API |

Raw API 是未封装普通命令的出口，不是任意状态型命令安全执行的保证。
禁止通过共享 Raw/Pipeline 发起 MULTI、WATCH、SUBSCRIBE、SELECT 等改变连接状态的命令。
缺少合适公开接口时报告能力缺口，不绕过 internal 包。
