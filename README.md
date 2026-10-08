# Boba Straw

Licensed under [Apache-2.0](LICENSE). See [NOTICE](NOTICE) for project notices.

> Redis client with a straw — sip your data like bubble tea.

Boba Straw is a lightweight, pure Java Redis and Valkey client. It uses a Java NIO execution core and exposes synchronous and `CompletionStage` APIs without Reactor, RxJava, Netty, or Spring dependencies in the core artifact.

Spring Boot integration includes topology-aware default clients, named clients, TLS store configuration,
and optional Actuator health and Micrometer gauges. See the [usage guide](docs/usage/quickstart.md#spring-boot-配置)
and [tested version boundaries](docs/implementation/core-completion-plan.md).
Core remains Java 8 compatible and JDK-only; publishing is disabled until release prerequisites are met.

## Current status

应用研发先看[接入指南](docs/usage/quickstart.md)、[普通命令、批量与分页](docs/usage/commands.md)
和[能力表](docs/usage/supported-features.md)。普通命令直接调用 sync()/async()/binary()；
typed() 仅用于 Pipeline/事务的类型化入队，不是全局开关。

维护者使用 AI 扩展 Redis 命令时，见[命令扩展开发指南](docs/development/command-extension-guide.md)
及仓库内的 [Command Development Skill](.agents/skills/boba-straw-command-development/SKILL.md)。

C5 聚焦主要数据结构的高频 API，不追求全 Redis 命令覆盖。三层接口与命令元数据设计见
[命令模型与演进](docs/architecture/command-model.md)，实际实现/测试范围见
[命令覆盖清单](docs/implementation/command-coverage.md)。

`0.1.0-alpha.1` is published on Maven Central. It provides a standalone NIO client with RESP2/RESP3 negotiation and synchronous/`CompletionStage` APIs. Key and String coverage includes conditional/expiring `SET`, `MGET`/`MSET`, counters, range and bit operations, expiry management, rename and type commands; Hash, List, Set and sorted-set currently provide their basic operations. Pipeline, dedicated transaction/Pub/Sub connections and scripts have basic implementations. Cluster and Sentinel include primary routing, topology recovery and the dedicated-command combinations listed in the capability table. Core SSLEngine TLS has local JSSE, deterministic I/O fault and real Redis/Valkey coverage, including TLS Cluster/Sentinel and dedicated connections. Known performance regressions, production endurance and broader platform validation remain pending; see the capability table and roadmap for tested scope.

```java
try (BobaStrawClient client = BobaStrawClient.builder().uri("redis://localhost:6379").build()) {
    client.sync().set("tea", "boba");
    String value = client.sync().get("tea");
}
```

To force RESP2 (for example when connecting through an older proxy), configure:

```java
BobaStrawClient.builder()
    .uri("redis://localhost:6379")
    .protocol(ProtocolVersion.RESP2)
    .build();
```

默认 decoder 会限制单条回复、Bulk、嵌套层数和 aggregate 元素数，防止异常服务端回复占满
客户端内存。需要读取较大的 value 或集合时，可以显式提高限制；格式错误或超限回复会关闭
该物理连接，已写命令仍按“可能已执行”报告，不会自动重试：

```java
RespLimits limits = RespLimits.builder()
    .maxResponseBytes(128 * 1024 * 1024)
    .maxBulkLength(96 * 1024 * 1024)
    .maxAggregateElements(200_000)
    .build();

BobaStrawClient client = BobaStrawClient.builder()
    .uri("redis://localhost:6379")
    .respLimits(limits)
    .build();
```

普通命令默认按每个 Redis 节点复用一个共享多路复用连接，不需要配置连接池大小。
事务与 Pub/Sub 使用独立连接；Standalone 的 `sync()/async().blpop/brpop` 也使用按需单次
专用连接。阻塞连接默认并发上限 32，可用 `maxBlockingConnections(...)` 调整。
事务支持 try-with-resources，取消/异常时销毁租约；使用约束见
[生命周期指南](docs/usage/lifecycle.md)。其他阻塞命令不得直接发送到共享 Raw/Pipeline。

每条物理连接默认最多接纳 4,096 条尚未排空响应的应用命令和 16 MiB 尚未写入 socket 的
编码命令帧。超过任一上限会立即得到 `BobaStrawBackpressureException`，命令不会发送到 Redis。
通常无需调整；只有在清楚知道单连接并发和大 Pipeline 内存预算时才显式设置。默认值是否限制
服务端吞吐、容量估算和监控方法见
[`背压与连接容量规划`](docs/usage/backpressure-and-capacity.md)：

```java
BobaStrawConnectionLimits limits = BobaStrawConnectionLimits.builder()
    .maxInFlightCommands(8_192)
    .maxQueuedWriteBytes(32L * 1024L * 1024L)
    .build();

BobaStrawClient client = BobaStrawClient.builder()
    .uri("redis://localhost:6379")
    .connectionLimits(limits)
    .reconnectInterval(Duration.ofSeconds(1))
    .reconnectMaxInterval(Duration.ofSeconds(30))
    .build();
```

共享 Standalone 连接断开后会按上述区间做指数退避重建，但绝不重放已经失败的命令。退避期间
新调用明确以 `BobaStrawCommandNotSentException` 失败，不会为每次调用新建 socket。可通过
无网络 I/O 的状态快照观察它：

```java
BobaStrawClientMetrics metrics = client.metrics();
System.out.println(metrics.sharedConnectionState());
System.out.println(metrics.inFlightCommands());
System.out.println(metrics.queuedWriteBytes());
```

默认每个 Client 自己管理一个 Selector EventLoop。应用中有多个 Client、Cluster 或专用连接时，
可显式共享 `BobaStrawClientResources`；`eventLoopThreads` 是 I/O 线程数量，不是连接池大小。
外部传入的 Resources 由应用在关闭全部 Client 后统一关闭：

```java
try (
    BobaStrawClientResources resources = BobaStrawClientResources.builder()
        .eventLoopThreads(2)
        .callbackThreads(2)
        .callbackQueueCapacity(2048)
        .build();
    BobaStrawClient cache = BobaStrawClient.builder()
        .resources(resources)
        .uri("redis://cache:6379")
        .build();
    BobaStrawClient sessions = BobaStrawClient.builder()
        .resources(resources)
        .uri("redis://sessions:6379")
        .build()
) {
    // clients close before resources, in reverse declaration order
}
```

`callbackThreads` 与 `callbackQueueCapacity` 只负责应用可见的 `CompletionStage` continuation 和
Pub/Sub listener，永不执行 socket I/O。普通命令会在写入前预留一个结果交付位；资源级 callback
容量耗尽时返回 `BobaStrawBackpressureException`，命令不会发往 Redis。Pub/Sub 同一连接保持消息
顺序；慢 listener 耗尽容量时会关闭该专用连接，而不会静默丢弃消息。

callback 容量和 `connectionLimits` 是两层独立保护：前者防止业务 callback 积压，后者防止单条
socket 的请求数和待写内存无界增长。同步 API 直接等待内部 transport 结果，因此不会被繁忙的
callback worker 阻塞。

共享连接默认不发送主动心跳；如需检测长时间空闲连接，可启用：

```java
BobaStrawClient.builder()
    .idlePingInterval(Duration.ofSeconds(30))
    .build();
```

只有连接空闲超过该间隔时才会发送 PING；业务流量活跃时不会额外发送心跳。

网络与 RESP 性能基准使用独立的 JMH 模块，环境、workload 和结果归档规则见
[`性能基准`](docs/benchmarks/README.md)。

事务专用池按需创建，可选配置其上限、获取等待和空闲回收：

```java
BobaStrawClient.builder()
    .transactionPoolMaxSize(8)
    .transactionAcquireTimeout(Duration.ofSeconds(1))
    .transactionIdleTimeout(Duration.ofMinutes(1))
    .build();
```

Cluster 启动发现可同时配置多个 seed。每次构建会随机化本次发现顺序，并依次执行 `CLUSTER SLOTS`；任一 seed 成功即可建立 slot 路由：

```java
BobaStrawClusterClient cluster = BobaStrawClusterClient.builder()
    .seeds("redis-1:6379", "redis-2:6379", "redis-3:6379")
    .build();
```

Cluster 普通命令已有节点退避重连、周期/事件拓扑刷新和同 Slot 多 Key 校验。MOVED 最多
跟随一次，ASK 使用有界单次专用连接，不改变永久 Slot owner；未知命令必须显式声明全部 Key。
Cluster/Sentinel 已提供 String Pipeline/事务、BLPOP/BRPOP 和经典 Pub/Sub；
Cluster 批量与事务限同 Slot，专用操作切换后不迁移、不重放，订阅由业务显式恢复。详见
[Cluster 连接与拓扑](docs/architecture/cluster-topology.md)。

Sentinel 提供独立的 `BobaStrawSentinelClient`：多 Sentinel 发现、两套认证、ROLE 校验、
周期发现与主节点切换，且不重放失败命令。配置与当前限制见
[Sentinel 连接与拓扑](docs/architecture/sentinel-topology.md)。

## Build

```bash
mvn test
```

## Local Redis/Valkey compatibility matrix

With Colima started, launch the local services explicitly:

```bash
./scripts/redis-test-up.sh
```

The script starts Redis 5.0.14, 6.2.14 and 7.4.2 on ports 16379–16381,
plus Valkey 8.1.3 on port 16382. It does not modify any other containers.
Remove just these test containers with:

```bash
./scripts/redis-test-down.sh
```

After the containers report ready, run the opt-in compatibility suite:

```bash
mvn -Dboba.straw.runCompatibility=true test
```
