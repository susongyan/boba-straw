# Boba Straw 接入指南

本资料面向应用研发，使用公开 API；实现状态见 [能力表](supported-features.md)，审查规则见
[用法检查](review-checklist.md)。SDK `0.1.0-alpha.1` 已发布到 Maven Central，采用 Apache-2.0。
当前为 Alpha，性能回归与生产长稳验证仍有待办；上线前应结合业务负载自行验证。

## 添加依赖

核心最低 Java 8，只依赖 JDK，不要求 Spring 或响应式库。可直接从 Maven Central 获取：

```xml
<dependency>
  <groupId>io.github.susongyan</groupId>
  <artifactId>boba-straw-core</artifactId>
  <version>0.1.0-alpha.1</version>
</dependency>
```

当前仓库没有独立 examples 模块或 usage-examples profile，离线示例包也尚未发布。
下方是可编译的调用示例，不是已提供的独立 Maven 示例工程。

## 普通命令直接调用

不需要开启 typed 模式，也不需要调用 `.typed()`。sync() 返回结果，async() 返回 CompletionStage；
binary() 接收原始 byte[]，同样返回 CompletionStage。详细用法见[命令、批量与分页](commands.md)。

```java
import io.github.susongyan.bobastraw.BobaStrawClient;
import io.github.susongyan.bobastraw.ProtocolVersion;
import java.time.Duration;

public class QuickStart {
    public static void main(String[] args) {
        String uri = System.getenv("BOBA_REDIS_URI");
        if (uri == null || uri.isEmpty()) {
            throw new IllegalArgumentException("Set BOBA_REDIS_URI to a test instance");
        }
        // Short-lived CLI only; a service should create one client at startup and reuse it.
        try (BobaStrawClient client = BobaStrawClient.builder()
                .uri(uri).protocol(ProtocolVersion.AUTO)
                .commandTimeout(Duration.ofSeconds(2)).build()) {
            String pong = client.sync().ping();
            if (!"PONG".equals(pong)) {
                throw new IllegalStateException("Unexpected PING response");
            }
        }
    }
}
```

不要输出含凭据的 URI。长期服务不要每次请求创建 Client；异步工作结束之前不要关闭 Client。
AUTO 先尝试 HELLO 3，在明确不支持时回退 RESP2；认证失败不是协议不支持。
需强制 RESP2 时改用 `.protocol(ProtocolVersion.RESP2)`，不需要按服务端版本自行判断。

## 选择入口

| 场景 | 用法 | 当前范围 |
| --- | --- | --- |
| 普通命令 | sync().get / async().get / binary().get 等 | 三拓扑 String sync/async 与 binary async |
| 批量发送 | pipeline().typed() 入队，executeTyped() 提交 | 三拓扑 String；Cluster 整批同 Slot；非原子操作 |
| 事务 | transaction().typed() 入队，execTyped() 提交 | 三拓扑 String；Cluster 使用 transaction(routingKey)；使用 try-with-resources |
| 游标分页 | scan().scan/hscan/sscan/zscan | 异步 String；Cluster 仅后三种单 Key 扫描 |
| 未封装普通命令 | executeAsync 等 Raw 出口 | 核对 Key、连接状态和拓扑限制，不使用 internal 包 |

Pipeline 和事务的 typed() 仅选择类型化入队接口，返回句柄而非 Future；不是全局开关。
完整支持范围以[能力表](supported-features.md)为准；错误、取消和不确定执行见[失败处理](failures-and-retries.md)。

## Spring Boot 配置

```yaml
boba:
  straw:
    uri: ${BOBA_REDIS_URI}
    command-timeout: 2s
    protocol: AUTO
```

原有 uri、command-timeout、protocol 配置继续有效。密码由外部密钥管理注入，
不要提交带真实凭据的 URI，也不要打印 URI。AUTO 使用 HELLO 3，显式 RESP2 跳过 HELLO。
使用 Starter 时依赖坐标为 `io.github.susongyan:boba-straw-spring-boot-starter:0.1.0-alpha.1`。
业务项目应使用自己的 Spring Boot BOM/parent 统一依赖版本；Starter 不强行接管应用的 Boot 版本。
Starter 可按 `mode: standalone / cluster / sentinel` 创建对应类型的默认 Bean。
Cluster/Sentinel 用 `nodes` 配置多个 `host:port`（IPv6 使用 `[host]:port`），不在节点列表里放凭据。
Sentinel 还需要 `master-name`，`username/password` 是数据节点认证，
`sentinel-username/sentinel-password` 是独立控制链路认证。
应用自定义任一种 Client Bean 时，默认 Client 自动配置退让，不修改或复制用户的配置。
Spring 注入的 Client 由容器关闭，业务方法不应自行 close。
启动行为沿用核心：Standalone 异步建连，Redis 暂时不可达不等于 Spring 容器必然启动失败；
Cluster/Sentinel 创建时需要完成初始拓扑发现，失败会阻止对应 Bean 创建。认证成功以实际连接就绪为准。

### 多客户端与 TLS

只需具名客户端时设置 `default-client-enabled: false`，避免额外创建默认 localhost 客户端。
每个具名客户端使用独立配置，不从默认客户端继承密码、TLS 或拓扑参数。

```yaml
boba:
  straw:
    default-client-enabled: false
    clients:
      cache:
        uri: ${BOBA_CACHE_URI}
        password: ${BOBA_CACHE_PASSWORD}
        tls:
          enabled: true
          trust-store: file:/run/secrets/redis-trust.p12
          trust-store-password: ${BOBA_TRUST_PASSWORD}
          # mTLS 时另外配置 key-store 与 key-store-password
      orders:
        mode: cluster
        nodes: [redis-a:6379, redis-b:6379, redis-c:6379]
        password: ${BOBA_ORDERS_PASSWORD}
        protocol: RESP2
        tls:
          enabled: true
```

注入 `io.github.susongyan.bobastraw.spring.BobaStrawClients`，按名称及实际拓扑类型获取：

```java
BobaStrawClient cache = clients.get("cache", BobaStrawClient.class);
BobaStrawClusterClient orders = clients.get("orders", BobaStrawClusterClient.class);
String value = cache.sync().get("key");
```

具名客户端由注册表统一关闭；部分创建失败会关闭此前创建的客户端，不留下后台重连线程。
默认客户端与具名客户端可以共存，但具名对象不是单独的 `@Qualifier` Bean。
`boba.straw.enabled=false` 禁用本 Starter 的全部自动创建和监控，不会关闭应用自己创建的 Bean。

TLS 配置在默认/具名客户端内一致：`tls.enabled=true` 使用 JDK 信任策略；可传入 Spring Resource
路径 `trust-store`、`key-store` 及对应密码，默认 `store-type=PKCS12`，密钥密码与 key store 密码相同。
`handshake-timeout=5s`，默认仅 TLS 1.2/1.3，始终校验证书与主机身份；不支持 trust-all 开关。
配了 store 却没有开启 TLS 会拒绝启动。`rediss://` 本身也启用 TLS，不能被 `enabled=false` 降级；
自定义 store 和握手预算时应显式开启 `tls.enabled`。Sentinel 使用独立的 `sentinel-tls` 同结构配置。

命令超时默认 2s、协议 AUTO；Sentinel 发现超时 500ms，拓扑刷新默认 Sentinel 1s、Cluster 30s，
可通过 `discovery-timeout`、`topology-refresh-interval` 调整。核心高级 options 或自定义 SSLContext
仍可通过用户定义 Client Bean 使用，不需要绕过安全校验或把运行时依赖放进 core。
发现/命令预算从提交开始，可能先于 TLS 的 5s 握手预算耗尽；TLS Sentinel 应按实际证书握手和
网络延迟配置发现预算（本机兼容测试显式使用 3s 发现、5s 命令），不要把握手上限当作额外赠送时间。

### Health 与 Metrics

Actuator 为可选依赖，业务项目按需加入 `spring-boot-starter-actuator`。Health 指标名为
`bobaStraw`，读取连接本地状态，不额外发送 PING；全部已知共享连接 READY 才为 UP，未就绪为 DOWN，
无客户端为 UNKNOWN。它不是服务端可用性或全集群 Slot 完整性的强证明，也不自动加入 liveness。
可使用 `management.health.boba-straw.enabled=false` 禁用；遵守 `management.health.defaults.enabled`。
不会在 Health details 暴露密码、URI 或失败堆栈。Actuator HTTP 暴露和访问控制由业务应用配置。

存在 Micrometer 时提供 `MeterBinder`，Boot 自动绑定到 MeterRegistry；无需引入 WebFlux。
`boba.straw.metrics.enabled=false` 可关闭。指标使用 `client=bean:Bean名称` 或 `named:配置名称`：

| Gauge | 语义 |
| --- | --- |
| `boba.straw.ready` | 当前本地就绪为 1，否则 0 |
| `boba.straw.inflight` | Standalone / Cluster 当前共享连接未完成命令总数 |
| `boba.straw.queued.bytes` | Standalone / Cluster 当前共享连接待写明文字节总数 |
| `boba.straw.topology.successes` / `failures` | Cluster 拓扑刷新或 Sentinel 发现的累计快照 |

全部为快照 Gauge；不把可能随连接/节点退休归零的数据包装为单调 Counter。不添加 Key、命令参数、
节点地址等高基数标签；Sentinel 未公开的数据节点队列指标不伪造为 0。容器关闭移除自身注册的指标，
遇到相同指标身份冲突时拒绝绑定并回收本次注册，不接管其他组件的 Meter。

## 核心 TLS 接入

```java
try (BobaStrawClient client = BobaStrawClient.builder()
    .uri("rediss://cache.example.internal:6379")
    .build()) {
    client.sync().set("tea", "boba");
}
```

私有 CA：使用标准 JDK trust manager 构造 `SSLContext`，通过
`.tls(BobaStrawTlsOptions.builder().sslContext(context).build())` 传入；mTLS 还需 key manager
提供客户端证书和私钥。不要使用 trust-all manager。默认 TLS 建连/握手预算为 5 秒，命令超时
仍从提交开始；TLS 校验失败不回退明文，不重放可能已执行的命令。

Cluster 的 `.tls(options)` 同时作用于种子、发现节点和重定向目标。
Sentinel 的 `.sentinelTls(options)` 保护控制链路，`.tls(options)` 保护数据节点，两者独立配置。
发现的 IP/主机也必须匹配证书身份；不能仅保证种子的证书正确。
已验证本机 JSSE、Redis 6.2.14/7.4.2、Valkey 8.1.3 TLS，以及 Redis 7.4.2 TLS Cluster/Sentinel。
生产长稳与扩展平台矩阵仍未完成；Starter 的 TLS 配置见上节。
默认值、失败边界和原理见[网络模型 C7](../architecture/network-model.md#c7-tls-传输设计)。

## 第一次让 AI 使用

仓库内可直接让 AI 读取 `.agents/skills/boba-straw-usage/SKILL.md` 及其引用文档。
离线包尚未发布；不要仅复制 SKILL.md 后丢失相对路径引用。业务团队应固定来源提交并携带引用文档，
按工具实际支持的方式配置规则发现；不支持 Skill 的工具可引用本指南。
首次显式请求：“请读取 boba-straw-usage，检查依赖版本，按示例接入，并报告验证结果。”
依赖版本应以 Maven 实际解析结果为准：`mvn dependency:tree -Dincludes=io.github.susongyan`。
发现版本不匹配先报告，不自动改依赖。详见 [分发与版本](distribution.md)。
