# Boba Straw 功能实施与验收清单

本文档记录已实现功能、验证结果和后续工作，是研发与 AI 协作时的进度基线。

状态：
- [x] 已实现并通过验收
- [~] 已有实现，但未达到生产验收
- [ ] 尚未实现

## 已实现功能与验收结果

### 工程与运行时

- [x] Maven 多模块工程
- [x] 包名统一为 io.github.susongyan.bobastraw
- [x] Java 8+ 编译目标
- [x] boba-straw-core 仅依赖 JDK
- [x] 不引入 Netty、Reactor、RxJava、Kotlin Coroutine、WebFlux
- [x] Java 8/11/17/21 CI 基础矩阵

验收结果：mvn test 通过，core 无第三方运行时依赖。

### 连接模型

- [x] 普通命令每个 Redis 节点一个共享多路复用连接
- [x] 事务和 Pub/Sub 使用独占连接
- [x] `BobaStrawClientResources` 共享固定数量的 Selector EventLoop
- [x] 有界 callback dispatcher 与 Pub/Sub listener 串行隔离
- [~] 状态型专用连接池（事务连接池已懒加载，Pub/Sub/阻塞命令待补）
- [x] 每条物理连接的 in-flight / 待写字节准入上限
- [x] Standalone 共享连接的 lifecycle 驱动指数退避重连与状态快照
- [x] 可选空闲连接 PING 健康检测

### 网络模型演进

- [x] 网络模型、EventLoop 所有权、取消与背压架构文档
- [x] 阶段 1：连接队列状态收敛和取消/FIFO 安全
- [x] 阶段 2：共享 Selector EventLoopGroup
- [x] 阶段 3：读缓冲复用、gathering write 与公平预算
- [x] 阶段 4：RESP 增量状态机与协议资源上限
- [x] 阶段 5：统一 deadline、背压、回调、订阅分发隔离与连接 lifecycle
- [~] 阶段 6：JMH harness、隔离 Core 的 ABBA runner 与 Redis critical 正式 A/B 已落地并通过；
  Redis/Valkey 全 workload、系统观测和故障注入待执行

验收原则：普通命令无需业务配置连接池大小；连接池只服务于状态型场景。

阶段 1 验收记录：业务线程只向 EventLoop 提交任务；握手前提交的请求按提交顺序进入
`preReady`；已写入的取消请求保留响应占位；RESP3 Attribute 包裹的 Push 不得消费普通
请求；Pub/Sub 的 RESP3 订阅确认匹配专用连接的待处理请求；连接失败会区分未发送和
可能已执行。上述语义由本地假 Redis socket 测试覆盖，并已通过 `mvn test`。

阶段 2 验收记录：`BobaStrawClientResources` 管理固定数量的共享 Selector 线程；
Standalone、事务、Pub/Sub 和 Cluster 全部经由统一连接 factory 分配 EventLoop。
同组单条连接失败不影响其他连接；关闭使用外部 Resources 的 Client 不关闭资源，关闭
Resources 会终止在途请求并拒绝新命令。上述生命周期由 socket 测试覆盖，并已通过
`mvn test`。

阶段 3 验收记录：每个连接复用 16 KiB heap 读缓冲和 gathering write 数组；单个
EventLoop turn 最多执行 256 个跨线程任务，单连接最多读 64 KiB、聚合写 32 帧 / 64 KiB、
分发 64 个完整响应。命中响应额度时停止继续从 socket 读入，缓存响应令下一轮使用
`selectNow()`，避免等待 100 ms。请求只在实际写出字节后进入 `WRITING`，取消与断连仍
保持未发送 / 可能已执行的失败语义。`NioConnectionIoTest` 覆盖预算截断的大帧 FIFO
写入、响应 burst 分片分发和同 loop 跨连接让出；完整 `mvn test` 已通过。

阶段 4 验收记录：RESP decoder 现在使用可 compact 输入缓冲、流式 Bulk 状态和显式
aggregate frame stack；不再拼接整段输入或递归重解析不完整 aggregate。严格 CRLF、Bulk
trailer、Null、Boolean、Verbatim 校验可阻止畸形回复污染 FIFO。`RespLimits` 默认保护
64 MiB buffer/顶层回复、32 MiB Bulk、64 KiB line、64 层嵌套和 100,000 个 aggregate
child；可在 Standalone 或 Cluster Builder 配置，并会传递到重连、事务、Pub/Sub 和所有
Cluster node 连接。越限/畸形回复会关闭连接，已写请求仍明确报告“可能已执行”，绝不重试。
逐字节 Attribute、大 Bulk、非法 wire、限制边界与 socket 级断连分类均有回归测试。

阶段 5A 验收记录：每个 Selector EventLoop 现在拥有可取消 deadline 队列，命令、握手和
空闲 PING 都不依赖全局定时线程。请求 deadline 从创建时开始计时；未写入时超时明确报告
未发送，已写入时进入响应排空并明确报告可能已执行。取消的 deadline 不会执行；不会自动
重放命令。
`NioEventLoopDeadlineTest` 与协议 socket 回归已覆盖。

阶段 5B 验收记录：`BobaStrawClientResources` 现在还拥有有界 callback dispatcher，默认
1 个 callback worker 和 1024 个排队位。普通命令在写入 Redis 前预留结果交付 slot；若已满，
立即以 `BobaStrawBackpressureException` 拒绝且不发送命令。应用的 `CompletionStage`
continuation 不再执行在 Selector EventLoop；同一 Pub/Sub 连接的 listener 通过串行 dispatcher
保序执行。慢 listener 耗尽容量时关闭专用连接而非静默丢消息，关闭后会从 Client 专用连接集合
移除。容量、隔离和慢消费者 socket 回归均已覆盖。

阶段 5C 验收记录：`BobaStrawConnectionLimits` 默认限制每条物理连接 4,096 条已准入
命令和 16 MiB 尚未写出的命令帧；Pipeline 在入队前原子预留全部容量，超限时零帧写出。
已写取消或超时请求保持命令占位直至其响应排空，避免错误地把响应匹配给下一命令。共享
Standalone 连接由 close/ready lifecycle 驱动 capped exponential backoff；退避期间新调用
明确失败为“未发送”，不会绕过退避创建额外 socket。`BobaStrawClientMetrics` 提供无 I/O 的
状态、创建次数、重连尝试/成功、失败计数、下一次退避、in-flight、待写字节及拒绝次数快照。
同步 facade 直接等待 transport 结果；派生 async stage 的取消会传播到底层请求；退订 ACK 后立即
释放专用 socket/Selector，Pub/Sub serial barrier 仍保证先交付 ACK 前已经解码的消息，再关闭 callback
stream；Client 在排空期间关闭会取消尚未开始的 listener。容量、取消、退避、同步隔离和
退订顺序均由 socket 回归覆盖。

阶段 6 性能验收进度：已使用 JDK 21、Colima 和固定 2 CPU/2 GiB Redis 7.4.2 容器完成
`ca078f4` 与 `7a2fe41` 的正式 Redis critical ABBA。候选版本的异步窗口吞吐、Pipeline 吞吐、
慢回调隔离和共享 EventLoop 公平性均改善，原始 JSON、环境与结论见
[`20260905-ca078f4-vs-7a2fe41-redis-critical`](../benchmarks/results/20260905-ca078f4-vs-7a2fe41-redis-critical/summary.md)。
后续继续测试
Redis 与 Valkey 的单命令、异步窗口、Pipeline、大 value、碎片响应、多 Client 共享 EventLoop、
慢回调和慢消费者负载，记录吞吐、P50/P95/P99/P999、CPU、GC、分配率、线程数、socket I/O 和
跨连接公平性；环境、命令、原始结果和结论统一保存至 `docs/benchmarks/`。

### 协议与连接

- [x] RESP2 Simple String、Error、Integer、Bulk、Array
- [x] RESP3 Map、Set、Push、Attribute
- [x] RESP3 Blob Error、Verbatim String、Big Number
- [x] 增量解析和碎片输入
- [x] 显式非递归 RESP frame stack 与可配置协议资源上限
- [x] 严格 RESP line / Bulk trailer 校验与协议失败终止连接
- [x] FIFO 请求/响应匹配
- [x] Attribute 不影响普通响应匹配
- [x] AUTO 使用 HELLO 3
- [x] Redis 5 不支持 HELLO 时回退 RESP2
- [x] 显式 RESP2 跳过 HELLO
- [x] 用户名、密码和 CLIENT SETNAME 握手入口
- [x] NIO Selector/SocketChannel 单连接事件循环
- [x] 命令超时和连接异常

验收结果：本地假 Redis 协商测试和 Redis 5/6.2/7、Valkey 矩阵测试通过。

### API 与命令

- [x] 同步 API
- [x] CompletionStage 异步 API
- [x] Raw Command 基础入口
- [x] PING、GET、SET、DEL
- [x] EXISTS、EXPIRE、TTL、INCR
- [x] HGET、HSET、HGETALL
- [x] LPUSH、RPUSH、LRANGE
- [x] SADD、SMEMBERS
- [x] ZADD、ZRANGE
- [x] Lua EVAL 基础入口
- [x] Pipeline 有序 API
- [~] MULTI/EXEC/DISCARD 专用连接 helper

验收结果：基础命令、Pipeline、事务 helper、Lua 已在 Redis/Valkey 兼容测试中验证；事务专用连接已接入，但仍需并发隔离和异常归还测试。

### 本地测试环境

- [x] Colima Redis/Valkey 启动脚本
- [x] Redis 5.0.14，端口 16379
- [x] Redis 6.2.14，端口 16380
- [x] Redis 7.4.2，端口 16381
- [x] Valkey 8.1.3，端口 16382
- [x] 端口仅绑定 127.0.0.1

启动：scripts/redis-test-up.sh
清理：scripts/redis-test-down.sh

## 部分实现

### Cluster

- [~] 独立 BobaStrawClusterClient 入口
- [~] CLUSTER SLOTS 初始发现
- [~] CRC16 Slot 计算
- [~] Hash Tag
- [~] Slot 到节点路由
- [~] MOVED 一次重定向
- [~] ASK/ASKING 一次重定向

尚未达到生产验收：拓扑周期刷新、连接池、故障摘除、Replica 读策略、跨 Slot 校验、Cluster Pipeline/事务/PubSub 语义。

### Spring Boot

- [~] Boot 2.7/3.x 基础自动配置
- [~] 单客户端 URI、超时、协议配置
- [x] core 与 Spring 解耦
- [x] 不提供 WebFlux/Reactor 适配

尚未达到生产验收：多客户端、Sentinel/Cluster/TLS 配置、Health、Metrics、生命周期和配置校验。

## 尚未实现

- [~] Pub/Sub 专用连接、订阅管理、RESP2 消息和 RESP3 Push 分发
- [~] 真正批量 Pipeline 编码和批量 Socket 写入
- [~] 事务专用连接、WATCH/UNWATCH 和连接归还
- [~] TransactionConnectionPool（按需创建、上限、成功归还、异常销毁）
- [x] 事务连接获取等待超时
- [x] 事务空闲连接回收
- [x] 归还时健康检查
- [ ] 通用 ConnectionFactory
- [x] Pipeline 与命令超时到物理请求的取消传播和响应排空
- [x] 未发送/可能已执行请求的失败分类
- [x] Standalone 有界退避重连、连接状态与指标管理
- [~] byte[] 基础 RESP 编码和 Raw API
- [ ] String/ByteArray Codec 及自定义 Codec SPI
- [ ] Stream、Bitmap、HyperLogLog、EVALSHA、Server/ACL 命令
- [ ] JDK SSLEngine TLS
- [ ] Sentinel 主节点发现和切换感知
- [ ] Cluster 完整拓扑、故障切换和多 Key 校验
- [ ] Spring Boot Health、Micrometer、Actuator、多客户端
- [ ] 故障注入、并发和 JMH 测试
- [ ] Checkstyle、SpotBugs、ArchUnit、JaCoCo、Revapi/japicmp、Enforcer 门禁
- [ ] LICENSE、NOTICE、Maven Central 发布元数据

## 每项功能的完成定义

功能只有同时满足以下条件才可从 [~] 或 [ ] 改为 [x]：

1. Java 8 兼容实现完成。
2. 有单元测试和碎片化测试（适用时）。
3. 有并发、超时、取消、断线测试（适用时）。
4. 有真实 Redis、Valkey 或拓扑容器测试（适用时）。
5. 明确失败、重试和资源生命周期语义。
6. 不引入禁止的响应式或网络运行时依赖。
7. README 和架构文档已同步。
8. mvn test 和 CI 门禁通过。

## 测试命令

普通测试：mvn test

兼容性测试：mvn -Dboba.straw.runCompatibility=true test

Cluster/Sentinel/TLS 测试完成后，应分别加入独立 profile，默认测试不得依赖本地容器。
