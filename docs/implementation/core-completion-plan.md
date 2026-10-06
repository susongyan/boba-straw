# 核心客户端后续执行顺序

更新时间：2026-10-06。C1 基线为 `2bd4993`，C2 基线为 `9a227d4`，C3 基线为 `ae3990f`，C4 基线为 `c696ae3`。
此前后置的 TLS 现进入 C7：先完成 C7、C8 功能及必要的安全与生命周期测试，
再开展长期稳定性和正式性能压测。正式发布仍受许可证选择等发布门禁约束。

## 阶段与验收

| 顺序 | 范围 | 完成条件 | 状态 |
| --- | --- | --- | --- |
| C1 | 事务和阻塞命令专用连接 | 租约只归还一次；取消/超时/关闭销毁；池等待不阻塞归还；真实 WATCH/EXEC 和阻塞隔离验证 | 本文限定范围已完成 |
| C2 | Cluster 连接与拓扑 | 节点退避重连、周期/事件刷新、故障摘除、MOVED/ASK 和多 Key 策略，真实集群故障测试 | 本文限定普通命令范围已完成 |
| C3 | Sentinel | 多 Sentinel 发现、认证边界、主节点切换、旧连接处理、明确未知执行结果，真实切换验证 | 本文限定普通命令范围已完成 |
| C4 | 可用环境的 JDK/平台验证 | 记录实际 JDK/OS/服务端矩阵，其他平台由 CI 验证，不将本机通过泛化 | 本机 8/11/17/21 通过；25 与其他平台待验证，入口已落地 |
| C5 | 高频命令与三层 API | 主要数据结构高频接口、命令元数据、Typed/特殊能力/Raw 边界与协议测试；不追求全命令 | 冻结功能完成；测试端口冲突 H 路径已修复，历史异常核对与正式性能复测待收尾 |
| C6 | 拓扑功能收尾 | Cluster/Sentinel 与新增命令、专用连接组合验收；不重复宣称 C2/C3 已完成 | C6.1–C6.5 限定范围完成；JDK 8/21 full 各 200 项通过 |
| C7 | TLS | SSLEngine、证书/主机名校验、拓扑与专用连接配置传播、关闭/重连测试 | 本文限定功能验收完成；JDK 8/21 full-tls 各 230 项通过 |
| C8 | Starter 与发布 | Health、Micrometer、多客户端、配置/生命周期，质量门禁和兼容矩阵；许可证确定后才能发布 | C7 后实施功能；不执行正式发布 |

用户后续可调整顺序。每阶段只记录真实完成和验证项；不将“网络模型六阶段完成”等同整个客户端完成。
不自动重试命令，网络断连、取消和超时均不能解释为服务端撤销。

当前总览见 [roadmap](roadmap.md)，C5 退出依据见 [收尾审查](c5-exit-review.md)。
2026-09-29 Lua L1 已补直接命令和 typed/binary 接口，L2 注册执行器已补代码，验证见下方链接；
L2 最终生产源码 JDK 8/21 full 各 176 tests 通过；额外两项 Cluster 路由测试随后在两种 JDK unit 通过。
L1 时 JDK 8 的事务等待超时及 Sentinel 模拟协议 H 异常本轮未复现但未定位，不宣称历史问题已修复。
详见 [Lua 测试记录](../testing/lua-scripting-validation.md)。
以下各批记录是历史快照，“下一批/待完成”以顶部现状及最新批次为准；
binary Scan/batch、完整 Stream/Geo/HLL 等未纳入 C5 冻结范围，后续按需排期。
2026-09-29 确认不规划读写分离或 Replica 读策略；Cluster/Sentinel 普通读写走当前主节点，
主从切换支持不变。该项不是 C6 或发布验收缺口，见[架构决策](../architecture/decisions.md)。

## C7 设计与实施入口（2026-10-06）

设计与原理见[网络模型：C7 TLS 传输设计](../architecture/network-model.md#c7-tls-传输设计)。
该节说明目标、目标 API、默认安全策略、握手时序、EventLoop 所有权、失败语义和拓扑边界。
其中 API 示例已接入实现；测试范围与未验证项在本节记录，不把设计描述作为验收证据。

- [x] 将设计目标、原理、流程图和验收边界落到架构文档。
- [x] TLS options、URI 语义、SSLEngine 传输及独立有界握手任务执行器。
- [x] 握手 deadline、加解密公平预算、缓冲上限、取消与关闭语义的代码实现。
- [x] Standalone/Cluster/Sentinel 及事务、阻塞、Pub/Sub 的 TLS 配置传播。
- [x] 下列本机安全负向、密文碎片、重连和资源释放测试；JDK 8/21 与既有明文全量回归。
- [x] 真实 TLS 服务端矩阵、确定性密文部分写/截断和资源上限故障注入。

长稳和正式压测后置，不意味着跳过上述功能与安全验收。

本机功能测试入口：`TlsConnectionTest`、`TlsOptionsTest`、`internal.TlsTaskCapacityTest`。
使用本机非复用 loopback 监听，JDK keytool 临时生成证书，不提交私钥或固定测试证书。
测试运行需要完整 JDK（含 keytool）和本机监听权限。

已补场景：TLS 1.2 与当前 JDK 支持的 TLS 1.3、大 binary 响应、并发与 Pipeline 保序、
加密 record 碎片代理、私有 CA/错误主机/不受信任/过期证书、mTLS、握手超时、取消与命令超时、
断连不重放及新连接握手、慢 trust manager 不阻塞其他连接和关闭后迟到任务隔离；
事务/阻塞/PubSub Push、模拟 Cluster ASK/拓扑刷新与 Sentinel 主节点变化也走真实 TLS 握手。
任务容量测试覆盖资源级队列有界和关闭，不等同高并发握手压力验收。

首批源码回归（2026-10-06）：`scripts/run-compatibility-matrix.sh full`，JDK 8u202 与
JDK 21.0.7 **各 219 tests，0 failures / 0 errors / 0 skipped**，六个 Maven 模块成功。
其中新增 18 项 TLS 相关测试、1 项 Selector 到期等待边界测试；真实 Redis 5/6.2/7.4、
Valkey 8.1、Cluster/Sentinel 回归仍是既有明文容器，不冒充真实 TLS 服务矩阵。
证据目录为 `$TMPDIR/boba-straw-compatibility-QChfim`，包含源码快照、环境、日志和测试报告；
该批运行时源码已核对与快照一致；后续新增验收测试见下表。没有执行长稳压测或发布。

### C7 收尾验收映射

2026-10-06 最终执行 `scripts/run-compatibility-matrix.sh full-tls`：JDK 8u202 与
JDK 21.0.7 **各 230 tests，0 failures / 0 errors / 0 skipped**，六个 Maven 模块成功。
证据目录 `$TMPDIR/boba-straw-compatibility-Xfivdq` 保留环境、源码快照和测试报告；
已核对当前 `boba-straw-core/src` 与该快照一致。C7 在下述范围内完成，不包含 C8 或生产长稳。

| 范围 | 可执行证据与边界 |
| --- | --- |
| 真实服务端 | `TlsCompatibilityTest`：Redis 6.2.14、7.4.2、Valkey 8.1.3，RESP2/AUTO，mTLS/认证、binary、Pipeline、事务、阻塞、Pub/Sub、注册脚本 |
| TLS Cluster | 同一测试类：Redis 7.4.2 三主节点，专用连接、真实 ASK 迁移和 MOVED Slot owner 变化；测试后恢复 Slot，不等同跨主机分区或副本晋升验收 |
| TLS Sentinel | 同一测试类：Redis 7.4.2 双数据节点、三个 Sentinel；控制和数据链路均加密，真实 FAILOVER、旧事务/订阅退休、新主节点专用能力 |
| 安全负向 | `TlsConnectionTest` 校验种子之后的发现节点及 ASK 目标不能绕过证书验证；真实拓扑测试分别覆盖 Sentinel 控制/数据链路及 Cluster 缺失客户端身份、错误 Redis 认证 |
| 部分写与资源上限 | `internal.NioTlsFaultTest`：零写/逐字节写保持密文与消费顺序，写异常不重放，wrap/unwrap/输入缓冲上限，EOF、零进展、任务拒绝 |
| 密文截断 | `TlsConnectionTest`：真实 JSSE 连接的响应密文被代理截断，待响应命令收到可能已执行异常，而非部分成功 |

确定性 I/O 故障测试使用模拟 SSLEngine/SocketChannel，验证状态机，不将它当作密码学验证；
加密互通、安全负向和实际 Redis 语义由 JSSE 与真实容器测试补足。生产长稳、跨主机故障、
正式性能压测和扩展 JDK/OS 矩阵仍后置；Starter 的 TLS 配置接入属于 C8。

### 复现 TLS 容器验收

先启动 Colima/Docker 和原有 `full` 模式需要的明文测试容器，再执行：

```sh
sh scripts/tls-test-up.sh /absolute/new/test-certificate-directory
export BOBA_TLS_CERT_DIR=/absolute/new/test-certificate-directory
sh scripts/run-compatibility-matrix.sh full-tls /absolute/jdk8/home /absolute/jdk21/home
# 不再需要测试容器时：
sh scripts/tls-test-down.sh
```

使用完整 JDK（含 keytool）、Maven、Docker 和 OpenSSL。启动脚本拒绝已有证书目录及同名容器；
仅绑定本机端口：17679–17681（Standalone）、17601–17603（Cluster）、17701–17702
（Sentinel 数据）、27701–27703（Sentinel 控制）。测试会变更这些专用实例的 Slot 和主节点，
不得指向业务环境。关闭脚本检查 `io.github.susongyan.boba-test=tls` 标签，只删除四个专用容器，
数据不持久化；保留证书目录，不删除其他 Redis 实例。

证书仅供测试，7 天过期，私钥不入库。PKCS12 用测试密码 `test-only` 和兼容 JDK 8 的
SHA-1/3DES 容器编码；这不是生产密钥存储建议，也不改变 TLS 1.2/1.3 传输策略。
过期后关闭测试容器并指定新的证书目录重建。Redis 5 没有原生 TLS，仍由明文兼容矩阵覆盖。

### C7 回归中发现的 deadline 边界修复

首轮 full 在 JDK 8 的既有 `commandTimeoutIsOwnedByTheConnectionEventLoop` 停住。
线程栈显示调用线程等待同步 GET，测试服务端等待客户端关闭，EventLoop 停在 Selector 等待。
检查发现 `nextSelectTimeoutMillis()` 在 deadline 恰好到期时返回 0；
`Selector.select(0)` 的含义是无限等待，不是立即轮询。这条竞态同样影响 TLS 握手 deadline。

已将到期及亚毫秒剩余时间统一限制为至少 1 ms，保留任务/已到期工作触发的 `selectNow()` 路径；
新增确定性边界测试 `deadlineExpiringBetweenDueCheckAndSelectCannotCauseAnInfiniteWait`。
不把该问题与历史非法 H 测试端口冲突混为同一根因。

## C6 执行分组（2026-10-05）

| 子阶段 | 内容 | 验收重点 |
| --- | --- | --- |
| C6.1a | Sentinel binary 普通命令及注册脚本 | 原始字节、发现/切换、取消/背压、不重放 |
| C6.1b | Cluster binary 普通命令及注册脚本 | 原始字节 Key、Hash Tag、同 Slot、MOVED/ASK、失败分类 |
| C6.2 | 两种拓扑的同步普通 facade | 等待 transport completion，不等待业务 callback worker |
| C6.3 | 拓扑 Pipeline | 明确节点/Slot、结果顺序、准入和重定向边界；不静默跨 Slot 拆分 |
| C6.4 | 拓扑事务与阻塞能力 | 专用连接绑定主节点代次，切换即失效，不迁移或重放在途操作 |
| C6.5 | 拓扑 Pub/Sub | 专用连接、确认/退订/关闭、切换后的可见失败及重新订阅契约 |

本批基线 `bcde420` 加未提交 C6 工作树。六个分组已补齐并完成下述限定环境验收。
范围为普通 binary、String sync、String Pipeline/事务、String BLPOP/BRPOP、经典 Pub/Sub，
并完成 Lua L4 的拓扑组合。binary Scan/batch/阻塞/订阅、其他阻塞命令和 sharded Pub/Sub 不在本轮范围。
本机 Redis 7.4.2 Cluster/Sentinel 是拓扑验证环境，不泛化为其他版本/OS 或生产长稳。
行为与用法分别维护在 [Cluster](../architecture/cluster-topology.md)、[Sentinel](../architecture/sentinel-topology.md)
和[能力表](../usage/supported-features.md)，下方保留历史验证过程。

### C6.1b–C6.5 验收映射

| 范围 | 实现选择 | 可执行证据 |
| --- | --- | --- |
| Binary | 原始字节 Slot 摘要，不往返 String；复用有界 MOVED/ASK | ClusterLifecycleTest.binaryMovedAndAskReuseRoutingWithoutTextConversion；两种拓扑 IntegrationTest 的 binary 方法 |
| Sync | 等待 transport，ASKING 也不等待业务 worker | 两种 LifecycleTest 的 callback worker 占用测试 |
| Pipeline | 一次批量准入/写入；Cluster 整批同 Slot；不重放重定向 | ClusterLifecycleTest.pipelineRejectsCrossSlotBeforeSendingAndDoesNotReplayMoved；两种 IntegrationTest |
| 事务 | 惰性池；Cluster routingKey/所有 Key 同 Slot；绑定主节点与拓扑代次 | TopologyDedicatedTestFixture 的 WATCH abort/typed Lua；真实切换测试；ClusterLifecycleTest.topologyRetirementPreservesAmbiguousExecAndClosesLease |
| BLPOP/BRPOP | 单次专用连接；取消关闭；不跟随 MOVED/ASK 重放 | TopologyDedicatedTestFixture；ClusterLifecycleTest.blockingCancellationClosesSocketAndMovedIsNotReplayed；既有 DedicatedConnectionLifecycleTest |
| Pub/Sub | subscribe/psubscribe 确认与退订；可观察 termination；手工重订阅重新选主 | TopologyDedicatedTestFixture 消息/模式/关闭及取消观察不关闭订阅；Cluster/Sentinel 真实切换测试 |

真实切换为 Sentinel RESP2/AUTO 主动 FAILOVER、Cluster AUTO 计划晋升，原有 Cluster RESP2
主进程暂停/自动晋升回归保留。Slot owner 变化保守退休全部专用连接，不仅受影响 Slot；
这不是精准迁移能力，也不提供 Pub/Sub 丢失消息补偿或事务重试。
事务池默认每内部 Client 8 条、获取等待 1 秒、空闲回收 1 分钟；阻塞默认最多 32 条。
两种拓扑暂未暴露这些池参数，Standalone 原配置保留；Sentinel 首次专用调用会增加一条管理器共享连接。

公开兼容性：仅新增拓扑入口和 Subscription 的 default termination()，不删除或更改已有 public 签名。
第三方 Subscription 实现默认不支持 termination；内置实现提供观察。原拓扑 BLPOP/BRPOP 从本地拒绝
变为真实专用执行，是明确的行为扩展。核心仍 Java 8、零第三方运行时依赖，无新增响应式运行时。
按开发/命令/审查 Skill 核对资源归属、取消、FIFO 和 Key 路由；没有声称独立 Agent 或跨模型评审。

### C6 后续验证记录（2026-10-05 至 06）

均在隔离源码目录执行根 Maven `clean test`，同时开启 compatibility、cluster、sentinel 三个开关，
JDK 间串行，不与 IDE 共用 target；命令为 `scripts/run-compatibility-matrix.sh full <JDK8> <JDK21>`。
平台 macOS x86_64/Colima；Standalone Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3，
拓扑 Redis 7.4.2。两种协议 RESP2/AUTO 的 API 正常路径均覆盖。

- `$TMPDIR/boba-straw-compatibility-DtD3JD`：新增测试误用 ClusterSlot.of(byte[])，编译失败；改为 ofBytes。
- `$TMPDIR/boba-straw-compatibility-qamzBL`：新增 callback 饥饿测试挂起，线程栈显示 thenAccept
  注册时前序 Future 已完成，测试主线程进入自身等待。给模拟服务端增加响应 gate，先注册再放行；
  没有修改客户端超时或将此测试竞态当作历史非法 H 的根因。终止的仅为本次测试进程。
- `$TMPDIR/boba-straw-compatibility-tdCCZv`：C6.1–C6.3 两种 JDK 各 194 项通过；
  `$TMPDIR/boba-straw-compatibility-VyiJWf`：增加 Pipeline 失败不重放测试后各 195 项通过，全模块成功。
- `$TMPDIR/boba-straw-compatibility-el1Epj`：新夹具引用不存在的 async.publish，测试编译失败；
  改用已有 Raw PUBLISH，Cluster 明确声明零 Key，不借本轮虚构 typed 方法。
- `$TMPDIR/boba-straw-compatibility-oVaiuW`：两种 JDK 各 197 tests，3 failures/1 error；
  旧夹具仍要求拓扑阻塞方法“不支持”、用 null Client 构造事务，以及 Cluster PUBLISH 未声明 Key。
  修正为真实 BLPOP/BRPOP 结果验收、有效 Client 和显式无 Key 路由，保留连接策略拒绝断言。
  新增真实主从切换的旧事务失效和订阅终止用例已通过。
- `$TMPDIR/boba-straw-compatibility-MgHXNK`：两种 JDK 各 199 tests，零失败/错误/跳过，全模块成功。
  随后补事务拓扑代次并发保护和取消 termination 观察不影响订阅的断言，最终运行见下一条。
- `$TMPDIR/boba-straw-compatibility-RUxy60`：JDK 8 全 199 项通过；JDK 21 为 199 项中一项失败，
  ScriptRegistryTest.binaryInputsAreSnapshotsAndNullSuccessIsCached 在 3 秒内未收到 EVAL；
  C6 新用例通过，但全量门禁未通过。与历史 Lua 等待失败相似，不能仅凭相似症状断言同一根因。
  已保留报告，新增 Future 状态、发送/接收字节与模拟服务端错误诊断，不放宽等待时间。
- 进一步复现确认：Java wildcard 测试监听与 VS Code HTTP loopback 监听共存，RESP PING
  进入 HTTP 服务，返回 HTTP 400；非复用、具体 loopback 绑定能拒绝冲突。统一修复测试监听，
  没有修改生产 decoder。证据与不能泛化的历史范围见[H 诊断](../testing/binary-resp-diagnostics.md)。
- **最终 `$TMPDIR/boba-straw-compatibility-CuD7SN`：Oracle JDK 8u202 / 21.0.7 各 200 tests，
  0 failures/errors/skipped，全六模块成功。** core/src 与隔离源码逐文件一致，`git diff --check` 通过。
  辅助千轮诊断在独立临时目录运行，不并发修改 Cluster/Sentinel 夹具；本次不是性能压测。
- 修复后的 JDK 21 定向重复证据：`/private/tmp/boba-c6-bound-loopback-8eE5ia`，
  ScriptRegistryTest + BinaryStringCommandsTest 共 25 tests 通过；脚本场景 1000 轮，
  原二进制 Null 场景 1000 轮/2000 连接。JDK 8 同一串行任务也通过，报告被后一轮 clean 覆盖，
  因此另以独立目录补存 JDK 8 证据，不把覆盖后的 JDK 21 XML 当作 JDK 8 报告。
- 独立 JDK 8 千轮证据 `/private/tmp/boba-c6-bound-loopback-jdk8-pNO25m`：同样 25 tests，
  零失败/错误/跳过，两项重复参数均为 1000；XML 保留 Java 1.8.0_202 与实际参数。

本轮已关闭被复现的测试端口冲突路径，不追认历史所有 H/等待失败同因；证据目录为本机临时留存，发布仍需归档 CI。
未验证：本版 JDK 11/17/25、其他 OS/ARM64、其他版本 Cluster/Sentinel、跨主机分区、生产长稳及新性能基线。
TLS 仍为 C7，Starter/正式发布为 C8，均不在 C6 完成声明内。

### C6.1a 实现与验证记录

- 新增 Sentinel `binary()`，复用既有命令目录、不可变帧和普通准入；
  String/binary 共用 executeOnPrimary，不改变发现和切换状态机，不新增业务重试。
- Sentinel 注册脚本开启二进制执行；仍按实际物理连接缓存提示，明确 NOSCRIPT 才恢复一次。
- SentinelIntegrationTest 验证 RESP2/AUTO 下非 UTF-8 Key/value、Null、空值、Hash、
  服务端错误后连接可用、直接 LOAD/EVALSHA/EVAL、注册脚本重复执行，以及切换前后 binary GET。
- SentinelLifecycleTest 验证 binary 写后断连的可能已执行分类、不向新主重放、
  发现期间明确未发送、已发送取消后仍占连接准入额度。
- 首轮 `$TMPDIR/boba-straw-compatibility-xlh0PZ` 两种 JDK 各 190 tests / 1 failure：
  新测试将既有 binary SET 的 byte[] 返回值与 String 比较。已修正测试为字节比较，未改变 API。
  首轮报告保留，不通过忽略失败或放宽等待修绿。
- 修正后 full 证据：`$TMPDIR/boba-straw-compatibility-McuOCh`。
  JDK 8u202 全 190 项通过、无跳过、全模块成功；JDK 21.0.7 为 190 项中 189 通过、1 failure，
  无跳过。新增 Sentinel 用例通过；失败为既有
  DedicatedConnectionLifecycleTest.blockingTimeoutAndClientCloseReleaseSocket：
  Peer.awaitHeld 两秒内未见 BLPOP，请求未到达的根因尚未确定。
  本批没有修改 Standalone 阻塞路径，不据此推断故障必然与变更无关或仅由环境导致。
  JDK 21 全量门禁未通过，C6.1a 暂记实现及专项验证完成、回归收尾未关闭。
- 同一 JDK 21 源码快照定向复测 DedicatedConnectionLifecycleTest、
  SentinelLifecycleTest、SentinelIntegrationTest：40 项通过、零跳过。
  最新定向报告在上述 run-2/build 的 surefire-reports；原失败报告保留在
  run-2/boba-straw-core/surefire-reports 与 maven.log。单次复测通过不关闭根因待办。

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

### 2026-09-28 规划调整与本批落地

用户确认按《规划C5阶段》收敛范围。新设计见 [命令模型](../architecture/command-model.md)，
取代旧 C5.4/C5.5 的编号含义；C5.4 为元数据化，C5.5 为 Typed/特殊能力/Raw 三层边界。
保留前面的历史记录，不将聊天中“普通命令生产路径完成”泛化为生产长稳或完整拓扑能力已验收。

本批新增 Key/TTL/Counter/Bit binary，以及 Hash/List/Set/ZSet 高频 binary 与缺失的 String sync/async。
注册表实际用于 Cluster Key 路由、Standalone/Sentinel Raw、Pipeline 和事务入口限制。
服务端版本仅作为已核实元数据，不改变 HELLO/AUTO、不按读写属性自动重试。
同步新增方法仍等待 transport completion，不经 callback worker；核心没有新增运行时依赖。

行为收紧：共享 Raw/Pipeline 拒绝已知状态型/阻塞/订阅命令；事务 helper 同样拒绝这类普通入队。
WAIT 是连接关联的复制屏障，测试夹具改为独占连接上的 SET+WAIT，不放宽复制确认断言。
未知普通 Raw 仍是低频命令出口，但不证明未知模块/未来命令安全；Cluster 必须声明全部 Key。
Scan typed 页结果、旧 mapper 全量迁移、复杂可选参数元数据以及 C6 专用拓扑组合仍待完成。
不再将完整 Stream/Geo/HLL 或冷门命令 typed 套件作为 C5 的完成前提。

本批最终 JDK 8u202/21.0.7 各 121 tests、0 failures/errors/skipped，包含四服务端
AUTO/RESP2 与原有 Cluster/Sentinel 实测。期间修正一处既有公平性测试的观察 Future 竞态，
保留原断言/超时；失败与最终报告位置见覆盖清单。没有重写 NIO 调度或宣称压测完成。

### C5 typed 执行复用第一批

基线 `4531b6e`：新增内部 TypedCommand<T>/CommandExecutor，迁移现有异步 String 普通方法，
并为 Cluster/Sentinel 增加 async()，不复制命令实现。取消仍传播至既有拓扑 Future，
没有增加自动重试；Standalone 阻塞路径不变，另外两种拓扑的阻塞方法明确本地拒绝。
本批不包括 binary/sync 执行统一、Pipeline/事务 typed 结果、Scan 页模型，C5 仍在进行中。
测试结果与证据追加在命令覆盖清单，不将抽象复用标为完整拓扑专用能力完成。

### C5 typed 批量结果第二批

基线 `d74d59c`：Standalone Pipeline/事务增加 typed() 本地入队目录与结果句柄，
executeTyped()/execTyped() 返回 BobaStrawBatchResult；原 Raw execute()/exec() 保留。
初始高频 String 方法共 16 个，覆盖主要数据结构，不为批量新建线程或复用普通连接执行事务。
新结果区分 WATCH abort、空事务、单条服务端错误和整批网络失败；取消仍排空 Pipeline 或销毁事务租约。
验收与来源见命令覆盖清单。下一步为 Scan 页模型；binary/sync 执行统一、拓扑 binary 与专用组合仍需后续批次。

### C5 Skill 同步与 Scan 第三批

基线 `5c8c4cc`：先修正开发/使用/审查 Skill，明确普通 Typed、特殊执行、普通 Raw 的选择；
Typed 与特殊能力不互斥。新增 CMD-13/14、BSU010/011，清除 Sentinel 过时的未实现描述。
随后依更新规则实现 scan() 分页入口与 ScanArgs/ScanPage，复用异步内核、保留取消传播；
Cluster 禁止没有节点绑定的数据库 SCAN，单 Key 三种扫描复用 Slot 路由。
仅 String 异步与基础 MATCH/COUNT；不隐式遍历、不去重、不提供快照或拓扑切换连续性承诺。
验收记录见覆盖清单。binary/sync 执行统一、binary Scan 和剩余 C5 退出审查仍需后续处理。

### Java 8 间歇性 Binary RESP 诊断

基线 `ee7e81a`，仅增加测试与诊断证据，没有根据一次未复现错误改动生产 decoder。
固定二进制回复的所有三段分片边界、输入缓冲复用、后续回复匹配测试通过；
Java 8 定向 200 轮/400 条独立连接未复现。测试附加端点与服务端状态，保留未来故障线索。
详情及复跑入口见 [Binary RESP 诊断](../testing/binary-resp-diagnostics.md)。
原始非法 H 的来源仍待定位，新的通过结果不能作为根因已修复的证明。

### C5 binary / sync typed 第四批

基线 `b95a320`：Standalone binary 普通方法迁入 TypedCommand.binary / BinaryCommandExecutor，
同步普通方法复用 TypedCommand，但仍等待 transport 并在调用线程解码；专用生命周期不变。
本批没有新增公共方法，不包含拓扑 binary/sync 或 binary Scan/batch。
参数深快照增加复制成本，性能基线需后续重测。验收记录见命令覆盖清单；
C5 最终退出审查及历史非法 H 追踪仍未关闭。

### C5 不可变 binary 帧与退出核对第五批

基线 `c49bcdf`：binary 调用直接编码成不可变 EncodedCommand，取消参数深快照与执行器副本，
只读 buffer 每请求独立游标，共用既有准入/取消/FIFO，不增加重试或改变拓扑范围。
JDK 8/21 全模块真实兼容矩阵各 147 tests 全通过；公开 Client/Binary/Sync 签名不变。
[C5 收尾审查](c5-exit-review.md) 汇总冻结高频范围与验收映射；功能核对通过不等于所有风险清零，
历史非法 H 根因仍未定位，C6 拓扑组合及后续 TLS/Starter 不得提前标为完成。
