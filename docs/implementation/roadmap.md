# Boba Straw 功能实施与验收清单

本文档记录已实现功能、验证结果和后续工作，是研发与 AI 协作时的进度基线。

更新时间：2026-10-06；最新已提交功能基线为 `bcde420`，C6 在工作树中完成。
Lua L3 已提交，Standalone String 批量限定范围已通过 JDK 8/21 full 验收。
C6.1～C6.5 已完成 binary、普通同步、String Pipeline/事务、BLPOP/BRPOP 和经典 Pub/Sub，
最终 JDK 8/21 full 各 200 项通过、无跳过，所有模块成功。
验证记录见核心收尾计划。
下文历史诊断与压测记录仍保留各自源码范围，不自动覆盖新代码。

2026-09-22 起的执行顺序及 TLS 后置决定见[核心收尾计划](core-completion-plan.md)。

状态：

- [x] 已实现并通过验收
- [~] 已有实现，但未达到生产验收
- [ ] 尚未实现

## 当前阶段与剩余工作

以下为当前状态；后文网络阶段记录保留当时的版本和测试结果，不代表后续改动自动通过验收。

| 阶段 | 当前状态 | 剩余范围 |
| --- | --- | --- |
| C1 专用连接 | 限定 Standalone 事务、BLPOP/BRPOP 范围已验收 | 更多阻塞命令及拓扑组合不包含在完成声明内 |
| C2 / C3 Cluster / Sentinel | 限定普通命令发现、路由、重连与切换已验收 | 专用能力组合见 C6；跨主机分区及生产长稳未覆盖 |
| C4 兼容矩阵 | 历史本机 JDK 8/11/17/21 通过；最新 C6 全量回归为 8/21 | JDK 25、其他 OS，以及最新版本的扩展矩阵 |
| C5 高频命令与三层 API | 冻结功能已实现；已定位并修复测试端口冲突导致的 H 路径 | 历史无端点证据的失败仍保留，低负载正式性能复测待做 |
| C6 拓扑组合 | C6.1–C6.5 限定范围完成；双 JDK full 各 200 项通过 | binary batch/Scan/阻塞/订阅、sharded Pub/Sub 按需另排，不在本次完成范围 |
| C7 TLS | 未实现，后置 | SSLEngine、证书与主机名校验、关闭和重连验收 |
| C8 Starter 与发布 | 基础自动配置已有，生产验收未完成 | 多客户端、拓扑/TLS 配置、Health/Micrometer、质量门禁、许可证及发布 |

### C5 两项收尾

- [~] `Unsupported RESP marker: H`：2026-10-06 已复现一条确定路径：本机 Java wildcard
  测试监听与 VS Code loopback HTTP 监听共存，RESP 请求得到 HTTP 400。测试统一改为明确地址、
  非复用端口绑定；生产 decoder 不改。旧 Binary/Sentinel 报告没有完整端点证据，仍不能逐次归因；
  其他等待异常保留，详情见[协议诊断](../testing/binary-resp-diagnostics.md)。
- [ ] C5 不可变 binary 帧优化的低负载正式 Redis/Valkey A/B/B/A：已有高负载分配量诊断，
  但不能代替吞吐/延迟验收。最近预检 load/CPU 为 1.770，高于 1.50，正式测试未启动。
  这不撤销历史网络模型阶段六的验收，也不沿用其结果替代本次性能测试。

此前 C5 full 矩阵：JDK 8u202 / 21.0.7 各 149 tests、零失败/跳过；最终诊断断言定向回归
各 25 tests 通过。详情见 [C5 收尾审查](c5-exit-review.md) 和
[Binary RESP 诊断](../testing/binary-resp-diagnostics.md)。

历史 Lua L1 回归：JDK 21 全 163 tests 通过；Java 8 两轮各 163 tests，分别有一项事务等待超时
和一项 Sentinel 模拟 H 异常。新增 Lua 用例通过，但全量 Java 8 门禁未通过；
证据与源码范围见 [Lua 测试记录](../testing/lua-scripting-validation.md)。

最近已提交脚本容量配置回归：JDK 8/21 各 184 tests，158 通过、26 集成测试跳过；
不代表历史偶发失败根因已关闭。L3 全量验证在权限审核服务恢复后完成：
JDK 8/21 各 187 项通过、零跳过，所有模块成功，详见 Lua 测试记录。

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
- [x] 事务懒加载专用连接池；Pub/Sub 不入池，BLPOP/BRPOP 使用有界单次专用连接
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
- [x] 阶段 6：JMH harness、隔离 Core 的 ABBA runner、Redis critical 与 Codec 正式 A/B 已落地；
  精确尺寸 RESP 编码优化已通过正式 ABBA，Redis 与 Valkey 全 workload/`byte[]` 大 value、系统观测、
  instrumentation 隔离 A/B 和确定性故障注入已完成；128 未采用，64 通过功能回归及正式 ABBA，最终采用 64 帧 / 64 KiB

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
Codec 正式 ABBA 也已完成：碎片解码和回复 burst 显著改善；初次观察到的 GET 编码 allocation
差异经对照实验不能归因于版本，但由此完成的精确尺寸编码仍将 allocation 稳定降至 144 B/op，
编码吞吐配对改善 1.82 倍。原始观察和后续验证分别记录于
[`20260905-ca078f4-vs-7a2fe41-codec`](../benchmarks/results/20260905-ca078f4-vs-7a2fe41-codec/summary.md)
与
[`20260906-7a2fe41-vs-da546da-codec-encode`](../benchmarks/results/20260906-7a2fe41-vs-da546da-codec-encode/summary.md)。
Valkey 8.1.3 全网络基线也已完成，结果见
[`20260906-0cbf813-valkey-full`](../benchmarks/results/20260906-0cbf813-valkey-full/summary.md)。
同环境 `byte[]` 大 value 将 1 MiB allocation 从 String 的约 2.10 MiB/op 降至约 1.053 MiB/op，
确认额外一份 payload 来自 String/UTF-8 转换，结果见
[`20260906-b9ceff7-valkey-binary-large`](../benchmarks/results/20260906-b9ceff7-valkey-binary-large/summary.md)。
Redis 7.4.2 全 workload（含 String/`byte[]` 大 value）也已完成，结果见
[`20260906-9b3f116-redis-full`](../benchmarks/results/20260906-9b3f116-redis-full/summary.md)。随后已补充
客户端/server CPU、线程数、socket I/O 等系统观测，并执行碎片响应、连接中断、慢消费者等故障注入；
环境、命令、原始结果和结论统一保存至 `docs/benchmarks/`。

系统观测 instrumentation 已落地：`BobaStrawClientMetrics` 提供当前物理连接的 socket read/write
次数和字节数，`TransportObservationBenchmark` 与独立 runner 同时采集 JMH 辅助计数、实际 fork JVM
及 Redis/Valkey 容器资源。Redis 正式结果见
[`20260907-9dfa609-redis-observe`](../benchmarks/results/20260907-9dfa609-redis-observe/summary.md)：
Pipeline 128 精确命中 32 commands/write，已将 gathering frame 上限识别为后续 A/B 候选。
Valkey 正式结果见
[`20260907-9ece1c7-valkey-observe`](../benchmarks/results/20260907-9ece1c7-valkey-observe/summary.md)：
同样观测到 Pipeline 128 精确命中 32 commands/write。instrumentation 隔离 ABBA 也已完成，
未观察到可分辨的实质吞吐或 allocation 回归，结果见
[`20260907-c4d9898-vs-9dfa609-transport-overhead`](../benchmarks/results/20260907-c4d9898-vs-9dfa609-transport-overhead/summary.md)。
gathering frame 参数校准已完成，最终值为 64 frames / 64 KiB。

gathering frame 128 候选保留每连接每轮 64 KiB 写预算，并由 `NioConnectionIoTest` 验证
Pipeline 128 的一次 gathering write、完整响应与 FIFO。正式 ABBA 显示 Async/Pipeline 吞吐分别
改善 1.493x/1.441x，但 shared EventLoop healthy GET 平均/P99 延迟恶化约 18.8%/60.0%，因此
128 被拒绝，结果见
[`20260908-b9de0a0-vs-0f3506a-gathering-write-128`](../benchmarks/results/20260908-b9de0a0-vs-0f3506a-gathering-write-128/summary.md)。
64 frames 正式 ABBA 已完成：healthy GET 平均/P99 延迟下降约 8.2%/15.4%，noisy 完成量
提高约 8.0%，Pipeline 吞吐整体持平。最终采用 64 frames / 64 KiB，结果见
[`64-frame ABBA`](../benchmarks/results/20260915-b9de0a0-vs-2214adb-gathering-write-64/summary.md)。
阶段 6 在 JDK 21、macOS/Colima 下完成；这不代表整个 v1 客户端或跨 JDK/平台发布矩阵已完成。
Sentinel、TLS、Cluster 生产化等仍按本文件对应功能条目跟踪。

确定性网络故障注入已整理为独立的 `fault-injection` JUnit 标签与
[`run-fault-injection-tests.sh`](../../scripts/run-fault-injection-tests.sh) 入口。覆盖 RESP 任意分片、
有界/部分写、回复 burst、写后断连、握手失败、超时/取消后的迟到回复、共享 EventLoop 单连接
故障、慢 Pub/Sub listener 和退订关闭竞态；失败分类与资源生命周期验收矩阵见
[`fault-injection.md`](../testing/fault-injection.md)。

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
- [x] NIO Selector/SocketChannel；共享 EventLoop 管理多条连接，每条连接由一个 EventLoop 串行处理
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
- [x] MULTI/EXEC 与本地 discard 专用连接 helper，支持 AutoCloseable

- [x] C5 高频 String/Key/TTL/Counter/Bit、Hash/List/Set/ZSet：Standalone String sync/async 与 binary async
- [x] CommandSpec/Args/Registry/Decoder/TypedCommand：元数据路由、普通入口策略和 typed 执行
- [x] Cluster/Sentinel 普通 String sync/async、binary async 与 String Raw 入口
- [x] 三拓扑 String Pipeline/事务 typed 句柄与结果；保留 Raw 批量入口
- [x] String 异步 Scan 页模型、MATCH/COUNT：Standalone/Sentinel 四种，Cluster 单 Key 三种

普通 `sync()/async()/binary()` 不需要显式 `typed()`；`typed()` 用于批量构建。
上述为冻结高频范围，非全命令/全选项；binary Scan/batch 和完整 Stream/Geo/HLL 不属于 C5 完成前提。
方法清单、返回语义和验证依据见[命令覆盖](command-coverage.md)及[版本能力表](../usage/supported-features.md)。

基础命令、Pipeline、事务 helper、Lua 已有 Redis/Valkey 兼容测试。事务与阻塞连接新增
DedicatedConnectionLifecycleTest / DedicatedConnectionCompatibilityTest，范围与环境见核心收尾计划。

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

- [x] 独立 BobaStrawClusterClient 普通命令入口
- [x] CLUSTER SLOTS 多 seed 初始发现、周期/事件刷新和原子快照校验
- [x] CRC16 Slot 计算与 Hash Tag
- [x] Slot 到主节点路由、节点退避重连和旧非 seed 节点摘除
- [x] MOVED 一次重定向
- [x] ASK/ASKING 一次专用连接重定向、取消与容量限制
- [x] 已知命令提取 Key/跨 Slot 拒绝，未知普通命令显式 Key 入口

尚未达到完整生产验收：Cluster Pipeline/事务/PubSub/阻塞语义、跨主机分区和
长稳压力。普通命令每节点复用连接，不要求共享连接池；专用组合留待 C6。
设计与测试入口见 [Cluster 拓扑](../architecture/cluster-topology.md)，实际记录见
[核心收尾计划](core-completion-plan.md)。

### Spring Boot

- [~] Boot 2.7/3.x 基础自动配置
- [~] 单客户端 URI、超时、协议配置
- [x] core 与 Spring 解耦
- [x] 不提供 WebFlux/Reactor 适配

尚未达到生产验收：多客户端、Sentinel/Cluster/TLS 配置、Health、Metrics、生命周期和配置校验。

## 专用能力与连接管理（已实现的限定范围）

- [x] 三拓扑经典 Pub/Sub 专用连接、订阅确认、RESP2 消息和 RESP3 Push、退订释放与可观察终止
- [x] 三拓扑真正批量 Pipeline 编码和批量 Socket 写入；Cluster 整批同 Slot、不重放
- [x] 事务专用连接、WATCH/UNWATCH、成功归还及取消/异常销毁
- [x] TransactionConnectionPool（按需创建、上限、锁外等待、关闭唤醒及空闲回收）
- [x] 事务连接获取等待超时
- [x] 事务空闲连接回收
- [x] 归还时连接存活与状态清理确认；不额外发送 PING
- [x] 共享 NioConnectionFactory
- [x] 三拓扑 String BLPOP/BRPOP 的同步/异步专用连接；更多阻塞命令仍待扩展
- [x] Pipeline 与命令超时到物理请求的取消传播和响应排空
- [x] 未发送/可能已执行请求的失败分类
- [x] Standalone 有界退避重连、连接状态与指标管理
- [x] Sentinel 主节点发现和切换感知；C6 绑定专用连接代次，退休旧租约，不自动重放/重订阅
- [x] 确定性网络故障注入测试及独立执行入口
- [x] 网络模型阶段六并发与 JMH 基线（Valkey 观测及 instrumentation A/B 已完成；非完整发布矩阵）

上述范围包括 C6 专用连接组合，不包括所有阻塞/订阅命令；测试范围见核心收尾计划和版本能力表。

## 未完成及后续按需扩展

- [x] C6：限定范围已完成，最终双 JDK full 各 200 项通过；详细范围见上表
- [ ] 跨主机分区、生产长稳及扩展 JDK/OS 发布矩阵
- [ ] C7：JDK SSLEngine TLS
- [ ] C8：Spring Boot Health、Micrometer、Actuator、多客户端、拓扑/TLS 配置及版本矩阵
- [ ] 自定义 Codec SPI（已有 String 与 byte[] 高频接口，不等于可插拔序列化 SPI）
- [ ] binary Scan/batch、更多阻塞命令及选项；按实际需求扩展，不作为 C5 冻结范围缺口
- [~] 常用 Lua 工作包：L1/L2 及容量配置已实现；L3 Standalone String 批量接口已完成限定矩阵验收。L4 拓扑组合未实施。阶段状态见[实施进度](lua-scripting-progress.md)，验证与历史回归待办见[测试记录](../testing/lua-scripting-validation.md)
- [ ] Stream、Geo、HyperLogLog、更多 Server/ACL typed API：按需排期，不追求全命令
- [ ] Checkstyle、SpotBugs、ArchUnit、JaCoCo、Revapi/japicmp、Enforcer 门禁
- [ ] LICENSE、NOTICE、Maven Central 发布元数据

Bitmap 的 GETBIT/SETBIT/BITCOUNT 高频接口已经实现，不再笼统列为未实现。
2026-09-29 范围决定：不规划客户端读写分离或 Replica 读策略，不列为待办或发布验收缺口。
Cluster/Sentinel 普通读写均路由到当前主节点；副本晋升后的新主节点仍可正常被发现和使用。
Cluster 的普通 Slot 路由、MOVED/ASK、节点重连及已知多 Key 同 Slot 校验已经实现，
不再与未完成的拓扑专用能力合并为“Cluster 未实现”。

## 每项功能的完成定义

功能只有同时满足以下条件才可从 [~] 或 [ ] 改为 [x]：

1. Java 8 兼容实现完成。
2. 有单元测试和碎片化测试（适用时）。
3. 有并发、超时、取消、断线测试（适用时）。
4. 有真实 Redis、Valkey 或拓扑容器测试（适用时）。
5. 明确失败、重试和资源生命周期语义。
6. 不引入禁止的响应式或网络运行时依赖。
7. README 和架构文档已同步。
8. mvn test 和适用的现有 CI 检查通过；尚未落地的发布门禁单独跟踪，不能宣称已通过。

## 测试命令

普通测试：mvn test

兼容性测试：mvn -Dboba.straw.runCompatibility=true test

Cluster：`mvn -Dboba.straw.runCluster=true test`。

Sentinel：`mvn -Dboba.straw.runSentinel=true test`。

隔离全模块矩阵：`sh scripts/run-compatibility-matrix.sh full /absolute/jdk8/home /absolute/jdk21/home`。
full 需要预先启动对应本地容器；默认测试不依赖容器，TLS 测试入口待 C7 实现。
