# 网络模型演进记录

本文完整保留网络模型阶段实施与验收记录。各阶段结果只适用于当时的提交、参数和测试环境，
不代表当前发布版本已经通过同样的性能验收。当前机制见[网络模型](../architecture/network-model.md)，
最新验证结果见[核心验证记录](core-completion-plan.md)。

---

## 分阶段实施与验收

1. **连接正确性**：收敛队列状态所有权，修复取消/写入竞态；Future 在锁外完成。
2. **共享 EventLoopGroup**：多个连接共享有限 Selector 线程，并完成生命周期测试。
3. **I/O 吞吐**：复用读缓冲、写入聚合、读写公平预算。
4. **RESP 增量状态机（已完成）**：减少累积复制和碎片重解析，并加入协议资源上限。
5. **背压、回调隔离与连接生命周期（已完成）**：有界队列、统一 deadline、Pub/Sub
   dispatcher、连接准入、退避重连和状态快照。
6. **基准与故障注入**：并发取消、部分写、断连、慢消费者、Redis/Valkey 矩阵和 JMH。

每阶段都必须保留 Java 8 兼容、执行 `mvn test`，并添加针对碎片输入、响应匹配、
资源关闭和失败语义的测试。阶段完成前不得将下一阶段能力标记为生产可用。

### 阶段 1 验收（已完成）

- 握手尚未完成时并发提交的普通命令保存在 `preReady`，激活后按 FIFO 转入
  `outbound`；不依赖 `CompletableFuture` 回调的执行顺序。
- 取消待写请求会从队列移除；写入中或已发送请求进入
  `CANCELLED_DRAINING`，其响应只用于恢复协议队列位置，绝不交给下一请求。
- 普通连接上的 RESP3 `Attribute(Push)` 会在进入 `pending` 前分流；Pub/Sub
  专用连接把订阅/退订 Push 确认匹配到对应请求，把消息分发给 listener。
- 连接在任何命令字节写出前失败时返回 `BobaStrawCommandNotSentException`；写出
  过任意字节后失败时返回 `BobaStrawCommandMayHaveExecutedException`；两种情况均不重试。
- 以上路径由回环假 Redis 测试覆盖，并通过完整 `mvn test` 回归。

### 阶段 2 验收（已完成）

- `BobaStrawClientResources` 可配置固定数量的 selector 线程；默认 Client 资源使用
  一个线程，外部 Resources 可被多个 Client 共享。
- 一个 `NioConnection` 固定绑定到一个 loop。连接的 connect、SelectionKey、读写、
  握手、空闲检查、关闭及 FIFO 队列仍只由该 loop 修改。
- Standalone 重连、事务池、Pub/Sub 专用连接、Cluster seed/拓扑节点连接均经由
  `NioConnectionFactory` 创建，不能回退到“一连接一线程”。
- 单条连接断开只终止该连接；同一 EventLoop 上的其他连接继续服务。外部 Resources
  的 Client 相互关闭隔离；关闭 Resources 会使在途请求终止并拒绝后续命令。
- 上述生命周期语义由 `BobaStrawClientResourcesTest` 与既有协议/Cluster 回归覆盖，
  并通过完整 `mvn test`。

### 阶段 3 验收（已完成）

- 每个连接仅分配一次 16 KiB heap 读缓冲，并复用 gathering write 所需的 buffer / request
  数组；不会在每次 socket read 时创建临时 `ByteBuffer`。
- 单轮任务、读、写和已解码响应均有内部预算：256 个任务、64 KiB 读、32 帧 / 64 KiB
  写、64 个完整响应。已缓存响应会驱动 `selectNow()`，不会等待固定 selector 超时。
- 写入状态只在实际 position 前进后变为 `WRITING`；部分帧、取消、临时写 limit 恢复和
  `outbound -> pending` 推进均保持物理连接 FIFO。写 syscall 异常的参与帧按“可能已执行”
  保守失败，绝不自动重试。
- `NioConnectionIoTest` 以小预算验证大帧 + 后续命令的 RESP 帧完整性和顺序，并验证
  响应 burst 每个 EventLoop turn 只分发预算内的响应；同一 loop 上繁忙 burst 也会让出
  已就绪的另一连接。既有碎片协议与取消测试继续回归。

### 阶段 4 验收（已完成）

- `RespCodec.Decoder` 通过显式 frame stack 解析 RESP2/RESP3 aggregate，不使用递归；
  累积输入采用 compact buffer，Bulk payload 以流式状态写入最终值，避免碎片输入的整段
  拼接与已完成节点的反复解析。
- 解析器严格校验 line 的 `CRLF`、Bulk trailer、RESP3 Null、Boolean 与 Verbatim 结构。
  任意协议格式错误或资源超限使 decoder 进入终止状态，连接层关闭相应 socket。
- `RespLimits` 保护 buffer、顶层回复、Bulk、line、aggregate depth 和累计元素数；默认值
  可由 `BobaStrawClient.Builder.respLimits(...)` 与
  `BobaStrawClusterClient.Builder.respLimits(...)` 覆盖，并完整传入重连、事务、Pub/Sub 和
  Cluster 节点连接。
- 单元测试覆盖逐字节/任意边界碎片的嵌套 Attribute、连续 Push/普通回复、大 Bulk、输入
  数组复用、非法 wire 和所有资源限制边界；socket 测试验证协议超限关闭连接并保留已写命令
  的“可能已执行”语义。完整 `mvn test` 回归后才可进入阶段 5。

### 阶段 5A 验收（已完成）

- 每个 `NioEventLoop` 使用自己的可取消 deadline 队列，并在 selector 等待前以最近 deadline
  计算等待时间；到期任务和普通 NIO I/O 都只在该 EventLoop 上执行。
- 普通命令、握手命令和空闲 PING 共享同一请求 deadline 模型。取消或超时的已写请求保留
  `CANCELLED_DRAINING` 响应占位，不能让后续响应错配。
- deadline 的取消、关闭和 generation 防护为后续连接 lifecycle 调度提供统一基础；不会
  重发失败或超时命令。
- `NioEventLoopDeadlineTest` 覆盖 deadline 所属线程、取消和 EventLoop 存活；协议 socket
  测试覆盖命令 deadline。

### 阶段 5B 验收（已完成）

- `BobaStrawClientResources` 管理独立、有界的 callback worker；`CompletionStage` 的应用
  continuation 不再在 NIO Selector 线程执行。结果 slot 在命令发送前预留，饱和时返回
  `BobaStrawBackpressureException` 且不写 socket。
- 每个有 push listener 的连接拥有串行 callback dispatcher，保持同一 Pub/Sub 连接的消息顺序。
  慢 listener 耗尽容量时主动关闭该专用连接；其生命周期 listener 会从 Client 的专用连接集合
  移除，避免死连接残留。
- `BobaCallbackDispatcherTest` 覆盖容量预留和串行顺序；`BobaStrawClientResourcesTest` 覆盖
  阻塞业务 continuation 不阻塞共享 EventLoop；协议 socket 测试覆盖 Pub/Sub callback 线程和
  慢消费者关闭语义。完整 `mvn test` 回归后才可继续阶段 5C。

### 阶段 5C 验收（已完成）

- `BobaStrawConnectionLimits` 为每条物理连接设置 `maxInFlightCommands` 和
  `maxQueuedWriteBytes`；Standalone 重连、事务池、Pub/Sub 专用连接和 Cluster 节点连接均传递
  同一 Client-owned 配置。Pipeline 在写入前一次性预留全部命令，任一上限不足时零帧写出。
- 已写取消/超时请求在 `CANCELLED_DRAINING` 中继续占用 command slot，直到对应回复消费；排队
  取消和超时、断连和正常回复均正确归还 reservation。待写字节仅在实际 socket write 后归还。
- 共享连接使用 close/ready lifecycle 而非固定轮询；失败候选按 capped exponential backoff 重建。
  Client 不重放、迁移或隐藏已失败命令，并通过 `BobaStrawClientMetrics` 暴露连接状态与累计指标；
  正字节 socket read/write 次数和字节数描述当前物理连接，replacement 后从零开始。
- 派生的 String、binary 和 Pub/Sub `CompletionStage` 取消会传播回底层请求；同步 facade 直接等待
  transport completion。UNSUBSCRIBE ACK 后立即释放专用连接的 socket/Selector；Pub/Sub 串行 callback
  barrier 继续先交付 ACK 前已经解码的消息，再关闭 callback stream，不让慢 listener 持有物理连接。
  Client 在排空期间关闭时会终止该 callback stream，不能在关闭后继续启动排队 listener。
- `BobaStrawConnectionLifecycleTest` 覆盖容量拒绝、Pipeline 原子准入、取消后的响应占位和 capped
  reconnect；`BobaStrawClientResourcesTest` 覆盖同步 API 不受阻塞 callback 影响及派生 Future 取消；
  `BobaStrawProtocolNegotiationTest` 覆盖退订 barrier。完整 `mvn test` 已回归。

### 阶段 6 性能验收（已完成，2026-09-15）

- Redis critical 正式 ABBA 已完成：阶段 2 基线 `ca078f4` 与候选 `7a2fe41` 使用同一份、在
  baseline API 上编译的 harness，按 A/B/B/A 顺序运行。异步窗口吞吐改善 2.30 倍、Pipeline
  吞吐改善 1.29 倍、慢回调隔离平均延迟改善 3.59 倍；原始数据和环境见
  [`benchmark result`](../benchmarks/results/20260905-ca078f4-vs-7a2fe41-redis-critical/summary.md)。
- Codec 正式 ABBA 已完成：逐字节碎片解码吞吐改善 2.54 倍且 allocation 改善 559.57 倍，
  128 回复 burst 吞吐改善 1.94 倍。初次 GET 编码 allocation 差异经对照不能归因于版本；
  后续精确尺寸编码将 allocation 稳定降至 144 B/op，吞吐配对改善 1.82 倍。
  原始数据见 [`codec result`](../benchmarks/results/20260905-ca078f4-vs-7a2fe41-codec/summary.md)。
  修复验证见 [`encoder result`](../benchmarks/results/20260906-7a2fe41-vs-da546da-codec-encode/summary.md)。
- 当前归档覆盖 Redis critical、Codec，以及 Redis 7.4.2 与 Valkey 8.1.3 全网络 baseline；结果见
  [`redis result`](../benchmarks/results/20260906-9b3f116-redis-full/summary.md) 和
  [`valkey result`](../benchmarks/results/20260906-0cbf813-valkey-full/summary.md)。两个服务端的
  `byte[]` 大 value 均确认内核约为 1x payload copy，String 的第 2x 主要来自 UTF-8/String 转换；
  Valkey 的独立 binary 结果见
  [`binary result`](../benchmarks/results/20260906-b9ceff7-valkey-binary-large/summary.md)。
- `TransportObservationBenchmark` 与 `redis-observe`/`valkey-observe` runner 已提供当前物理连接的
  socket 次数/字节辅助计数，以及 fork JVM 和容器的系统采样。Redis 正式结果已归档于
  [`redis observation`](../benchmarks/results/20260907-9dfa609-redis-observe/summary.md)，Valkey 正式结果已归档于
  [`valkey observation`](../benchmarks/results/20260907-9ece1c7-valkey-observe/summary.md)。两者都确认
  Pipeline 128 精确命中 32 commands/write。instrumentation 隔离 ABBA 未观察到可分辨的实质
  吞吐或 allocation 回归，结果见
  [`instrumentation ABBA`](../benchmarks/results/20260907-c4d9898-vs-9dfa609-transport-overhead/summary.md)。
  128-frame 候选的吞吐明显提升，但 shared EventLoop 公平性退化，已按
  [`gathering-write ABBA`](../benchmarks/results/20260908-b9de0a0-vs-0f3506a-gathering-write-128/summary.md)
  暂不采用。64-frame 正式 ABBA 的健康连接平均/P99 延迟下降约 8.2%/15.4%，繁忙连接
  完成量提高约 8.0%，Pipeline 吞吐整体持平；最终采用 64 frames / 64 KiB，见
  [`64-frame ABBA`](../benchmarks/results/20260915-b9de0a0-vs-2214adb-gathering-write-64/summary.md)。
  Async 吞吐配对方向不一致，不宣称确定收益。参数校准在 Redis 7.4.2、JDK 21、macOS/Colima
  上完成，Valkey 全量与系统观测属于此前基线；其他 JDK/平台及最终参数的 Valkey A/B 不在本轮结论内。
- 确定性网络故障注入通过 `fault-injection` JUnit 标签独立执行，覆盖 wire 分片、部分写预算、
  回复 burst、写后断连、取消/超时 drain、连接隔离、慢 Pub/Sub listener 与退订竞态。矩阵和
  复跑命令见 [`fault-injection`](../testing/fault-injection.md)。

- 先探测本机 JDK、Colima 与容器运行状况；缺少的 JDK、JMH 构建依赖、Redis / Valkey
  镜像和观测工具可直接安装。环境版本、镜像 digest、CPU 核数、内存、JVM 参数与命令必须
  写入 `docs/benchmarks/`，使结果可复跑。
- 同时保留阶段 2 提交 `ca078f4` 和网络模型最终提交的基线，分别在 Redis 与 Valkey 上
  运行同一组工作负载：单命令 GET / SET、1/16/128 命令 Pipeline、大 value、碎片响应、
  多 Client 共享一个 EventLoop 的 noisy-neighbor 场景，以及阶段 5 完成后的 Pub/Sub
  慢消费者场景。
- 记录吞吐、P50/P95/P99/P999 延迟、分配率、GC、CPU、线程数、socket read/write 次数和
  每连接完成量；公平性以繁忙连接与健康连接的完成量和尾延迟共同判断，不能只报平均值。
- 每个 JMH workload 至少包含 warmup、多个 measurement fork 和原始 JSON/文本输出；
  网络端到端压测另保留客户端 / server 侧指标。没有完成这些步骤前，不对吞吐或延迟作
  生产性能承诺。
