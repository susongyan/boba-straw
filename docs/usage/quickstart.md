# Boba Straw 接入指南

本资料面向应用研发，使用公开 API；实现状态见 [能力表](supported-features.md)，审查规则见
[用法检查](review-checklist.md)。SDK 当前为 0.1.0-SNAPSHOT，正式发布与许可证尚未完成。
示例依赖需先从对应提交构建安装到本地 Maven 仓库，或使用企业已批准的相同制品。

## 可编译示例

随离线 Skill 包提供的 examples 包含 java 和 spring-boot 两个模块。
仓库中执行：`mvn -Pusage-examples test`。
独立导入后执行：`mvn -f examples/pom.xml test`。
示例 POM 不继承 Boba Straw 源码根 POM；SDK 从 Maven 仓库解析。

- Java：复用一个 Client，sync/async、Pipeline、Lua、binary 和订阅均有示例。
- Spring Boot：2.7.18 已作为首版样例基线，注入自动装配的 Client，由 Spring 关闭。
- 事务示例是现有 helper 的演示，仍属于受限能力，不能据此承诺取消与 WATCH 的完整语义。
- 外部环境变量 `BOBA_REDIS_URI` 指定测试实例。不要对生产实例运行示例测试。

集成测试需显式启用：
`mvn -f examples/pom.xml -Dboba.examples.integration=true test`。
每轮测试使用随机 Key 前缀，在 finally 清理自己创建的 Key；禁止 FLUSHDB/FLUSHALL。

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
TLS、Sentinel、多客户端自动配置、Health 和 Micrometer 尚未提供；不能编造配置项。
Boot 3 的自动配置入口存在，但本套示例不据此宣称已完成 Boot 3 兼容验收。

## 第一次让 AI 使用

把配套 Skill 导入业务仓库，并按该 AI 工具说明配置项目规则发现；不支持 Skill 的工具可直接引用本指南。
首次显式请求：“请读取 boba-straw-usage，检查依赖版本，按示例接入，并报告验证结果。”
依赖版本应以 Maven 实际解析结果为准：`mvn dependency:tree -Dincludes=io.github.susongyan`。
发现版本不匹配先报告，不自动改依赖。详见 [分发与版本](distribution.md)。
