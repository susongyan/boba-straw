# 版本能力表

适用 SDK：0.1.0-SNAPSHOT；仓库版以源码提交及工作树为准，离线 manifest 包尚未发布。
“基础已验证”不等于整个 v1 已完成或适合任意生产负载。

| 能力 | 当前边界 | 验证依据（仓库测试类） |
| --- | --- | --- |
| Standalone sync / CompletionStage | 基础已验证，Client 长期复用 | RedisCompatibilityTest |
| RESP2/RESP3、HELLO 协商、显式 RESP2 | 基础已验证 | BobaStrawProtocolNegotiationTest / RespCodecTest |
| 重连、取消、超时、容量、共享 Resources | 不自动重放命令，已写取消需排空 | BobaStrawConnectionLifecycleTest / BobaStrawClientResourcesTest |
| Pipeline | 真正批量写，保序但不原子；一次性 builder；Standalone String typed() 句柄与 executeTyped() 结果，Raw execute() 保留 | NioConnectionIoTest / RedisCompatibilityTest / TypedBatchCompatibilityTest |
| Pub/Sub | 异步订阅确认，close 发起退订；不是等待全部回调完成的同步屏障 | BobaStrawProtocolNegotiationTest |
| 事务 helper、懒加载池 | AutoCloseable；取消/失败销毁、成功归还；typed() / execTyped() 区分 WATCH abort，Raw exec() 保留空列表兼容行为 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest / TypedBatchCompatibilityTest |
| 高频 String / binary | String/Key/TTL/Counter/Bit、Hash/List/Set/ZSet 高频普通命令；String sync/async 和 binary async，非全命令全选项 | RedisCompatibilityTest / BinaryStringCommandsTest / BinaryStringCompatibilityTest / HighFrequencyCommandsCompatibilityTest；见[命令覆盖](../implementation/command-coverage.md) |
| Lua L1/L2/L3 | 直接 EVAL/SCRIPT LOAD/EVALSHA、ScriptOutput；scripts() 注册、按名称执行和单次 NOSCRIPT 恢复；三拓扑 String async，Standalone 另有 binary；Standalone String typed Pipeline/事务支持脚本，按名称入队 EVAL、不事后恢复 | ScriptRegistryTest / ScriptOutputTest / ScriptCommandsTest / ScriptCompatibilityTest / ScriptBatchTest / TypedBatchCompatibilityTest / DedicatedConnectionLifecycleTest / ClusterIntegrationTest / SentinelIntegrationTest；实际结果见[Lua 记录](../testing/lua-scripting-validation.md)，[用法](lua.md) |
| 命令元数据 / 三层入口 | 共用 Key 规则与连接模式；已知状态型/阻塞命令不能经共享 Raw/Pipeline 绕过 | CommandModelTest；见[设计](../architecture/command-model.md) |
| Scan typed 页结果 | scan() 特殊入口；异步 String SCAN/HSCAN/SSCAN/ZSCAN、MATCH/COUNT；Cluster 仅单 Key 扫描，不提供全库迭代 | ScanCommandsTest / ScanCompatibilityTest / ClusterIntegrationTest / SentinelIntegrationTest |
| Cluster | 普通 String async typed 与 Raw；多 seed、Slot/Hash Tag、退避重连、周期/事件刷新、MOVED、独占 ASK、已知多 Key 校验；专用命令和生产长稳仍待完成 | TypedCommandExecutionTest / ClusterSlotTest / ClusterLifecycleTest / opt-in ClusterIntegrationTest；详见核心收尾计划 |
| Sentinel | 普通 String async typed 与 Raw、多 Sentinel 发现、独立认证、ROLE 校验、切换和退避重发现；专用组合待完成 | TypedCommandExecutionTest / SentinelLifecycleTest / opt-in SentinelIntegrationTest；详见核心收尾计划 |
| TLS | 未实现，明确后置 | 无 |
| 阻塞命令专用管理 | Standalone 同步/异步 BLPOP、BRPOP；有界按需单次连接，更多阻塞命令待扩展 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest |
| Spring Boot | 基础单客户端配置；示例工程及 Boot 版本矩阵尚未验收 | 尚无 SpringContextTest，不作示例已验证声明 |
| Codec SPI / Health / Micrometer / 多客户端自动配置 | 未完成；核心 Client 已有 metrics() 快照，不等于 Starter 的 Micrometer 集成；冷门普通命令保留 Raw 出口 | 不生成虚构 API |

Raw API 是未封装普通命令的出口，不是任意状态型命令安全执行的保证。
禁止通过共享 Raw/Pipeline 发起 MULTI、WATCH、SUBSCRIBE、SELECT 等改变连接状态的命令。
缺少合适公开接口时报告能力缺口，不绕过 internal 包。

Cluster/Sentinel 现在可使用 `client.async().get(key)`、`hgetall(key)`、`zscore(key, member)`
等普通 String typed 方法。复用的 facade 中 BLPOP/BRPOP 仅 Standalone 支持；在另外两种拓扑
同步抛 UnsupportedOperationException。Cluster 多 Key 仍要求同 Slot；KEYS/RANDOMKEY 等无 Key
命令只查询一个主节点，不是全集群扫描。未提供 Cluster/Sentinel binary 或同步 facade。

普通 sync()/async()/binary() 方法直接返回相应结果或 Stage，不需要调用 typed()。
批量 typed 用法见[命令、批量与分页](commands.md)。句柄不独立执行/取消；
取消整个执行 Stage 不证明服务端未执行。单条错误在 result.get(handle) 抛出，不应忽略整批其他结果。
仅 Standalone String 初始高频目录，不代表所有 async 方法都已有批量 typed 对应项。
