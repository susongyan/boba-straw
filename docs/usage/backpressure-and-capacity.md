# 背压与连接容量规划

本文面向使用 Boba Straw 的应用开发者，说明客户端为什么需要背压、默认容量是否会限制
Redis 吞吐，以及如何根据业务负载判断是否需要调整。

## 背压保护什么

Boba Straw 的背压首先保护客户端，而不是限制 Redis 的处理能力。普通命令默认复用每个
Redis 节点的一条物理连接；如果调用方持续提交命令，而网络、Redis 或结果消费速度暂时跟不上，
无限接收请求会使客户端内部队列持续增长。

当前设计主要保护以下边界：

1. **堆内存**：限制尚未写入 socket 的编码命令帧，避免大命令或网络阻塞导致 outbound
   数据无限堆积。
2. **待响应请求数**：限制已经准入但尚未排空响应的命令，避免 pending request、Future、
   deadline 和相关状态无限增长。
3. **EventLoop 可用性**：有界队列与单轮服务预算共同避免某条繁忙连接长期独占网络线程，
   影响同一 EventLoop 上的其他连接。
4. **RESP 响应匹配正确性**：已开始写入的请求即使被取消或超时，也会保留 FIFO 占位，直到
   对应响应到达并被排空，防止响应错误匹配给下一条命令。
5. **结果交付能力**：有界 callback dispatcher 防止慢 `CompletionStage` continuation 或
   Pub/Sub listener 把业务回调无限积压到内存中。
6. **故障影响范围**：连接断开时，所有尚未完成的请求都可能同时失败。限制 in-flight 数量也
   限制了单次连接故障的影响面。

背压采用 fail-fast，而不是阻塞提交线程：容量不足时返回
`BobaStrawBackpressureException`，且该命令不会发送到 Redis。客户端不会自动重试；调用方可根据
自身业务语义决定限速、稍后重试或直接失败。

## 默认容量

每条物理连接默认限制为：

| 配置 | 默认值 | 统计范围 | 释放时机 |
| --- | ---: | --- | --- |
| `maxInFlightCommands` | 4,096 | 已准入且响应尚未排空的应用命令 | 对应响应排空，或明确未发送的请求终止 |
| `maxQueuedWriteBytes` | 16 MiB | 尚未被 socket 接受的编码命令字节 | 随实际 socket 写入逐步释放 |

握手和内部空闲 PING 不占用应用命令预算。事务、Pub/Sub、阻塞命令使用专用连接；Cluster
则按节点分别维护和计算连接容量。

`maxInFlightCommands` 不是每秒吞吐量限制。它限制的是某个时刻一条连接上未完成的命令数，
而不是 Redis 每秒最多执行多少命令。

## 是否会限制服务端吞吐

只有当连接所需的并发在途请求超过容量时，`maxInFlightCommands` 才可能成为客户端侧吞吐
边界。可以使用 Little's Law 做近似估算：

```text
所需 in-flight ≈ 单连接目标吞吐量（次/秒）× 请求响应时间（秒）
```

示例：

| 单连接目标吞吐 | 响应时间 | 估算所需 in-flight |
| ---: | ---: | ---: |
| 50,000 次/秒 | 1 ms | 50 |
| 100,000 次/秒 | 5 ms | 500 |
| 200,000 次/秒 | 10 ms | 2,000 |
| 200,000 次/秒 | 30 ms | 6,000 |

前三种负载在估算上没有触及默认 4,096；最后一种可能产生本地背压。但这里的响应时间应使用
客户端观测到的端到端时间，并在稳定负载下结合 P99，而不能只使用 Redis 服务端命令执行时间。

提高上限也不一定提升吞吐。Redis 在一条连接内按顺序读取命令和返回响应，过深的 pipeline
可能只会增加：

- 客户端内存占用和排队时间；
- 慢命令造成的队头阻塞；
- 超时后等待响应排空的 `CANCELLED_DRAINING` 请求；
- 连接断开时同时失败的请求数量；
- P99/P999 尾延迟。

因此，4,096 是安全边界，不是推荐应用始终填满的目标值。

## 如何判断默认值是否成为瓶颈

不要只根据 Redis CPU 尚有余量就提高容量。先同时观察客户端和服务端：

```java
BobaStrawClientMetrics metrics = client.metrics();
System.out.println(metrics.inFlightCommands());
System.out.println(metrics.queuedWriteBytes());
System.out.println(metrics.connectionBackpressureRejections());
```

默认值可能正在限制负载的典型信号是：

- `inFlightCommands()` 长期接近 4,096；
- `connectionBackpressureRejections()` 持续增长；
- Redis CPU、网络和命令处理能力仍有明显余量；
- 小幅提高容量后，吞吐增加且错误率、堆内存和 P99 延迟仍可接受。

如果背压拒绝数为零，`maxInFlightCommands` 就没有限制当前吞吐。若
`queuedWriteBytes()` 先接近 16 MiB，则瓶颈更可能是大命令、网络写入速度或
`maxQueuedWriteBytes`，而不是命令数量。

## 容量估算与调参

开发阶段无法准确评估时，建议保留默认值，通过压测和生产指标校准。初始估算可以使用：

```text
建议 maxInFlightCommands
  ≈ 单连接峰值吞吐量 × 客户端 P99 响应时间 × 1.5～2 安全系数
```

例如，单连接峰值为 100,000 次/秒、客户端 P99 为 20 ms：

```text
100,000 × 0.020 = 2,000
2,000 × 1.5～2 = 3,000～4,000
```

默认 4,096 基本覆盖该负载。需要提高时应采用小步调整并重新压测：

```java
BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
    .maxInFlightCommands(8_192)
    .maxQueuedWriteBytes(32L * 1024L * 1024L)
    .build();

BobaStrawClient client = BobaStrawClient.builder()
    .uri("redis://localhost:6379")
    .connectionLimits(limits)
    .build();
```

调高命令数量时必须同时评估平均命令帧大小和待写字节预算，否则可能先触发 16 MiB 的默认
写入容量。也应验证堆内存、GC、超时率、P99/P999 延迟以及断连时的失败规模。

如果单连接已经出现明显队头阻塞，提高 in-flight 通常不是最优解。应先检查慢命令、大响应、
Redis 服务端延迟和网络瓶颈；只有事务、Pub/Sub 和阻塞命令等需要状态隔离的场景才使用专用连接。
不要仅为了绕开背压而无界增加连接。

## 相关但不同的限制

以下 EventLoop 数值是公平性预算，不是并发容量，也不会直接拒绝命令：

- 每轮最多处理 256 个跨线程任务；
- 每连接每轮最多读取 64 KiB；
- 每连接每轮最多写入 64 KiB 或聚合 32 帧；
- 每连接每轮最多分发 64 个完整 RESP 响应。

超过这些单轮预算的工作会留到下一轮继续处理。真正执行准入拒绝的容量主要是
`maxInFlightCommands`、`maxQueuedWriteBytes` 和 Resources 级 callback capacity。

callback capacity 与连接容量彼此独立：前者保护应用结果消费，后者保护 socket 的请求数量和
待写内存。普通异步命令在发送前会同时取得所需 reservation；任何一层容量不足都会明确失败且
不发送命令。Pub/Sub 消息无法事先拒绝，慢 listener 耗尽容量时会关闭订阅专用连接，而不是
静默丢弃消息。
