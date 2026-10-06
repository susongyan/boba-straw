# 版本能力表

适用 SDK：0.1.0-SNAPSHOT；仓库版以源码提交及工作树为准，离线 manifest 包尚未发布。
“基础已验证”不等于整个 v1 已完成或适合任意生产负载。

| 能力 | 当前边界 | 验证依据（仓库测试类） |
| --- | --- | --- |
| Standalone sync / CompletionStage | 基础已验证，Client 长期复用 | RedisCompatibilityTest |
| RESP2/RESP3、HELLO 协商、显式 RESP2 | 基础已验证 | BobaStrawProtocolNegotiationTest / RespCodecTest |
| 重连、取消、超时、容量、共享 Resources | 不自动重放命令，已写取消需排空 | BobaStrawConnectionLifecycleTest / BobaStrawClientResourcesTest |
| Pipeline | 三拓扑 String 真正批量写，保序但不原子；typed() 句柄与 executeTyped() 结果，Raw execute() 保留；Cluster 整批同 Slot，不重定向重放 | NioConnectionIoTest / TypedBatchCompatibilityTest / ClusterLifecycleTest / ClusterIntegrationTest / SentinelIntegrationTest |
| Pub/Sub | 三拓扑经典订阅；异步确认，close 发起退订；termination() 可观察意外终止，不自动重订阅，不保证切换期间消息无损 | BobaStrawProtocolNegotiationTest / ClusterIntegrationTest / SentinelIntegrationTest |
| 事务 helper、懒加载池 | AutoCloseable；取消/失败销毁、成功归还；typed() / execTyped() 区分 WATCH abort，Raw exec() 保留空列表兼容行为 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest / TypedBatchCompatibilityTest |
| 高频 String / binary | String/Key/TTL/Counter/Bit、Hash/List/Set/ZSet 高频普通命令；String sync/async 和 binary async，非全命令全选项 | RedisCompatibilityTest / BinaryStringCommandsTest / BinaryStringCompatibilityTest / HighFrequencyCommandsCompatibilityTest；见[命令覆盖](../implementation/command-coverage.md) |
| Lua L1–L4 | 直接 EVAL/SCRIPT LOAD/EVALSHA、ScriptOutput；scripts() 注册、按名称执行和单次 NOSCRIPT 恢复；三拓扑 String sync/async、binary async；三拓扑 String typed Pipeline/事务支持脚本，按名称入队 EVAL、不事后恢复 | ScriptRegistryTest / ScriptOutputTest / ScriptCommandsTest / ScriptCompatibilityTest / ScriptBatchTest / TypedBatchCompatibilityTest / DedicatedConnectionLifecycleTest / ClusterIntegrationTest / SentinelIntegrationTest；实际结果见[Lua 记录](../testing/lua-scripting-validation.md)及[核心收尾](../implementation/core-completion-plan.md)，[用法](lua.md) |
| 命令元数据 / 三层入口 | 共用 Key 规则与连接模式；已知状态型/阻塞命令不能经共享 Raw/Pipeline 绕过 | CommandModelTest；见[设计](../architecture/command-model.md) |
| Scan typed 页结果 | scan() 特殊入口；异步 String SCAN/HSCAN/SSCAN/ZSCAN、MATCH/COUNT；Cluster 仅单 Key 扫描，不提供全库迭代 | ScanCommandsTest / ScanCompatibilityTest / ClusterIntegrationTest / SentinelIntegrationTest |
| Cluster | String sync/async、binary async、String Pipeline/事务/BLPOP/BRPOP/经典订阅；多 seed、Slot/Hash Tag、重连/发现、普通命令 MOVED/ASK；拓扑变化退休专用连接，生产长稳未覆盖 | TypedCommandExecutionTest / ClusterSlotTest / ClusterLifecycleTest / opt-in ClusterIntegrationTest；详见核心收尾计划 |
| Sentinel | String sync/async、binary async、String Pipeline/事务/BLPOP/BRPOP/经典订阅；多 Sentinel、独立认证、ROLE 校验与切换；旧专用连接退休，不重放 | TypedCommandExecutionTest / SentinelLifecycleTest / opt-in SentinelIntegrationTest；详见核心收尾计划 |
| TLS | 核心 SSLEngine、rediss/显式 options、证书与端点校验、私有 CA/mTLS；三拓扑与专用连接已验收；生产长稳及扩展平台矩阵后置 | TlsConnectionTest、TlsOptionsTest、TlsTaskCapacityTest、NioTlsFaultTest、TlsCompatibilityTest（真实 Redis/Valkey）；环境与边界见核心收尾计划 |
| 阻塞命令专用管理 | 三拓扑 String 同步/异步 BLPOP、BRPOP；有界按需单次连接，Cluster 同 Slot；更多阻塞和 binary 阻塞未提供 | DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest / ClusterLifecycleTest / ClusterIntegrationTest / SentinelIntegrationTest |
| Spring Boot | 基础单客户端配置；示例工程及 Boot 版本矩阵尚未验收 | 尚无 SpringContextTest，不作示例已验证声明 |
| Spring Boot Starter | 三拓扑默认 Bean、具名客户端、TLS store、生命周期、可选 Health/Micrometer；已测 Boot 2.7.18/Java 8、Boot 3.0.13/Java 17、Boot 3.5.6/Java 21 | BobaStrawAutoConfigurationTest、StarterCompatibilityTest；完整/定向矩阵边界见 C8 记录，用法见 quickstart |
| 自定义 Codec SPI | 未完成；已有 String 与 byte[] 不等于可插拔序列化 SPI，按实际需求另排 | 不生成虚构 API |

Raw API 是未封装普通命令的出口，不是任意状态型命令安全执行的保证。
禁止通过共享 Raw/Pipeline 发起 MULTI、WATCH、SUBSCRIBE、SELECT 等改变连接状态的命令。
缺少合适公开接口时报告能力缺口，不绕过 internal 包。

Cluster/Sentinel 现在可使用 `client.async().get(key)`、`hgetall(key)`、`zscore(key, member)`
等普通 String typed 方法，三种拓扑也提供 sync()/binary()。BLPOP/BRPOP 走专用连接。
Cluster 多 Key 仍要求同 Slot；KEYS/RANDOMKEY 等无 Key 命令只查询一个主节点，不是全集群扫描。
事务使用 `cluster.transaction(routingKey)` 或 `sentinel.transaction()`；拓扑变化后不继续旧租约。

普通 sync()/async()/binary() 方法直接返回相应结果或 Stage，不需要调用 typed()。
批量 typed 用法见[命令、批量与分页](commands.md)。句柄不独立执行/取消；
取消整个执行 Stage 不证明服务端未执行。单条错误在 result.get(handle) 抛出，不应忽略整批其他结果。
三拓扑 String 批量目录不代表所有 async 方法都已有批量 typed 对应项；binary batch/Scan 尚未提供。
