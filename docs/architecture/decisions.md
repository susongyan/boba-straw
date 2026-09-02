# Architecture decisions

## Runtime baseline

`boba-straw-core` targets Java 8 and has no third-party runtime dependency. Java NIO is the only transport foundation. Java 21 virtual threads may call the blocking facade but are not part of the public baseline.

## Protocol

The decoder has one RESP value model. RESP2 is a subset; RESP3 Push, Attribute, Blob Error, Verbatim String and Big Number values are parsed. Attribute values are unwrapped only after they have been kept separate from Push messages, so they cannot shift normal request-response FIFO matching.

## Failure semantics

The client does not automatically retry commands. A timeout or disconnect after a write may mean Redis executed the command; callers must not treat it as a safe negative acknowledgement.

## Connection model

普通命令默认使用每个 Redis 节点一个共享的 NIO 多路复用连接，不要求业务配置连接池大小。事务、Pub/Sub 和阻塞命令使用独占连接；未来只为这些状态型场景提供可选专用连接池。Cluster 模式按节点分别维护共享连接。

网络线程、连接状态所有权、取消语义和性能演进见
[`network-model.md`](network-model.md)。该文档规定连接内状态最终由所属 EventLoop
独占；命令取消后仍必须保留已发送请求的响应占位。

`internal.NioConnection` 与 `internal.TransactionConnectionPool` 历史上曾暴露 public
构造器。为保持二进制兼容，它们保留为 `@Deprecated` 兼容入口，并只在被直接使用时创建
私有单 loop 资源；Boba Straw 的普通 Client、事务、Pub/Sub 和 Cluster 路径一律使用
`BobaStrawClientResources` 的共享 EventLoopGroup。后续大版本才能移除这些 internal
兼容入口。

## Current delivery boundary

The initial implementation is standalone only. Public topology promises must not be documented as supported until Sentinel and Cluster routing are implemented and tested.
