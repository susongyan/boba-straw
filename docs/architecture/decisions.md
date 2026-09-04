# Architecture decisions

## Runtime baseline

`boba-straw-core` targets Java 8 and has no third-party runtime dependency. Java NIO is the only transport foundation. Java 21 virtual threads may call the blocking facade but are not part of the public baseline.

## Protocol

The decoder has one RESP value model. RESP2 is a subset; RESP3 Push, Attribute, Blob Error, Verbatim String and Big Number values are parsed. Attribute values are unwrapped only after they have been kept separate from Push messages, so they cannot shift normal request-response FIFO matching. The decoder is an explicit incremental state machine with a compact input buffer and a non-recursive aggregate frame stack. `RespLimits` is enforced at this boundary; malformed or oversized replies terminate the physical connection rather than being truncated or retried.

## Failure semantics

The client does not automatically retry commands. A timeout or disconnect after a write may mean Redis executed the command; callers must not treat it as a safe negative acknowledgement.

## Connection model

普通命令默认使用每个 Redis 节点一个共享的 NIO 多路复用连接，不要求业务配置连接池大小。事务、Pub/Sub 和阻塞命令使用独占连接；未来只为这些状态型场景提供可选专用连接池。Cluster 模式按节点分别维护共享连接。

共享连接使用 `BobaStrawConnectionLimits` 做每物理连接的准入保护：默认上限为 4,096 个
未排空响应的应用命令和 16 MiB 尚未写入 socket 的编码命令帧。这与 Resources 级 callback
容量相互独立；前者限制单条连接的请求/内存，后者限制应用结果交付。业务线程只在提交
EventLoop task 前取得很短的 reservation，协议队列和写入进度仍只由 EventLoop 修改。

Standalone 共享连接的 `CONNECTING -> READY -> BACKING_OFF -> CONNECTING` lifecycle 由连接
close/ready 事件驱动。失败候选按 `reconnectInterval` 至 `reconnectMaxInterval` 的 capped
exponential backoff 重建；BACKING_OFF 中的新调用明确以“未发送”失败。重连永不迁移、重放或
掩盖已失败命令，`BobaStrawClientMetrics` 只提供无网络 I/O 的观测快照。

网络线程、连接状态所有权、取消语义和性能演进见
[`network-model.md`](network-model.md)。该文档规定连接内状态最终由所属 EventLoop
独占；命令取消后仍必须保留已发送请求的响应占位。

`internal.NioConnection` 与 `internal.TransactionConnectionPool` 历史上曾暴露 public
构造器。为保持二进制兼容，它们保留为 `@Deprecated` 兼容入口，并只在被直接使用时创建
私有单 loop 资源；Boba Straw 的普通 Client、事务、Pub/Sub 和 Cluster 路径一律使用
`BobaStrawClientResources` 的共享 EventLoopGroup。后续大版本才能移除这些 internal
兼容入口。

## Current delivery boundary

Standalone 是当前唯一达到基础验收的连接拓扑。Cluster 已有实验性的 seed 发现、slot 路由和
单次 MOVED/ASK 跳转，但尚未具备周期拓扑刷新、故障摘除、跨 Slot 规则和完整 reconnect
管理，因此不得作生产支持承诺。Sentinel 和 TLS 仍未实现。
