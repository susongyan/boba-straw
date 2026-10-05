# Boba Straw 接入指南

本资料面向应用研发，使用公开 API；实现状态见 [能力表](supported-features.md)，审查规则见
[用法检查](review-checklist.md)。SDK 当前为 0.1.0-SNAPSHOT，正式发布与许可证尚未完成。
示例依赖需先从对应提交构建安装到本地 Maven 仓库，或使用企业已批准的相同制品。

## 添加依赖

核心最低 Java 8，只依赖 JDK，不要求 Spring 或响应式库。当前未正式发布到 Maven Central，
请使用经团队确认的源码提交构建制品，或从企业仓库获取同一来源的 SNAPSHOT：

```xml
<dependency>
  <groupId>io.github.susongyan</groupId>
  <artifactId>boba-straw-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
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

只有 uri、command-timeout、protocol 是当前 Starter 的绑定配置。密码由外部密钥管理注入，
不要提交带真实凭据的 URI，也不要打印 URI。AUTO 使用 HELLO 3，显式 RESP2 跳过 HELLO。
使用 Starter 时依赖坐标为 `io.github.susongyan:boba-straw-spring-boot-starter:0.1.0-SNAPSHOT`。
Starter 当前仅自动配置单个 Standalone Client；Sentinel/Cluster 和多客户端自动配置、Health、
Micrometer 集成尚未提供。核心 SDK 的 Sentinel/Cluster 能力不代表 Starter 已支持配置它们。
核心 TLS 仍未实现。Boot 3 的自动配置入口存在，但 Boot 版本矩阵与独立示例尚未验收。
Spring 注入的 Client 由容器关闭，业务方法不应自行 close。

## 第一次让 AI 使用

仓库内可直接让 AI 读取 `.agents/skills/boba-straw-usage/SKILL.md` 及其引用文档。
离线包尚未发布；不要仅复制 SKILL.md 后丢失相对路径引用。业务团队应固定来源提交并携带引用文档，
按工具实际支持的方式配置规则发现；不支持 Skill 的工具可引用本指南。
首次显式请求：“请读取 boba-straw-usage，检查依赖版本，按示例接入，并报告验证结果。”
依赖版本应以 Maven 实际解析结果为准：`mvn dependency:tree -Dincludes=io.github.susongyan`。
发现版本不匹配先报告，不自动改依赖。详见 [分发与版本](distribution.md)。
