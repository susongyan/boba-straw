# Boba Straw 功能实施与验收清单

本文档记录已实现功能、验证结果和后续工作，是研发与 AI 协作时的进度基线。

状态：
- [x] 已实现并通过验收
- [~] 已有实现，但未达到生产验收
- [ ] 尚未实现

## 已实现功能与验收结果

### 工程与运行时

- [x] Maven 多模块工程
- [x] 包名统一为 io.github.susongyan.bobastraw
- [x] Java 8+ 编译目标
- [x] boba-straw-core 仅依赖 JDK
- [x] 不引入 Netty、Reactor、RxJava、Kotlin Coroutine、WebFlux
- [x] Java 8/11/17/21 CI 基础矩阵

验收结果：mvn test 通过，core 无第三方运行时依赖。

### 连接模型

- [x] 普通命令每个 Redis 节点一个共享多路复用连接
- [x] 事务和 Pub/Sub 使用独占连接
- [~] 状态型专用连接池（事务连接池已懒加载，Pub/Sub/阻塞命令待补）
- [~] 共享连接失效检测、下一请求懒重连和固定间隔后台重连
- [x] 可选空闲连接 PING 健康检测
- [ ] 后台退避重连、连接状态和指标管理

### 网络模型演进

- [x] 网络模型、EventLoop 所有权、取消与背压架构文档
- [x] 阶段 1：连接队列状态收敛和取消/FIFO 安全
- [ ] 阶段 2：共享 Selector EventLoopGroup
- [ ] 阶段 3：读缓冲复用、gathering write 与公平预算
- [ ] 阶段 4：RESP 增量状态机与协议资源上限
- [ ] 阶段 5：统一 deadline、背压、回调与订阅分发隔离
- [ ] 阶段 6：JMH、故障注入和负载验收

验收原则：普通命令无需业务配置连接池大小；连接池只服务于状态型场景。

阶段 1 验收记录：业务线程只向 EventLoop 提交任务；握手前提交的请求按提交顺序进入
`preReady`；已写入的取消请求保留响应占位；RESP3 Attribute 包裹的 Push 不得消费普通
请求；Pub/Sub 的 RESP3 订阅确认匹配专用连接的待处理请求；连接失败会区分未发送和
可能已执行。上述语义由本地假 Redis socket 测试覆盖，并已通过 `mvn test`。

### 协议与连接

- [x] RESP2 Simple String、Error、Integer、Bulk、Array
- [x] RESP3 Map、Set、Push、Attribute
- [x] RESP3 Blob Error、Verbatim String、Big Number
- [x] 增量解析和碎片输入
- [x] FIFO 请求/响应匹配
- [x] Attribute 不影响普通响应匹配
- [x] AUTO 使用 HELLO 3
- [x] Redis 5 不支持 HELLO 时回退 RESP2
- [x] 显式 RESP2 跳过 HELLO
- [x] 用户名、密码和 CLIENT SETNAME 握手入口
- [x] NIO Selector/SocketChannel 单连接事件循环
- [x] 命令超时和连接异常

验收结果：本地假 Redis 协商测试和 Redis 5/6.2/7、Valkey 矩阵测试通过。

### API 与命令

- [x] 同步 API
- [x] CompletionStage 异步 API
- [x] Raw Command 基础入口
- [x] PING、GET、SET、DEL
- [x] EXISTS、EXPIRE、TTL、INCR
- [x] HGET、HSET、HGETALL
- [x] LPUSH、RPUSH、LRANGE
- [x] SADD、SMEMBERS
- [x] ZADD、ZRANGE
- [x] Lua EVAL 基础入口
- [x] Pipeline 有序 API
- [~] MULTI/EXEC/DISCARD 专用连接 helper

验收结果：基础命令、Pipeline、事务 helper、Lua 已在 Redis/Valkey 兼容测试中验证；事务专用连接已接入，但仍需并发隔离和异常归还测试。

### 本地测试环境

- [x] Colima Redis/Valkey 启动脚本
- [x] Redis 5.0.14，端口 16379
- [x] Redis 6.2.14，端口 16380
- [x] Redis 7.4.2，端口 16381
- [x] Valkey 8.1.3，端口 16382
- [x] 端口仅绑定 127.0.0.1

启动：scripts/redis-test-up.sh
清理：scripts/redis-test-down.sh

## 部分实现

### Cluster

- [~] 独立 BobaStrawClusterClient 入口
- [~] CLUSTER SLOTS 初始发现
- [~] CRC16 Slot 计算
- [~] Hash Tag
- [~] Slot 到节点路由
- [~] MOVED 一次重定向
- [~] ASK/ASKING 一次重定向

尚未达到生产验收：拓扑周期刷新、连接池、故障摘除、Replica 读策略、跨 Slot 校验、Cluster Pipeline/事务/PubSub 语义。

### Spring Boot

- [~] Boot 2.7/3.x 基础自动配置
- [~] 单客户端 URI、超时、协议配置
- [x] core 与 Spring 解耦
- [x] 不提供 WebFlux/Reactor 适配

尚未达到生产验收：多客户端、Sentinel/Cluster/TLS 配置、Health、Metrics、生命周期和配置校验。

## 尚未实现

- [~] Pub/Sub 专用连接、订阅管理、RESP2 消息和 RESP3 Push 分发
- [~] 真正批量 Pipeline 编码和批量 Socket 写入
- [~] 事务专用连接、WATCH/UNWATCH 和连接归还
- [~] TransactionConnectionPool（按需创建、上限、成功归还、异常销毁）
- [x] 事务连接获取等待超时
- [x] 事务空闲连接回收
- [x] 归还时健康检查
- [ ] 通用 ConnectionFactory
- [x] Pipeline 与命令超时到物理请求的取消传播和响应排空
- [x] 未发送/可能已执行请求的失败分类
- [ ] 有界退避重连、连接状态与指标管理
- [~] byte[] 基础 RESP 编码和 Raw API
- [ ] String/ByteArray Codec 及自定义 Codec SPI
- [ ] Stream、Bitmap、HyperLogLog、EVALSHA、Server/ACL 命令
- [ ] JDK SSLEngine TLS
- [ ] Sentinel 主节点发现和切换感知
- [ ] Cluster 完整拓扑、故障切换和多 Key 校验
- [ ] Spring Boot Health、Micrometer、Actuator、多客户端
- [ ] 故障注入、并发和 JMH 测试
- [ ] Checkstyle、SpotBugs、ArchUnit、JaCoCo、Revapi/japicmp、Enforcer 门禁
- [ ] LICENSE、NOTICE、Maven Central 发布元数据

## 每项功能的完成定义

功能只有同时满足以下条件才可从 [~] 或 [ ] 改为 [x]：

1. Java 8 兼容实现完成。
2. 有单元测试和碎片化测试（适用时）。
3. 有并发、超时、取消、断线测试（适用时）。
4. 有真实 Redis、Valkey 或拓扑容器测试（适用时）。
5. 明确失败、重试和资源生命周期语义。
6. 不引入禁止的响应式或网络运行时依赖。
7. README 和架构文档已同步。
8. mvn test 和 CI 门禁通过。

## 测试命令

普通测试：mvn test

兼容性测试：mvn -Dboba.straw.runCompatibility=true test

Cluster/Sentinel/TLS 测试完成后，应分别加入独立 profile，默认测试不得依赖本地容器。
