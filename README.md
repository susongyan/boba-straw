# Boba Straw

> Redis client with a straw — sip your data like bubble tea.

Boba Straw is a lightweight, pure Java Redis and Valkey client. It uses a Java NIO execution core and exposes synchronous and `CompletionStage` APIs without Reactor, RxJava, Netty, or Spring dependencies in the core artifact.

## Current status

`0.1.0-SNAPSHOT` provides a standalone NIO client with RESP2/RESP3 negotiation and synchronous/`CompletionStage` APIs. The currently covered commands are `PING`, `GET`, `SET`, `DEL`, `EXISTS`, `EXPIRE`, `TTL`, `INCR`, Hash, List, Set and sorted-set basics. Sentinel, Cluster, TLS, Pub/Sub, transactions and scripts are not yet production-ready.

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
