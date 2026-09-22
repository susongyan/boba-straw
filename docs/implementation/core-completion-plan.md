# 核心客户端后续执行顺序

更新时间：2026-09-22。基线：`2bd4993` 后的工作树。TLS 后置，不再与本轮其他网络能力并行推进。

## 阶段与验收

| 顺序 | 范围 | 完成条件 | 状态 |
| --- | --- | --- | --- |
| C1 | 事务和阻塞命令专用连接 | 租约只归还一次；取消/超时/关闭销毁；池等待不阻塞归还；真实 WATCH/EXEC 和阻塞隔离验证 | 本文限定范围已完成 |
| C2 | Cluster 连接与拓扑 | 节点退避重连、周期/事件刷新、故障摘除、MOVED/ASK 和多 Key 策略，真实集群故障测试 | 待实施 |
| C3 | Sentinel | 多 Sentinel 发现、认证边界、主节点切换、旧连接处理、明确未知执行结果，真实切换验证 | 待实施 |
| C4 | 可用环境的 JDK/平台验证 | 记录实际 JDK/OS/服务端矩阵，其他平台由 CI 验证，不将本机通过泛化 | 待实施 |
| C5 | 命令和二进制接口 | 用命令开发 Skill 按数据结构分组，完善覆盖清单、返回类型、版本与协议测试 | 待实施 |
| C6 | 拓扑功能收尾 | Cluster/Sentinel 与新增命令、专用连接组合验收；不重复宣称 C2/C3 已完成 | 待实施 |
| C7 | TLS | 单独实现 SSLEngine、证书/主机名校验和关闭/重连测试；前置功能验收后开展 | 明确后置 |
| C8 | Starter 与发布 | Health、Micrometer、多客户端、配置/生命周期，质量门禁和兼容矩阵；许可证确定后才能发布 | 待实施 |

用户后续可调整顺序。每阶段只记录真实完成和验证项；不将“网络模型六阶段完成”等同整个客户端完成。
不自动重试命令，网络断连、取消和超时均不能解释为服务端撤销。

## C1 当前范围

- 事务增加 AutoCloseable；成功 EXEC 或已确认 UNWATCH 后归还；取消/失败/放弃时销毁。
- WATCH/UNWATCH 必须等待当前操作完成再开始后续操作；控制命令不得混入 transaction.command。
- 保持现有 exec() 返回签名和 WATCH 冲突空列表行为，暂不引入新的事务结果对象。
- 池按需创建；借用在 Client 锁外等待，销毁/关闭唤醒等待者，重复归还/销毁不改变容量。
- 空闲回收使用共享 EventLoop 定时任务，不再创建事务回收线程。
- Standalone 的同步/异步 BLPOP、BRPOP 使用单次专用连接，有并发上限；暂不提供连接池或 Cluster 阻塞 API。
- 客户端 commandTimeout 始终适用，Redis timeout=0 不代表客户端无限等待；服务端正常超时返回空列表。
- 二进制阻塞 API、更多阻塞命令留在 C5；不得通过共享 Raw/Pipeline 绕过连接隔离。

事务 MULTI 失败时不得继续发送业务命令；因此按 ACK 推进，不能不加条件地把 MULTI/业务命令/EXEC 全部写出。
专用请求在 transport 失败时关闭连接，不等待业务 callback worker 空闲。

## 服务端语义依据

2026-09-22 核对：[Redis transactions](https://redis.io/docs/latest/develop/using-commands/transactions/)、
[BLPOP](https://redis.io/docs/latest/commands/blpop/)、[BRPOP](https://redis.io/docs/latest/commands/brpop/)
和 [UNWATCH](https://redis.io/docs/latest/commands/unwatch/)。
事务无回滚，WATCH 冲突使 EXEC 返回 Null；BLPOP 的服务端超时与客户端命令超时分开处理。
使用整数秒参数维持 Redis 5 兼容，不增加依赖较新服务端的小数超时选项。

## C1 验证入口

```sh
mvn test
mvn test -pl boba-straw-core -Dtest=DedicatedConnectionLifecycleTest
mvn test -Dboba.straw.runCompatibility=true
```

前两项使用本地模拟服务器；最后一项使用 16379–16382 上的 Redis 5/6.2/7.4 与 Valkey 8.1。
真实测试使用 UUID Key 并只清理本次数据。

## C1 验收记录（2026-09-22）

源码为 `2bd4993` 后本阶段工作树。运行平台 macOS x86_64、Colima；四个既有测试容器从
停止状态恢复，未修改其他项目容器。服务端分别为 Redis 5.0.14、6.2.14、7.4.2 和 Valkey 8.1.3。

| JDK | 实际命令（JAVA_HOME 指向对应已安装 JDK） | 结果 |
| --- | --- | --- |
| Oracle 8u202 | `mvn test -q -Dboba.straw.runCompatibility=true` | 76 tests，0 failures/errors/skipped |
| Oracle 17.0.10 | `mvn clean test -q -Dboba.straw.runCompatibility=true` | 76 tests，0 failures/errors/skipped |
| Oracle 21.0.7 | `mvn clean test -q -Dboba.straw.runCompatibility=true` | 76 tests，0 failures/errors/skipped |

新增 16 个模拟服务器生命周期测试及 2 个真实兼容测试方法，后者每个遍历四服务端与
AUTO/RESP2 组合。Surefire 报告位于 core 的 `target/surefire-reports/`，会随 clean 覆盖；
本记录不是不可变的发布制品证明。JDK 8 首次失败来自测试 helper 过度剥离异常 cause，
修正为仅剥离 CompletionException/ExecutionException 后重跑通过，未放宽失败分类断言。

覆盖：懒加载、干净租约复用、池耗尽等待/超时、归还/销毁唤醒、Client 关闭唤醒、空闲回收、
WATCH 取消/放弃关闭、EXEC 取消/超时/写后断连、MULTI/排队错误不发送 EXEC、状态命令拒绝、
阻塞连接隔离/容量拒绝/取消/超时/中断、callback 繁忙时 transport 清理、真实 WATCH 冲突、
discard/UNWATCH 清理、EXEC 内逐项错误、真实阻塞读取和服务端超时。

公开兼容性复核：不删除或修改已有方法签名；增加 AutoCloseable、BLPOP/BRPOP 和 builder 上限配置。
行为收紧：WATCH/UNWATCH 未完成时不能并发启动下一操作；拒绝在 command() 混入连接状态命令；
discard 改为真正清理本地待执行事务，而不是在尚未 MULTI 时发送无效 DISCARD。
同步中断会取消底层等待，但仍保留“可能执行”的业务边界。

未验证：JDK 11/25 本机运行、其他 OS、完整故障压力长跑、Cluster 专用命令，以及新性能基线。
这些不包含在 C1 完成声明中。更多阻塞命令与 byte[] API 留待 C5，不用此阶段宣称完整命令覆盖。

## C2 开始前已识别的重点

当前 Cluster 仍为旧实验实现：不能将 ASK 临时目标写成永久 Slot 所有者；ASKING 与目标命令
必须独占同一连接，避免被其他共享请求消费。刷新需原子替换经过校验的 Slot 快照，
节点关闭后只恢复连接、不重放未知执行结果。C2 必须分别验证这些语义，而不只增加刷新定时器。
