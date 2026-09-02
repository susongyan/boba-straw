# Boba Straw

> Redis client with a straw — sip your data like bubble tea.

Boba Straw is a lightweight, pure Java Redis and Valkey client. It uses a Java NIO execution core and exposes synchronous and `CompletionStage` APIs without Reactor, RxJava, Netty, or Spring dependencies in the core artifact.

## Current status

`0.1.0-SNAPSHOT` provides a standalone NIO client with RESP2/RESP3 negotiation and synchronous/`CompletionStage` APIs. Key and String coverage includes conditional/expiring `SET`, `MGET`/`MSET`, counters, range and bit operations, expiry management, rename and type commands; Hash, List, Set and sorted-set currently provide their basic operations. Sentinel, Cluster, TLS, Pub/Sub, transactions and scripts are not yet production-ready.

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

普通命令默认按每个 Redis 节点复用一个共享多路复用连接，不需要配置连接池大小。事务、Pub/Sub 和阻塞命令使用独立连接。

共享连接默认不发送主动心跳；如需检测长时间空闲连接，可启用：

```java
BobaStrawClient.builder()
    .idlePingInterval(Duration.ofSeconds(30))
    .build();
```

只有连接空闲超过该间隔时才会发送 PING；业务流量活跃时不会额外发送心跳。

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
