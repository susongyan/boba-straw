# Boba Straw

> Redis client with a straw — sip your data like bubble tea.

Boba Straw 是轻依赖、纯 Java 的 Redis / Valkey 访问客户端，面向应用研发提供同步、
`CompletionStage` 异步和 `byte[]` 二进制 API。

核心兼容 **Java 8+**，运行时仅依赖 JDK，基于 Java NIO 实现连接复用；
不依赖 Netty、Reactor、RxJava 或 Spring。Spring Boot 通过独立 Starter 接入。

**已发布：`0.1.0-alpha.1`** · [Maven Central](https://central.sonatype.com/artifact/io.github.susongyan/boba-straw-core/0.1.0-alpha.1) · [Apache-2.0](LICENSE)

当前为 Alpha，适合评估和试用。已知性能回归、生产长稳及扩展平台验证仍有待办；
发布不等于生产验收。详见[路线图](docs/implementation/roadmap.md)。

## 能力概览

- **协议与拓扑**：RESP2 / RESP3，Standalone、Sentinel、Cluster，多种子节点及拓扑刷新。
- **常用命令**：Key、String、Hash、List、Set、ZSet、TTL、Counter、Bitmap 的高频 API；未封装的普通命令可使用 Raw API。
- **专用操作**：Pipeline、事务、Lua 脚本注册与执行、经典 Pub/Sub、BLPOP / BRPOP、游标分页。
- **连接管理**：按节点复用共享连接，有界背压、超时、取消与退避重连；事务、阻塞命令和订阅使用专用连接。
- **安全与集成**：JDK TLS / mTLS、Spring Boot 自动配置、多客户端、可选 Actuator Health 和 Micrometer 指标。

不同拓扑、String / binary 和批量入口的覆盖范围并不完全相同；不追求全命令、全选项封装。
具体边界见[版本能力表](docs/usage/supported-features.md)。

## 安装

### 普通 Java 项目

```xml
<dependency>
    <groupId>io.github.susongyan</groupId>
    <artifactId>boba-straw-core</artifactId>
    <version>0.1.0-alpha.1</version>
</dependency>
```

### Spring Boot 项目

使用 Starter 即可，无需重复添加 Core：

```xml
<dependency>
    <groupId>io.github.susongyan</groupId>
    <artifactId>boba-straw-spring-boot-starter</artifactId>
    <version>0.1.0-alpha.1</version>
</dependency>
```

应用使用自己的 Spring Boot BOM / parent 管理依赖版本。已测试 Boot 2.7.18 / Java 8、
Boot 3.0.13 / Java 17、Boot 3.5.6 / Java 21；详细验证范围见[能力表](docs/usage/supported-features.md)。

## 快速上手

以下是连接本地 Redis 的短任务示例：

```java
import io.github.susongyan.bobastraw.BobaStrawClient;
import java.util.concurrent.CompletionStage;

public class QuickStart {
    public static void main(String[] args) {
        try (BobaStrawClient client = BobaStrawClient.builder()
                .uri("redis://localhost:6379")
                .build()) {
            client.sync().set("boba:tea", "milk tea");
            String value = client.sync().get("boba:tea");
            System.out.println(value);

            CompletionStage<String> result = client.async().get("boba:tea");
            // 仅为短任务示例等待完成，避免提前关闭 Client。
            System.out.println(result.toCompletableFuture().join());
        }
    }
}
```

长期服务应在启动时创建 Client、在请求间复用、在停止时关闭，不要每次请求新建。
普通命令直接使用 `sync()`、`async()` 或 `binary()`，无需开启 `typed()` 模式；
`typed()` 是 Pipeline / 事务的类型化入队入口，见[命令与批量操作](docs/usage/commands.md)。

Spring Boot 使用 Starter 后配置连接，并注入自动创建的 `BobaStrawClient`：

```yaml
boba:
  straw:
    uri: ${BOBA_REDIS_URI}
    command-timeout: 2s
    protocol: AUTO
```

由环境变量提供连接地址和凭据，不要提交带密码的 URI。Spring 管理的 Client 由容器关闭。
Cluster、Sentinel、多客户端及 TLS 配置见[完整接入指南](docs/usage/quickstart.md#spring-boot-配置)。

## 使用前需要了解

- 默认 `AUTO` 尝试 `HELLO 3`，明确不支持时回退 RESP2；认证失败不会触发协议降级。可显式配置 `RESP2`。
- 普通命令按节点复用连接，通常无需设置连接池大小；容量与资源所有权见[生命周期](docs/usage/lifecycle.md)与[背压指南](docs/usage/backpressure-and-capacity.md)。
- **重连不重放失败命令**。超时、取消或断连不等于服务端未执行，业务应处理不确定结果，不能盲目重试写命令。
- Cluster 多 Key 操作要求同 Slot，不隐式拆分；Pipeline 不保证原子性，事务中的命令错误也不意味着回滚。
- Pub/Sub 不保证持久投递，断连或拓扑切换后不自动恢复订阅；Raw API 不能绕过专用连接和路由限制。

完整失败语义见[失败、取消与重试](docs/usage/failures-and-retries.md)。

## 文档导航

**应用接入**

- [接入指南](docs/usage/quickstart.md)：依赖、Spring Boot、TLS 与多客户端。
- [命令速查](docs/usage/command-reference.md)：按数据结构查看命令、binary / typed 批量覆盖和选项。
- [命令、批量与分页](docs/usage/commands.md) · [Lua 脚本](docs/usage/lua.md)。
- [生命周期](docs/usage/lifecycle.md) · [背压与容量](docs/usage/backpressure-and-capacity.md) · [用法检查](docs/usage/review-checklist.md)。
- [版本能力表](docs/usage/supported-features.md)：按实际拓扑和 API 选择能力。

**设计与贡献**

- [网络模型](docs/architecture/network-model.md) · [命令模型](docs/architecture/command-model.md) · [Lua 设计](docs/architecture/lua-scripting.md)。
- [Cluster 拓扑](docs/architecture/cluster-topology.md) · [Sentinel 拓扑](docs/architecture/sentinel-topology.md)。
- [命令扩展指南](docs/development/command-extension-guide.md) · [性能基准方法](docs/benchmarks/README.md)。

**验证与演进记录**（按阶段保留过程，不作为当前 API 清单）

- [路线图](docs/implementation/roadmap.md) · [核心验证记录](docs/implementation/core-completion-plan.md)。
- [网络模型演进记录](docs/implementation/network-model-history.md) · [命令开发历史](docs/implementation/command-development-history.md)。

使用 AI 辅助接入时，先阅读[接入指南中的 AI 使用说明](docs/usage/quickstart.md#第一次让-ai-使用)。
仓库内提供使用方与开发方 Skill；它们指导实现和审查，不替代测试或能力表。

## 本地开发

在仓库根目录执行测试：

```bash
mvn test
```

真实 Redis / Valkey 兼容测试需显式启动本地容器；Colima / Docker 就绪后运行：

```bash
./scripts/redis-test-up.sh
mvn -Dboba.straw.runCompatibility=true test
./scripts/redis-test-down.sh
```

普通 `mvn test` 不代表容器专项、完整兼容矩阵或性能验证全部执行。
测试脚本说明见[启动脚本](scripts/redis-test-up.sh)，压测方法见[性能基准](docs/benchmarks/README.md)。

## 许可证

采用 [Apache License 2.0](LICENSE)，项目声明见 [NOTICE](NOTICE)。
