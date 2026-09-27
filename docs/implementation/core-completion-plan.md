# 核心客户端后续执行顺序

更新时间：2026-09-27。C1 基线为 `2bd4993`，C2 基线为 `9a227d4`，C3 基线为 `ae3990f`，C4 基线为 `c696ae3`。
TLS 后置，不再与本轮其他网络能力并行推进。

## 阶段与验收

| 顺序 | 范围 | 完成条件 | 状态 |
| --- | --- | --- | --- |
| C1 | 事务和阻塞命令专用连接 | 租约只归还一次；取消/超时/关闭销毁；池等待不阻塞归还；真实 WATCH/EXEC 和阻塞隔离验证 | 本文限定范围已完成 |
| C2 | Cluster 连接与拓扑 | 节点退避重连、周期/事件刷新、故障摘除、MOVED/ASK 和多 Key 策略，真实集群故障测试 | 本文限定普通命令范围已完成 |
| C3 | Sentinel | 多 Sentinel 发现、认证边界、主节点切换、旧连接处理、明确未知执行结果，真实切换验证 | 本文限定普通命令范围已完成 |
| C4 | 可用环境的 JDK/平台验证 | 记录实际 JDK/OS/服务端矩阵，其他平台由 CI 验证，不将本机通过泛化 | 本机 8/11/17/21 通过；25 与其他平台待验证，入口已落地 |
| C5 | 命令和二进制接口 | 用命令开发 Skill 按数据结构分组，完善覆盖清单、返回类型、版本与协议测试 | 进行中：首批二进制 String 接口已实现，其余分组待完成 |
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

本阶段开始时 Cluster 仍为旧实验实现：不能将 ASK 临时目标写成永久 Slot 所有者；ASKING 与目标命令
必须独占同一连接，避免被其他共享请求消费。刷新需原子替换经过校验的 Slot 快照，
节点关闭后只恢复连接、不重放未知执行结果。C2 必须分别验证这些语义，而不只增加刷新定时器。

## C2 本阶段交付边界

- 普通主节点命令：节点复用 Standalone 退避重连，周期/事件发现和原子快照替换；非 seed 旧节点摘除。
- MOVED 最多一次，ASK 独占 ASKING/目标请求，临时连接有界并正确取消/关闭。
- 已知命令提取所有 Key、跨 Slot 拒绝；未知普通命令改用显式全部 Key 的入口。
- 顶层明确服务端错误独立分类；超时、连接中断、未知执行结果不自动重发。
- 节点 metrics、拓扑版本/刷新计数；主动刷新视图取消不影响内部共享刷新。
- 新增六节点一次性测试环境、12 个模拟生命周期测试与 3 个 opt-in 真实 Cluster 测试。

完整配置、失败边界和兼容性说明见 [Cluster 拓扑设计](../architecture/cluster-topology.md)。
C2 不含 Cluster typed/binary、事务/Pipeline/PubSub/阻塞入口，亦不宣称完成生产长稳或跨主机分区验收。

### C2 测试命令

```sh
sh scripts/cluster-test-up.sh
mvn clean test -q -Dboba.straw.runCompatibility=true -Dboba.straw.runCluster=true
```

源码基线：`9a227d4` 加本阶段工作树；平台 macOS x86_64 / Colima。
Standalone 为 Redis 5.0.14、6.2.14、7.4.2、Valkey 8.1.3（AUTO/RESP2）；Cluster 为 Redis 7.4.2
三主三副本，ASK/多 Key 覆盖 AUTO/RESP2，主动切换 AUTO，不可用主节点自动选主 RESP2。

初轮 Cluster 验收出现连接超时与 Docker 控制超时；不放宽原断言，后续定向及全量重跑通过。
故障恢复增加容器内自动 CONT 保护。JDK 8 首轮工作目录出现混合编译产物的
`NoSuchMethodError`，改在独立临时目录复制同一源码并 clean 构建，避免编辑器编译干扰。
隔离运行还发现测试间主从切换尚未收敛就开始下一故障场景的问题：增加副本就绪前置检查，
不修改命令超时或隐藏客户端失败。

### C2 最终验收记录（2026-09-22）

在隔离临时目录执行上述完整命令，避免编辑器与 Maven 同时写入 target。
逐文件对比确认 core/src 与工作树一致；每次换 JDK 均 clean，未并行运行集群测试。

| JDK | 全模块构建与测试 | 真实服务端测试 |
| --- | --- | --- |
| Oracle 8u202 | 91 tests，0 failures/errors/skipped | 四服务端 AUTO/RESP2 与 Cluster 三场景通过 |
| Oracle 17.0.10 | 91 tests，0 failures/errors/skipped | 同上 |
| Oracle 21.0.7 | 91 tests，0 failures/errors/skipped | 同上 |

报告生成于隔离构建的 core/target/surefire-reports，随 clean 覆盖；本表不是发布制品证明。
阶段末检查测试集群为 `cluster_state:ok`、16384 Slot 全部正常。测试容器保持运行，未更改其他项目容器。
脚本通过 `sh -n`，改动通过 `git diff --check`。无新增 core 运行时依赖。

审查覆盖：仅明确服务端拒绝才允许一次重定向；ASKING 与目标命令独占连接；取消关闭临时连接；
不覆盖较新的 MOVED 路由；旧非 seed 节点摘除；外部 Resources 归属；错误帧不消耗下一请求响应。
按照开发/命令/审查 Skill 的清单执行本阶段检查，没有启动独立 AI Agent 或宣称跨模型验收。

未验证：Cluster 的 Redis 5/6.2 与 Valkey 矩阵、认证轮换、IPv6 实网、跨主机分区、长稳压力、
JDK 11/25 和其他 OS。Cluster 专用命令组合留在 C6，更多命令与二进制接口留在 C5；
TLS 仍明确后置。下一阶段为 C3 Sentinel。

## C3 本阶段交付边界

- 新增独立 `BobaStrawSentinelClient`：多个 Sentinel、masterName、独立 Sentinel/Redis 认证配置。
- 查询主节点地址后，在实际业务物理连接上执行 ROLE 验证；相同主节点复用连接。
- 周期发现、断线/READONLY/超时重新发现；失败有界退避，只重试发现，不重放业务请求。
- 主节点切换退休旧连接，保留未发送/可能已执行分类；没有可用主连接时立即返回未发送失败。
- 主地址、连接状态、发现成功/失败计数；外部 Resources 归属、关闭与取消视图有明确语义。
- 暂提供普通 String Raw CompletionStage 入口；typed/binary、DB/URI、专用命令组合留在 C5/C6。

设计与接入说明：[Sentinel 拓扑](../architecture/sentinel-topology.md)。
新增 12 个模拟生命周期测试和 2 个真实 Sentinel 测试方法；真实方法分别遍历 RESP2/AUTO。
测试使用专用 Redis 7.4.2 容器（一主一副本、三个 Sentinel），认证和主从切换不依赖其他项目环境。

### C3 同步修复的基础边界

1. 原 AUTH/HELLO/CLIENT SETNAME 失败后的 close 会遮住认证根因，改为保留握手错误并分类未发送请求。
   初次新增认证失败测试因此失败，修正实现后定向回归通过，不放宽 WRONGPASS 断言。
2. 拓扑主动关闭旧节点原来只产生通用关闭异常；新增内部 topology retirement 路径按写入状态分类。
   Cluster 节点摘除也改用此路径，并增加已写未响应请求的针对性验证。
   整仓回归发现紧接着关闭自有 Resources 时会抢先执行 shutdown，现使退休分类标记在 shutdown 路径保留。
3. 原回调隔离测试可能在注册 thenApply 前已收到回复，导致回调合法地在测试线程等待自身放行。
   改为先注册 continuation 再通过服务端 latch 放行，不更改超时或业务断言。
4. ROLE 检查因本地容量不足被拒绝，不代表现有主连接失效；保留仍有效的主连接并安排发现退避，
   新增单请求容量下在途业务不被关闭的测试。
5. JDK 8 回归暴露旧慢订阅测试的时序假设：消息 burst 可能在 listener 开始前就触发关闭并取消排队回调。
   现在先让第一条 listener 确认运行，再发送溢出 burst；仍要求及时关闭专用连接，不放宽原断言。

已有公开方法不删除/改签名，不引入新的 core 运行时依赖。普通用户 close 保持既有行为。
Skill 检查重点为认证/协议边界、响应 FIFO、主节点角色、资源归属和不可自动重放；没有启动独立 Agent。

### C3 验证入口

```sh
sh scripts/sentinel-test-up.sh
mvn clean test -q -Dboba.straw.runCompatibility=true -Dboba.straw.runCluster=true -Dboba.straw.runSentinel=true
```

源码为 `ae3990f` 后工作树；正式矩阵在隔离目录复制同一源码执行，避免编辑器自动编译覆盖 target。
macOS x86_64 / Colima；Standalone 为 Redis 5/6.2/7.4、Valkey 8.1 的既有测试矩阵，
Cluster 为 Redis 7.4.2 三主三副本，Sentinel 为 Redis 7.4.2 一主一副本、三个 Sentinel。
最终源码逐文件比对与工作树 core/src 一致。每次更换 JDK 先 clean，三个矩阵串行执行。

### C3 最终验收记录（2026-09-22 至 23）

| JDK | 全模块构建与测试 | 真实服务端范围 |
| --- | --- | --- |
| Oracle 8u202 | 105 tests，0 failures/errors/skipped | Standalone 四服务端矩阵、Cluster 三场景、Sentinel 认证/切换 |
| Oracle 17.0.10 | 105 tests，0 failures/errors/skipped | 同上 |
| Oracle 21.0.7 | 105 tests，0 failures/errors/skipped | 同上 |

Sentinel 认证及切换各遍历 RESP2/AUTO；使用的是真实 `SENTINEL FAILOVER`，不是模拟地址替换。
未验证硬停主进程触发的 Sentinel 自动选主或跨主机网络分区，不将主动切换结果扩展为这些场景的通过。
模拟测试另外覆盖写后断连、旧节点退休、切换时未发送失败、角色错误、认证错误、保留旧主连接、
ROLE 本地背压、关闭/取消及外部 Resources。既有 Cluster 与普通命令回归均通过。

最终环境检查：Sentinel 返回 3 个可用节点且 quorum/failover authorization 可达；Cluster 为
`cluster_state:ok`、16384 Slot 全部正常。测试容器保留运行，没有更改其他项目容器。
脚本通过 `sh -n`，工作树通过 `git diff --check`。隔离目录中的 Surefire 报告会随 clean 覆盖，
本表不是不可变的 Maven 发布制品证明。

下一阶段：C4 可用 JDK/平台兼容验收，再按计划进入 C5 命令与二进制接口；TLS 仍留在 C7。

未验证：Sentinel Redis 5/6.2/Valkey、命名 ACL 用户、跨宿主网络分区、长稳、TLS、JDK 11/25 和其他 OS。
自动发现更多 Sentinel、事件订阅加速、Replica 读取未提供；当前配置的多个 Sentinel 和周期重发现是基础恢复路径。

## C4 兼容性验收入口与本机记录（2026-09-27）

本阶段不改运行时或公开 API。使用开发/审查 Skill 核对验证边界，保持 Java 8 与核心零外部运行时依赖。
新增 [兼容性验收指南](../development/compatibility-validation.md) 与
`scripts/run-compatibility-matrix.sh`：显式 JDK、隔离源码、串行 clean test、逐 JDK 日志和报告留存，
失败返回非零且不通过自动重跑隐藏失败。每个 JDK 使用独立 build 目录，避免清理失败时混入旧报告。

CI 增加 JDK 25；模拟测试矩阵为 Linux/Intel macOS/Windows × JDK 8/11/17/21/25，
Linux 独立作业启用 Standalone/Cluster/Sentinel 真实测试。所有作业失败时仍上传报告。
**本次未触发远端 Actions，配置存在不代表这些平台已验收。**

实际平台：macOS x86_64、Colima；Maven 3.9.6；源码为 `c696ae3` 加本阶段脚本/CI/文档改动。
本次未更改 core 源码。各组均执行全模块 `mvn clean test` 并开启三个真实服务端测试开关，
复用现有专用测试容器，未更改其他项目环境。

| JDK | 结果 | 实际服务端范围 |
| --- | --- | --- |
| Oracle 8u202 | 105 tests，0 failures/errors/skipped | Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3；Redis 7.4.2 Cluster/Sentinel |
| Temurin 11.0.32.1+1 | 105 tests，0 failures/errors/skipped | 同上 |
| Oracle 17.0.10 | 105 tests，0 failures/errors/skipped | 同上 |
| Oracle 21.0.7 | 105 tests，0 failures/errors/skipped | 同上 |
| JDK 25 | 未运行 | 官方包下载连接超时；不能记为测试失败或通过 |

JDK 11 从 Adoptium 官方 API 返回的地址下载，按其 SHA-256 校验后解包到专用临时目录，
没有修改系统 JAVA_HOME 或已安装 JDK。JDK 25 下载多次遇到连接超时，保留待验证。
脚本通过 `sh -n`，非法模式/相对 JDK 路径返回退出码 2；CI YAML 通过本地解析，改动通过 diff 检查。

本机证据目录位于 `$TMPDIR` 下 `boba-straw-compatibility-TB9GHF`（8/17/21）和
`boba-straw-compatibility-aZxPj2`（11）；均保留逐次报告、环境与源码。前一组运行的是初版执行器，
三个 JDK 串行 clean 后分别留存报告；后一组验证了最终的逐 JDK 独立 build 目录方案。
这些临时目录不是永久发布证据，后续发布必须将对应提交的 CI 报告归档。

C4 剩余验收：JDK 25、本次配置的远端平台矩阵；ARM64、长稳、TLS、Boot 版本矩阵不在本次通过范围。
之后按原顺序进入 C5 命令与二进制接口，不能用此表宣称“完整客户端”或“全平台兼容”。

## C5 分批执行

源码基线 `c82acdf`；执行顺序与接口边界集中记录在
[命令覆盖清单](command-coverage.md)，避免将 Raw、类型化接口与协议能力混为一谈。
首批补 Standalone 二进制 String：MGET/MSET/MSETNX、SET 选项、APPEND/STRLEN/GETRANGE/SETRANGE。
复用既有 CompletionStage、共享连接和取消传播；不增加同步二进制 facade，不更改拓扑入口。
按命令 Skill 核实官方版本/返回语义，修正 SET GET 的旧注释，并新增编码/边界及真实矩阵测试。
其余 Key/TTL、Hash/List/Set/ZSet、Scan/Stream/Geo/HLL/Lua、阻塞二进制仍待分组完成。

C5.1 实际验收：macOS x86_64 / Colima，Oracle JDK 8u202 和 21.0.7，
各 112 tests、0 failures/errors/skipped；真实四服务端 AUTO/RESP2，加原有 Cluster/Sentinel 回归。
报告与方法边界见覆盖清单。JDK 11/17 本批未重跑，25/其他 OS 仍未验证。
下一批为 C5.2 Key/TTL 与 String 数值/位操作二进制；C5 整体尚未完成。
