# Architecture decisions

## Runtime baseline

`boba-straw-core` targets Java 8 and has no third-party runtime dependency. Java NIO is the only transport foundation. Java 21 virtual threads may call the blocking facade but are not part of the public baseline.

## Protocol

The decoder has one RESP value model. RESP2 is a subset; RESP3 Push, Attribute, Blob Error, Verbatim String and Big Number values are parsed. Attribute values are unwrapped only after they have been kept separate from Push messages, so they cannot shift normal request-response FIFO matching. The decoder is an explicit incremental state machine with a compact input buffer and a non-recursive aggregate frame stack. `RespLimits` is enforced at this boundary; malformed or oversized replies terminate the physical connection rather than being truncated or retried.

## Failure semantics

The client does not automatically retry commands. A timeout or disconnect after a write may mean Redis executed the command; callers must not treat it as a safe negative acknowledgement.

2026-09-29 Lua L2 实现的显式例外：使用注册脚本执行器时，首次 EVAL，
有目标节点缓存提示时 EVALSHA，仅明确 NOSCRIPT 可恢复一次 EVAL。直接命令仍不自动回退，
超时/断连/其他错误不恢复；脚本不得伪造 NOSCRIPT。这不是通用网络重试策略，详见
[Lua 设计与流程图](lua-scripting.md)。

## Connection model

普通命令默认使用每个 Redis 节点一个共享的 NIO 多路复用连接，不要求业务配置连接池大小。
事务使用懒加载有界池；成功 EXEC 或显式放弃时 UNWATCH 确认后归还，取消/失败/未完成 close
均销毁。池借用不持有 Client 锁，空闲回收使用共享 EventLoop 定时任务。
Pub/Sub 使用独占连接；三拓扑 String BLPOP/BRPOP 使用有并发上限的按需单次连接，不入池，
结束或取消即关闭。其他阻塞命令仍未完成。Cluster 普通命令按节点共享连接。

共享连接使用 `BobaStrawConnectionLimits` 做每物理连接的准入保护：默认上限为 4,096 个
未排空响应的应用命令和 16 MiB 尚未写入 socket 的编码命令帧。这与 Resources 级 callback
容量相互独立；前者限制单条连接的请求/内存，后者限制应用结果交付。业务线程只在提交
EventLoop task 前取得很短的 reservation，协议队列和写入进度仍只由 EventLoop 修改。

Standalone 共享连接的 `CONNECTING -> READY -> BACKING_OFF -> CONNECTING` lifecycle 由连接
close/ready 事件驱动。失败候选按 `reconnectInterval` 至 `reconnectMaxInterval` 的 capped
exponential backoff 重建；BACKING_OFF 中的新调用明确以“未发送”失败。重连永不迁移、重放或
掩盖已失败命令，`BobaStrawClientMetrics` 只提供无网络 I/O 的观测快照。socket read/write 指标
描述当前共享物理连接并在 replacement 后归零，避免把不同连接的系统调用混成一个累计值。

网络线程、连接状态所有权、取消语义和性能演进见
[`network-model.md`](network-model.md)。该文档规定连接内状态最终由所属 EventLoop
独占；命令取消后仍必须保留已发送请求的响应占位。

`internal.NioConnection` 与 `internal.TransactionConnectionPool` 历史上曾暴露 public
构造器。为保持二进制兼容，它们保留为 `@Deprecated` 兼容入口，并只在被直接使用时创建
私有单 loop 资源；Boba Straw 的普通 Client、事务、Pub/Sub 和 Cluster 路径一律使用
`BobaStrawClientResources` 的共享 EventLoopGroup。后续大版本才能移除这些 internal
兼容入口。

## Current delivery boundary

### Primary-only routing

2026-09-29：不规划客户端读写分离或 Replica 读策略。Cluster/Sentinel 普通读写均选择
当前主节点，不提供副本读取偏好或主节点不可用时降级读副本的策略。这样避免引入副本读取的
额外陈旧数据语义和配置复杂度，但不承诺消除故障切换时的数据丢失或未知执行结果。
该决定不取消服务端复制和故障切换支持：副本晋升为主节点后仍按新拓扑访问。
Standalone 使用调用方配置的端点，不因该决定自动发现或校验其主从角色。
读写分离不作为 v1 或当前发布的验收前提；如未来需求改变，需重新作架构决策。

### Implemented scope

Standalone 已达到基础验收。Cluster 普通主节点命令具有节点退避重连、周期/事件拓扑发现、
非 seed 旧节点摘除、已知多 Key 同 Slot 校验和单次 MOVED/ASK；ASK 使用单次专用连接，
不污染共享连接状态和永久 Slot 映射。完整边界见 [cluster-topology.md](cluster-topology.md)。
C6 增加单 Slot Pipeline/事务、BLPOP/BRPOP 与经典 Pub/Sub；不等于生产长稳验收。
Sentinel 普通主节点命令通过独立入口实现：重新发现、同连接 ROLE 校验、两套认证与旧连接退休；
见 [sentinel-topology.md](sentinel-topology.md)。C6 专用组合绑定主节点代次，失败不迁移/重放。
C7 已接入 SSLEngine TLS 和三拓扑配置传播，并完成本机 JSSE、真实 TLS 服务矩阵及
确定性 I/O 故障验收；不能等同生产长稳承诺。设计与边界见 network-model 的 C7 节。

## Command surface

C5 以主要数据结构的高频 API 为目标，不追求全量 Redis 命令。CommandRegistry 提供内部
Key/连接模式元数据，Typed、特殊能力与 Raw 共享既有执行内核；三层边界见
[command-model.md](command-model.md)。已知状态/阻塞命令不能经普通 Raw/Pipeline 或事务
普通 command 入队绕过专用生命周期；未知普通 Raw 出口由调用方核实副作用，Cluster 显式全部 Key。
