# 核心客户端后续执行顺序

更新时间：2026-10-08。C1 基线为 `2bd4993`，C2 基线为 `9a227d4`，C3 基线为 `ae3990f`，C4 基线为 `c696ae3`。
此前后置的 TLS 现进入 C7：先完成 C7、C8 功能及必要的安全与生命周期测试，
再开展长期稳定性和正式性能压测。2026-10-08 已选择 Apache-2.0，正式发布仍受其余发布门禁约束。

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
| C8 | Starter 与发布 | Health、Micrometer、多客户端、配置/生命周期，质量门禁和兼容矩阵；许可证确定后才能发布 | 接入与工程门禁验收完成；Boot 2.7/Java 8、Boot 3.5/Java 21 各 248 项通过；正式发布仍阻断 |

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

## C8 Starter 与发布门禁（2026-10-06）

目标是让业务只配置端点、拓扑与安全策略即可接入，同时不把 Spring 生命周期或监控依赖带入 core。
Starter 不建立另一套连接池、执行器或重试层；具体命令与失败语义沿用核心 Client。

### 接入与生命周期选择

1. 保留旧 `boba.straw.uri/command-timeout/protocol` 默认单例用法；根据 mode 暴露实际客户端类型。
2. 多客户端使用具名注册表，而非运行期动态注册任意 Bean；业务明确选择名称和拓扑类型。
   默认客户端可独立关闭自动创建，各名称之间不隐式继承配置，避免认证和证书串用。
3. 自动配置对应用定义的客户端退让；默认 Bean 和具名注册表分别负责自己创建的资源。
   部分初始化失败回收已创建对象；应用不应在请求中 close 注入的 Client。
4. TLS 使用标准 JDK store/SSLContext，数据节点和 Sentinel 控制链路分别配置；证书加载失败拒绝启动。
5. Health 和 Metrics 仅读取核心状态，不在监控请求中建连、PING、刷新拓扑或重试业务命令。
   Health 只说明本地就绪程度；Metrics 不伪造未暴露的 Sentinel 数据指标，不使用节点地址/Key 标签。

使用方法、配置默认值与安全边界见[使用指南](../usage/quickstart.md#spring-boot-配置)。
Spring Boot 入口兼容依据：[官方 Boot 3 迁移说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)。
保留 factories 和 imports 双入口；支持声明以实际测试版本为准，不推断所有 Boot 3 小版本或 Boot 4。

### 验证与发布边界

- Starter 单元测试覆盖绑定、默认/具名生命周期、初始化失败回收、自定义 Bean 退让、可选依赖缺席、
  Boot 发现自动配置、自动 MeterBinder 接入、Health 开关、监控不发命令、Meter 释放。
- `StarterCompatibilityTest` 验证真实 Cluster、认证 Sentinel，以及混合三拓扑 TLS 配置到命令链路。
- `scripts/run-starter-matrix.sh unit|full-tls JAVA8_HOME JAVA17_OR_21_HOME` 隔离源码和构建产物，
  分别验证 Boot 2.7.18 与 3.5.6；full-tls 使用前述专用容器和 BOBA_TLS_CERT_DIR。
- `mvn verify` 执行 core Enforcer、Java 8 API 检查以及普通测试中的 ArchUnit / 字节码检查；
  `mvn -Pquality clean verify` 增加 Checkstyle、SpotBugs 高优先级阻断、Forbidden APIs 与 JaCoCo 报告。
  按本机普通测试基线设置行/分支覆盖下限：core 80%/65%，autoconfigure 65%/50%；
  覆盖率只是退化门禁，不代表协议/并发语义正确，真实容器组合仍须单独验收。
- `mvn -pl boba-straw-core -am -Papi-check -Dboba.api.baseline=/absolute/core-baseline.jar verify`
  比较公开核心 API 的源码/二进制兼容；internal 不作为稳定公共 API。首个正式版本尚未发布，
  不自动从 Central 猜测基线，必须保存并指定经确认的基线制品。
- 默认禁止 deploy；`release` profile 要求非快照版本、非快照依赖、LICENSE、NOTICE 和显式批准。
  初始验收时许可证未选择；2026-10-08 用户确认采用 Apache-2.0，已添加官方 LICENSE 原文、
  项目 NOTICE 与继承到模块的 POM 许可元数据。根目录 Maven 构建将声明打包至 JAR 的 META-INF，
  同时保留原有模块资源。未虚构版权持有人或声称完成第三方代码权属审计。
  首版已确认使用 `0.1.0-alpha.1`。新增 `release-artifacts` profile 生成源码/Javadoc，
  `release` profile 要求同时启用该 profile，并配置 GPG 签名及 Central 上传插件。
  Token 仅保存在本机 Maven settings，server ID 为 `central`，不进入仓库。
  发布范围为 parent、core、autoconfigure、starter；空的 test-support 和压测模块不发布。
  Starter 无 Java 类，Javadoc 分类包附项目 README、LICENSE、NOTICE 说明。
  本地检查使用 `mvn -Prelease-artifacts test package`，不签名、不上传。
  签名及发布准备好后，根目录使用
  `mvn -Prelease-artifacts,release -Dboba.release.approved=true deploy`；
  插件设置 `autoPublish=false`，上传后等待 Central 校验，仍需在 Portal 确认公开发布。
  该 deploy 命令已在用户授权后执行；Central 远端校验、用户确认公开发布及下载比对均已完成（见下方记录）。
  不把已知性能退化标成验收通过，alpha 版本不等同生产验收。
  2026-10-08 JDK 21 本地 `release-artifacts` 全六模块测试/打包通过：
  core 233 项（36 项跳过）、autoconfigure 16 项（3 项跳过），无失败；
  core/autoconfigure/starter 的主包、sources、javadoc 九个 JAR 均通过归档完整性检查。
  证据：`/tmp/boba-alpha-artifacts-final-20261008.log`。
  `mvn -N -Prelease,release-artifacts validate` 在版本、profile、依赖、许可文件检查通过后，
  因未设置显式批准属性按预期失败，证据 `/tmp/boba-alpha-release-gate-20261008.log`。
  未生成签名、未上传；本轮没有重跑容器专项或性能回归。
  后续用户已创建并上传公钥：`0C44B39E03C1A7212B916D7D94D4F8EFD8E79943`。
  2026-10-08 核对本机签名密钥与 Ubuntu 公钥服务器完整指纹一致。
  本地签名验证在 parent 阶段失败：GPG 返回 `No pinentry`，退出码 2；
  该次构建未运行子模块测试、未完成签名验收、未上传。
  保留日志 `/tmp/boba-alpha-signed-verify-20261008.log`；需先在用户本机完成密码交互，
  再重跑签名与验签，不跳过签名门禁。此前不带签名的测试/打包结果保持不变。
  用户在本机完成密码交互后，2026-10-08 JDK 21 全六模块
  `mvn --batch-mode -Prelease-artifacts,release -Dboba.release.approved=true
  -Dgpg.keyname=0C44B39E03C1A7212B916D7D94D4F8EFD8E79943 verify` 成功。
  日志 `/tmp/boba-alpha-signed-verify-unlocked-20261008.log`；core 233 项（36 项跳过）、
  autoconfigure 16 项（3 项跳过），无失败。发布范围内 parent POM 和三个模块各四份
  POM/主包/sources/javadoc 共 13 份签名逐一验签通过，VALIDSIG 完整指纹与指定密钥一致。
  签名门禁未跳过；未执行 deploy、未上传或公开发布，原失败日志保留。
  用户随后授权上传，2026-10-08 使用上述 release profiles 和指定 GPG 指纹执行 deploy 成功，
  Central 返回 deployment ID `c5f5a994-c98a-45a7-8717-84e29d14c0ce`，状态已验证通过，
  需手动确认发布（`autoPublish=false`），不能描述为已在 Central 公开可下载。
  上传包仅包含 parent、core、autoconfigure、starter，排除 test-support 与 benchmarks。
  构建/上传日志 `/tmp/boba-alpha-central-upload-20261008.log`；原始包
  `target/central-publishing/central-bundle.zip` 的 SHA-256 为
  `e24e6543741ce2db1944790bc295a874a715c435226d6bffaad9b96c1858af15`。
  用户随后确认已在 Portal 发布。2026-10-08 查询上述 deployment 的 API 状态为
  `PUBLISHING`，尚非 `PUBLISHED`；四个模块的公开 POM 下载地址均返回 HTTP 404。
  API 同时返回 `errors.common: Deployment components info not found`，原样记录，不将其
  擅自解释为发布成功或终态失败。当时待发布处理完成及公开下载验证，未重复上传同一版本。
  用户再次确认后，2026-10-08 复查 API 已返回 `PUBLISHED`，四个模块公开 POM 均为 HTTP 200。
  从 `https://repo.maven.apache.org/maven2/io/github/susongyan/` 下载 parent POM、
  core/autoconfigure/starter 各自的 POM、主 JAR、sources、javadoc，共 13 份文件，
  与本地已签名制品逐字节比对全部一致，证据目录 `/tmp/boba-central-published-aHYl95`。
  API 的组件信息字段仍返回上述 common 信息，但发布状态与实际公开下载已独立验证成功。
  首版 alpha 已公开可下载；本次没有重新上传、变更同版本制品或声称性能/生产验收完成。

C8 接入功能与工程门禁已完成下列限定环境验收，不等于正式发布或生产长稳验收。
首批增量质量验收通过：JDK 21 全六模块 `mvn -Pquality verify`，core 232 项（35 项容器测试按普通模式跳过），
Starter 16 项（3 项容器测试跳过），无失败；随后 `jacoco:check@coverage-gate` 验证覆盖下限通过。
日志 `/tmp/boba-c8-quality-accepted.log`、`/tmp/boba-c8-coverage-gate.log`。
SpotBugs 只豁免两个 EventLoop 单写 volatile 计数位置及 JMH 1.37 生成代码的 dead-local-store，
理由和精确匹配规则见 `config/spotbugs-exclude.xml`；不整体关闭并发规则或忽略业务 benchmark 源码。
公开 core API 与 `0783183` 比较通过，证据 `$TMPDIR/boba-straw-api-wcS91o`；
`mvn -N -Prelease validate` 按预期因 SNAPSHOT、缺失 LICENSE/NOTICE、缺少发布批准而失败，未上传制品。

首轮并行验收保留在 `$TMPDIR/boba-straw-starter-q4sH1X`：Java 8 的 Starter TLS Sentinel 使用
默认 500ms 发现预算时，在命令写出前超时；互通测试调整为与核心 TLS 矩阵一致的 3s 发现/5s 命令。
Java 21 的既有 Cluster 测试也发生一次发现超时；未修改生产执行/重试策略，也未放宽该原有测试，
须记录串行复测结果，不以推测的机器负载归因宣布该历史超时已修复。

第二轮 `$TMPDIR/boba-straw-starter-PQZsIO` 中 Boot 2.7/Java 8 的 248 项全量测试通过；
Java 21 的 232 项 core 测试也全部通过，但干净编译后的 Animal Sniffer 拦截了 ByteBuffer
的 Java 9+ 协变返回值方法引用。这是实质性的构建兼容风险，而非误报：仅 source/target=8
不能限制编译器使用新 JDK API，旧的增量 Java 8 产物可能掩盖它。
现统一配置 `maven.compiler.release=8`，使用 Compiler Plugin 3.13.0 在 JDK 8 下自动回退
source/target、在新 JDK 下使用 Java 8 API 签名；依据见
[Maven 官方说明](https://maven.apache.org/plugins/maven-compiler-plugin/examples/set-compiler-release.html)。
质量入口改用 clean verify；修复后的最终结果另行记录，不沿用此前增量构建的通过结论。

最终结果（2026-10-06）：`mvn -Pquality clean verify` 六模块成功，包含 Java 8 API 签名、
字节码/依赖边界、静态分析和覆盖率门槛，日志 `/tmp/boba-c8-quality-clean.log`。
`scripts/run-starter-matrix.sh full-tls` 的 Boot 2.7.18 / JDK 8u202 与 Boot 3.5.6 / JDK 21.0.7
**各 248 tests（core 232 + Starter 16），0 failures / 0 errors / 0 skipped**。
证据 `$TMPDIR/boba-straw-starter-0PyYFl` 保存源码快照、日志和报告；当前 core 与 autoconfigure
的 src 均与快照一致，之后仅格式化 POM、补 CI 版本项及文档。此前 Cluster 发现超时在本轮未复现，
不据此宣称其根因已修复。首批质量报告另存 `/tmp/boba-c8-quality-evidence-xVqfbu`，以 clean 构建为最终依据。

边界补验：Boot 3.0.13 / JDK 17 的 Starter 定向 16 项测试全部通过，包含三拓扑与 TLS。
证据 `/tmp/boba-c8-boot30-java17-T70f51/validation.log`；这次只选择 Starter 测试，不能写成
JDK 17 的 248 项完整回归。复现参数为 `-Dspring-boot.version=3.0.13` 配合
`-Dtest=BobaStrawAutoConfigurationTest,StarterCompatibilityTest -Dsurefire.failIfNoSpecifiedTests=false`，
并启用 runCluster/runSentinel/runTls。CI 已列出 Boot 3.0.13/3.5.6 与 JDK 17/21 组合，
其余组合和其他 OS/JDK 的远程 CI 尚未执行，不由本机结果推断。
release=8 修复后公开 core API 再次比较通过，最终证据 `$TMPDIR/boba-straw-api-evmi0M`。

后续继续限定环境故障复测及性能基线；跨主机分区需要独立主机环境，生产长稳需要约定时长与负载，
不把同机容器或几分钟回归声明为生产验收。冷门 typed API、自定义 Codec、binary batch/Scan 仍按需排期。

### C8 后续验证记录

- 2026-10-06 JDK 21 执行 `scripts/run-fault-injection-tests.sh`：**87 tests，0 failures / errors / skipped**。
  TLS 确定性故障测试已纳入 fault-injection 标签；证据 `/tmp/boba-c8-fault-20261006`。
- 全部构建/回归结束后检查正式压测条件：8 logical CPUs，1m load 27.26，load/CPU **3.408**，
  超过 **1.50** 门槛。未关闭门槛、未停止其他应用、未启动正式 Redis/Valkey A/B/B/A。
  此项仍需空闲测试窗口，固定基线/候选和入口见 [C5 收尾审查](c5-exit-review.md)。
- 跨主机分区与生产长稳等待独立测试环境、运行时长和负载约束；不使用同机容器结果代替。
- 远程多 OS/JDK CI、许可证选择、签名/制品审核与 Central 上传仍未完成。未提交或推送本批变更。

### 单机稳定性与故障恢复验证（2026-10-06 晚间）

本轮先验证稳定性，再在空闲窗口进行性能回归；不以吞吐跑分替代持续运行正确性。
`scripts/run-stability-soak.sh [seconds]` 默认运行 1,800 秒，使用已有且经过镜像、端口核对的
四个独立测试服务（Redis 5/6.2/7.4、Valkey 8.1），拒绝与另一个该入口的运行重叠。
每个服务保留 RESP2、AUTO 各一个客户端，总计八个 worker；每轮提交四组异步 SET/GET，
每十轮增加 typed Pipeline、事务与非 UTF-8 二进制读写，每轮暂停 100 ms，JVM 堆上限 256 MiB。
所有响应检查内容，不仅计数；错误立即失败，不自动重放。仅写入 UUID 隔离的测试 Key，
正常结束删除自己的 Key，异常清理失败保留为附加错误，不执行 FLUSH。

入口保存源码快照及 SHA-256、环境、日志、测试结果与每秒资源采样到独立临时证据目录。
检查结束后请求与写队列排空、健康负载中无重连/背压拒绝、close 后无新增客户端线程遗留；
采样堆占用、线程、文件描述符和连接创建量，供比较运行前后趋势。堆使用量不是存活对象量，
不能凭短跑的内存曲线宣称无泄漏；30 分钟仅是第一轮有界单机稳定性验证。
Cluster/Sentinel 切换及取消、超时、Pub/Sub、TLS 故障通过既有真实拓扑/确定性测试单独验收，
不声称本轮持续负载覆盖这些全部组合，也不等同跨主机网络分区或生产长稳。

执行记录：

- 87 项确定性故障测试通过，证据 `/tmp/boba-stability-fault-20261006-evening`。
- 新增 opt-in 测试后根目录 `mvn test` 通过：core 233 项（36 项按需跳过）、Starter 16 项
  （3 项按需跳过），零失败/错误；日志 `/tmp/boba-stability-unit-20261006.log`。
- Colima 原处于停止状态，已启动；只启动核实后的六个 plaintext 测试容器。
  Cluster 夹具存在重启时重复建群失败，启动入口已改为复用六节点完整配置，残缺配置拒绝覆盖。
- 真实 Cluster/Sentinel 恢复回归 **16 项全部通过**，包括切换与专用能力生命周期；日志
  `/tmp/boba-stability-topology-20261006.log`，XML 报告
  `/tmp/boba-stability-topology-20261006-reports/`。
- 60 秒短跑通过：3,947 worker cycles、零响应校验失败，结束时共享请求/写队列为零，
  八条共享连接无重连，close 后新增客户端线程为零。证据 `$TMPDIR/boba-straw-soak-QpeeaX`。
  短跑期间文件描述符保持 67；堆占用有 GC 回落，不能据此认定长期无泄漏。
- 30 分钟运行 **通过**：2026-10-06 19:10:39（Asia/Shanghai）完成，测试实际耗时
  1,801.036 秒，132,543 worker cycles（不是命令数）；1 test，零失败/错误/跳过，退出码 0。
  证据 `$TMPDIR/boba-straw-soak-ebJ184` 包含源码快照、`exit-code.txt`、XML 报告、日志及
  1,791 条资源采样。八条共享连接创建量保持 8、重连 0，文件描述符保持 67，JVM 活线程保持
  32、客户端线程保持 16；最终 in-flight 与写队列均为 0，close 后新增客户端线程为 0。
  堆占用采样范围 6,259,712～83,041,312 bytes，期间观察到 27 次回落；最终为
  56,847,552 bytes。这说明本轮未观察到线程/FD/连接数增长，不能据此证明生产长期无内存泄漏。
  本轮未开展跨主机分区、TLS/Cluster/Sentinel 持续负载或性能回归；对应故障回归单独计数。
- 今晚 22:00（Asia/Shanghai）检查性能回归条件；稳定性存在阻断、其他测试仍运行或主机
  负载门槛不通过时不开始性能回归，不拿旧快照跑分充当当前版本成绩。

### 当前版本关键路径性能回归（2026-10-06）

四批测量的独立报告、配对数据与 28 份原始 JSON 已整理到
[性能回归归档](../benchmarks/results/20261008-2214adb-vs-7bd1d55-regression/summary.md)。
下文保留实施与诊断过程；本机日志与 JAR 未全部归档，不将原始目录当作远程备份。

用户授权提前开始，原 22:00 启动任务已停用。JDK 21、固定 Redis 7.4.2 容器（2 CPU / 2 GiB），
启动预检 load/CPU=1.139，低于 1.50。执行 `full redis-critical`，每项 5×2s 预热、8×2s
测量、3 forks、GC profiler；四段 ABBA 完整结束、进程退出码 0。基线为网络阶段六
`2214adb`，候选及共同 harness 为 `7bd1d55`。此对比覆盖期间全部改动，不能归因为某个阶段。
原始 JSON、JMH 日志、构建日志、环境和 JAR 校验在
`benchmark-results/20261006-2214adb-vs-7bd1d55-critical/`，未覆盖或删除任何失败证据。

**结论：出现明显回归信号，性能验收不通过，暂停后续扩展测试，原因尚未定位。**
下表配对一为 B02/A01、配对二为 B03/A04；均表示候选/基线，吞吐越大越好、延迟和分配越小越好。
汇总变化为两次候选均值除以两次基线均值减一，非显著性检验；P99 比较是各 run P99 的均值比，
不是合并样本的 P99。Pipeline 延迟按每命令归一化，不是整批耗时。

| 指标 | 配对一 | 配对二 | 汇总变化 |
| --- | ---: | ---: | ---: |
| Async window 1024 吞吐 | 0.878 | 0.647 | -24.3% |
| Pipeline 128 吞吐 | 0.858 | 0.584 | -28.7% |
| Pipeline 128 P99 | 1.011 | 2.378 | +52.3% |
| Sync GET P99 | 1.092 | 1.667 | +33.9% |
| 公平性 healthy GET P99 | 1.479 | 1.997 | +68.2% |
| 慢回调隔离 healthy GET P99 | 1.442 | 1.418 | +43.1% |
| Async allocation B/op | 1.075 | 1.086 | +8.1% |
| Pipeline throughput allocation B/op | 1.052 | 1.039 | +4.6% |

公平性 noisyCommands 同时下降：配对 0.808 / 0.692，汇总 -25.4%；慢回调 noisyCompletions
配对 0.900 / 0.909，汇总 -9.5%。不能只看健康连接延迟而忽略背景吞吐。
Async 吞吐依次为 176,966 / 155,456 / 126,457 / 195,471 commands/s，Pipeline 为
60,496 / 51,929 / 39,700 / 67,949 commands/s。四段存在较大时间波动；启动负载通过并不能
证明全程环境稳定，本轮没有独立连续宿主负载证据，不能断言差异完全来自代码，也不能直接
归咎环境。下一步应先拆分分配开销、版本差异与环境波动，复核证据后再决定受控复测或修复。
Redis/Valkey binary 大 value、Codec 后续对照均未启动；TLS、全矩阵及生产长稳不在本次完成范围。
结果检查任务停用，未修改核心、未提交推送。

#### 第一轮归因：命令构造与重复校验

只读对照 `2214adb` 与 `7bd1d55`，没有修改生产代码，也没有用工作树替换本次测量 JAR。
审查按命令模型保留普通入口安全校验、参数所有权、同步 transport completion 和取消边界，
不把删除安全校验当作性能修复。

- **已测量的额外分配**：使用同一份 Java 诊断程序及上述两份 Core JAR，独立 JVM 按 ABBA
  顺序构造 Raw Pipeline，每批 128 个 GET，仅本地入队、不 execute。每 JVM 预热 20,000 批，
  再测量五次、每次 20,000 批；ThreadMXBean 只计调用线程分配，128 MiB 固定堆。
  基线每条入队命令 **64.5 B**，候选 **88.8125 B**，差 **24.3125 B**；四个 JVM 每次采样
  均一致。另一次同配置重复运行得到相同结果。连接握手/PING 在测量外，不发送这些 Pipeline。
  这证明批次构造额外分配真实存在，不受 Redis 响应时间影响，但不等于解释 28.7% 的端到端下降。
  可复现源码与完整重复运行输出位于原结果目录的 `BobaAllocationProbe.java` 和
  `pipeline-construction-allocation.log`。该探针不是 JMH 吞吐验收，不应据此报告速度提升/下降。
- **源码确认的重复工作**：`BobaStrawPipeline.command` 入队先执行 requireOrdinary，
  `BobaStrawClient.executeBatch` 发送前又逐条 `Arrays.copyOfRange` 后 requireOrdinary。
  后者既重复查验，也临时创建参数数组；此次探针未执行发送，故未计入这部分分配。
- **普通 text typed 路径**：`TypedCommand` 构造时校验并 clone 参数，`arguments()` 再 clone，
  `CommandExecutor` 随后进入 Client 公共 Raw 入口再次校验。同步路径同样有两层准入。
  这些对象未必全部逃逸，实际分配须进一步测量，不能按源码 new/clone 数量推算端到端差值。
- **专用连接逻辑进入普通热路径**：NioConnection 现在对普通请求也创建捕获 dedicated 标志的
  CompletableFuture 子类，普通调用虽传 false，仍走同一个构造点。这是待隔离验证的额外开销，
  未量化因果；不得为了减少分配破坏阻塞取消关闭或普通取消后 FIFO 排空。
- **测量波动仍需分离**：候选第二轮相对第一轮的 Async/Pipeline 吞吐分别约下降 18.7%/23.6%，
  基线第二轮却比第一轮提高约 10.5%/12.3%。重复方向值得追查，但这些变化不能被单一代码差异
  解释为固定幅度；还需受控短窗口配对、独立环境观测或采样剖析，不能宣称已找到全部根因。

建议修复顺序：先以零复制参数视图替代 batch 校验中的临时子数组；再设计包内已验证 text
命令入口，保留公共 Raw 准入及 Cluster 路由校验、避免暴露可变参数；最后独立验证普通 Future
与专用取消路径分流。每一步都需证明语义不变并单独比较，不能一次合并所有优化后再猜收益。
当前阶段为诊断，不改网络读写、协议解析、重试或同步锁策略；修复与正式复测均尚未执行。

#### 用户授权继续：Redis binary 大 Value 对照

用户要求保留回归记录、继续测量而不修改核心。串行队列仍固定 JDK 21、基线 `2214adb`、
候选及 harness `7bd1d55`，正式 full 参数不变。Redis binary 四段 ABBA 全部完成，八份 JSON
均可解析、每份六项（GET/SET × 1 KiB/64 KiB/1 MiB）；证据目录为
`benchmark-results/20261006-2214adb-vs-7bd1d55-redis-binary-large/`。

| 操作 / 大小 | 吞吐 B02/A01、B03/A04 | 吞吐均值比变化 | P99 B02/A01、B03/A04 | P99 均值比变化 | 吞吐测量 allocation 均值比变化 |
| --- | --- | ---: | --- | ---: | ---: |
| GET 1 KiB | 1.080 / 0.920 | -0.6% | 1.100 / 1.301 | +18.3% | +2.42% |
| GET 64 KiB | 0.783 / 0.919 | -14.7% | 0.608 / 1.654 | -3.3% | +0.20% |
| GET 1 MiB | 0.867 / 0.961 | -8.4% | 0.565 / 0.857 | -32.0% | +0.019% |
| SET 1 KiB | 1.020 / 0.942 | -2.4% | 0.724 / 1.239 | -7.0% | +2.11% |
| SET 64 KiB | 0.642 / 0.927 | -21.5% | 0.714 / 0.880 | -22.6% | +0.15% |
| SET 1 MiB | 0.692 / 0.943 | -18.0% | 0.723 / 0.938 | -18.7% | +0.023% |

均值比计算和方向沿用上节，不是合并分位数或显著性检验；allocation 为 GC profiler 的 B/op。
1 MiB GET/SET 每次分配约 1.052 MB，两版相近，未看到整份 payload 级额外复制。
64 KiB / 1 MiB 吞吐两组配对均下降，但幅度不稳定；部分 P99 配对方向相反，且吞吐与延迟
分别运行，不能把不同时间段的 P99 改善解释成已经消除回归。

Redis 批次结束后，Valkey 启动前负载门禁得到 8 核、1m load=27.58、load/CPU=3.447，
超过 1.50。外层会话 9289 以退出码 2 停止；**Valkey binary 与 Codec 尚未启动**，
不是 Redis JMH 执行失败。未关闭门禁、未重启重复任务；结果检查任务停用。
结束时高负载并不能反推全程负载，但进一步限制性能归因可信度；本轮保存观察，不宣称正式
性能验收通过。待空闲窗口继续剩余目标，核心、原始数据和已发现的 critical 回归记录不变。

#### 剩余两批完成：Valkey binary 与 Codec（2026-10-08）

10 月 7 日晚恢复 Colima 后启动，10 月 8 日凌晨完成；外层会话 37688 退出码 0。
版本仍为 `2214adb` / `7bd1d55`，共同 harness `7bd1d55`、JDK 21、full ABBA。
Valkey 启动负载/核 0.781，Codec 启动前 1.059，均通过 1.50 门禁，未并行执行。
两批各四段完整结束：Valkey 八份 JSON 每份六项，Codec 四份 JSON 每份六项，均可解析，
本节引用指标均为有限数值。原始日志、环境、构建记录、JAR 与校验仍在各自目录：

- `benchmark-results/20261007-2214adb-vs-7bd1d55-valkey-binary-large/`
- `benchmark-results/20261007-2214adb-vs-7bd1d55-codec/`

Valkey 8.1.3 / AUTO / 2 CPU / 2 GiB 的结果如下。所有配对继续使用 B02/A01、B03/A04，
汇总为两次候选均值/两次基线均值减一；吞吐越大越好，P99 与 allocation 越小越好。
P99 均值比不是合并样本分位数，吞吐/延迟为分开运行。

| 操作 / 大小 | 吞吐配对 | 吞吐汇总变化 | P99 配对 | P99 汇总变化 | allocation 配对 / 汇总变化 |
| --- | --- | ---: | --- | ---: | --- |
| GET 1 KiB | 1.302 / 1.056 | +17.6% | 1.271 / 0.932 | +8.0% | 1.0384 / 1.0177 / +2.79% |
| GET 64 KiB | 1.072 / 1.057 | +6.5% | 1.203 / 0.957 | +6.4% | 1.0008 / 1.0014 / +0.11% |
| GET 1 MiB | 2.402 / 1.007 | +46.5% | 1.148 / 0.965 | +4.8% | 1.0002 / 1.0002 / 约 +0.02% |
| SET 1 KiB | 1.330 / 1.007 | +15.4% | 1.186 / 1.081 | +12.8% | 0.9747 / 1.0080 / -0.88% |
| SET 64 KiB | 0.905 / 1.088 | -1.7% | 1.148 / 1.231 | +19.3% | 1.0000 / 0.9996 / -0.02% |
| SET 1 MiB | 0.926 / 1.159 | +2.0% | 1.142 / 0.940 | +3.1% | 1.0002 / 1.0002 / 约 +0.02% |

不能将 GET 1 MiB 的汇总 +46.5% 当稳定收益：四段吞吐分别为 43.38 / 104.22 / 89.47 /
88.86 ops/s，第一段基线明显偏低，而第二组配对接近持平。多个 P99 和 SET 吞吐配对方向
不一致，说明时间波动仍显著；SET 1 KiB、64 KiB P99 两组均变差，必须保留该退化信号。
大 payload 分配量仍接近基线，不能据此否定其他执行路径的分配开销或宣布整体性能通过。

Codec 仅测 throughput 与 GC 分配，**没有 P99 测量**：

| Workload | 吞吐配对 | 吞吐汇总变化 | allocation 配对（B/A） | 近似分配 B/op |
| --- | --- | ---: | --- | ---: |
| decodeBulk64 | 1.0073 / 1.0180 | +1.26% | 1.0000 / 1.0000 | 120 |
| decodeBulk64KiB | 0.9518 / 1.0213 | -1.39% | 1.0000 / 1.0000 | 65,592 |
| decodeFragmentedBulkByteByByte | 0.9946 / 1.0311 | +1.28% | 1.0000 / 1.0000 | 1,080 |
| decodeResp3Aggregate | 0.9614 / 0.9692 | -3.46% | 1.0000 / 1.0000 | 632 |
| decodeResponseBurst128 | 1.0097 / 1.0156 | +1.26% | 1.0000 / 1.0000 | 64 |
| encodeGet | 1.0081 / 1.0036 | +0.59% | 1.0000 / 1.0000 | 144 |

分配比值按四位小数显示，不代表浮点数完全相等；六项未见明显分配放大，吞吐汇总范围
-3.46%～+1.28%。RESP3 aggregate 两组均小幅下降，未经受控复测/显著性分析，不直接判定
收益或回归成因；本轮 Codec 数据不足以解释 critical 中约 24%～29% 的端到端吞吐下降。

本次安排的四批对照均已测量完成，但**性能验收仍未通过**：Redis critical 和 binary 的退化、
参数构造额外分配及环境波动尚未收尾。TLS、全 workload 矩阵和生产长稳均不在完成声明中。
不重复启动测试，不修改核心；自动结果检查停用。

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
[命令开发历史记录](command-development-history.md)，避免将 Raw、类型化接口与协议能力混为一谈。
首批补 Standalone 二进制 String：MGET/MSET/MSETNX、SET 选项、APPEND/STRLEN/GETRANGE/SETRANGE。
复用既有 CompletionStage、共享连接和取消传播；不增加同步二进制 facade，不更改拓扑入口。
按命令 Skill 核实官方版本/返回语义，修正 SET GET 的旧注释，并新增编码/边界及真实矩阵测试。
其余 Key/TTL、Hash/List/Set/ZSet、Scan/Stream/Geo/HLL/Lua、阻塞二进制仍待分组完成。

C5.1 实际验收：macOS x86_64 / Colima，Oracle JDK 8u202 和 21.0.7，
各 112 tests、0 failures/errors/skipped；真实四服务端 AUTO/RESP2，加原有 Cluster/Sentinel 回归。
报告与方法边界见开发历史记录。JDK 11/17 本批未重跑，25/其他 OS 仍未验证。
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
保留原断言/超时；失败与最终报告位置见开发历史记录。没有重写 NIO 调度或宣称压测完成。

### C5 typed 执行复用第一批

基线 `4531b6e`：新增内部 TypedCommand<T>/CommandExecutor，迁移现有异步 String 普通方法，
并为 Cluster/Sentinel 增加 async()，不复制命令实现。取消仍传播至既有拓扑 Future，
没有增加自动重试；Standalone 阻塞路径不变，另外两种拓扑的阻塞方法明确本地拒绝。
本批不包括 binary/sync 执行统一、Pipeline/事务 typed 结果、Scan 页模型，C5 仍在进行中。
测试结果与证据追加在命令开发历史记录，不将抽象复用标为完整拓扑专用能力完成。

### C5 typed 批量结果第二批

基线 `d74d59c`：Standalone Pipeline/事务增加 typed() 本地入队目录与结果句柄，
executeTyped()/execTyped() 返回 BobaStrawBatchResult；原 Raw execute()/exec() 保留。
初始高频 String 方法共 16 个，覆盖主要数据结构，不为批量新建线程或复用普通连接执行事务。
新结果区分 WATCH abort、空事务、单条服务端错误和整批网络失败；取消仍排空 Pipeline 或销毁事务租约。
验收与来源见命令开发历史记录。下一步为 Scan 页模型；binary/sync 执行统一、拓扑 binary 与专用组合仍需后续批次。

### C5 Skill 同步与 Scan 第三批

基线 `5c8c4cc`：先修正开发/使用/审查 Skill，明确普通 Typed、特殊执行、普通 Raw 的选择；
Typed 与特殊能力不互斥。新增 CMD-13/14、BSU010/011，清除 Sentinel 过时的未实现描述。
随后依更新规则实现 scan() 分页入口与 ScanArgs/ScanPage，复用异步内核、保留取消传播；
Cluster 禁止没有节点绑定的数据库 SCAN，单 Key 三种扫描复用 Slot 路由。
仅 String 异步与基础 MATCH/COUNT；不隐式遍历、不去重、不提供快照或拓扑切换连续性承诺。
验收记录见开发历史记录。binary/sync 执行统一、binary Scan 和剩余 C5 退出审查仍需后续处理。

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
参数深快照增加复制成本，性能基线需后续重测。验收记录见命令开发历史记录；
C5 最终退出审查及历史非法 H 追踪仍未关闭。

### C5 不可变 binary 帧与退出核对第五批

基线 `c49bcdf`：binary 调用直接编码成不可变 EncodedCommand，取消参数深快照与执行器副本，
只读 buffer 每请求独立游标，共用既有准入/取消/FIFO，不增加重试或改变拓扑范围。
JDK 8/21 全模块真实兼容矩阵各 147 tests 全通过；公开 Client/Binary/Sync 签名不变。
[C5 收尾审查](c5-exit-review.md) 汇总冻结高频范围与验收映射；功能核对通过不等于所有风险清零，
历史非法 H 根因仍未定位，C6 拓扑组合及后续 TLS/Starter 不得提前标为完成。
