# C5 命令与二进制接口覆盖

本清单以源码公开方法为准，Raw 能发送不等于已提供类型化 API。2026-09-28 按用户确认规划，
C5 收敛到主要数据结构的高频 API，不以全量 Redis 命令或冷门选项为目标。
具体架构、三层边界及退出标准见 [命令模型](../architecture/command-model.md)。
不承诺覆盖未来版本新增命令或 Redis 模块命令。每批实现和验收分别记录，不使用“完整命令”笼统结论。

## 基线缺口与执行分组

基线 `c82acdf`：String 同步/异步有常用 Key/String 命令、Hash 三个、List 三个普通命令和
BLPOP/BRPOP、Set 两个、ZSet 两个及 EVAL。二进制类型化仅异步 GET/SET/DEL。
Cluster/Sentinel 仅 String Raw 普通命令，不存在可直接复用的二进制 facade。

| 批次 | 范围 | 当前状态 |
| --- | --- | --- |
| C5.1 | Standalone 二进制 String 批量、SET 选项、字节范围 | 本批实现，验证记录见下 |
| C5.2 | Key/TTL、Counter、Bit 高频二进制接口 | 本批已实现，按下方记录验收 |
| C5.3 | Hash/List/Set/ZSet 高频普通命令及二进制接口 | 本批已实现冻结清单，非所有命令/选项 |
| C5.4 | 命令元数据化 | 注册表已用于路由和入口策略，新方法复用 decoder；复杂选项/旧 mapper 迁移仍有限定 |
| C5.5 | Typed / 特殊能力 / Raw 三层边界 | 入口限制已落地；Scan 类型化页结果、拓扑与特殊能力组合尚待完成 |
| C6 | Cluster/Sentinel 类型化、二进制与专用连接组合 | 按核心计划后续验收，不能通过 UTF-8 转换二进制 Key 绕过 |

## C5.1 新增接口

入口为 `client.binary()`，返回 Java 8 `CompletionStage`，没有新增同步二进制 facade。
沿用共享执行内核与取消传播，不改物理连接、FIFO、协议协商和重试策略。

| Redis 形式 | 方法 | 返回与参数边界 | Redis 最低版本 |
| --- | --- | --- | --- |
| MGET key... | mget(byte[]...) | List<byte[]>，保持顺序/重复；不存在或非 String 元素为 null；空值为长度 0 数组 | 1.0 |
| MSET key value... | mset(byte[]...) | OK 字节数组；参数交替排列，至少一对 | 1.0.1 |
| MSETNX key value... | msetNx(byte[]...) | Boolean；任一 Key 存在则全部不写 | 1.0.1 |
| SET key value options | set(byte[], byte[], SetArgs) | OK/null/旧值字节；不能凭旧值判断是否写成功 | 基础 1.0；选项见下 |
| APPEND key value | append(byte[], byte[]) | 最终字节长度 Long；不存在则创建 | 2.0 |
| STRLEN key | strlen(byte[]) | 字节数 Long，不是字符数；不存在为 0 | 2.2 |
| GETRANGE key start end | getRange(byte[], long, long) | 闭区间、支持负索引；缺失/越界返回空数组 | 2.4 |
| SETRANGE key offset value | setRange(byte[], long, byte[]) | 最终字节长度 Long；空洞补零；负偏移本地拒绝 | 2.2 |

本批新增方法拒绝 null 参数、空批次、不完整键值对；空 byte[] 合法。
批量不采用 `Map<byte[], byte[]>`，避免数组引用相等语义被误当作内容相等。
未修改原 GET/SET/DEL 方法的签名或参数校验契约。

SET 复用已有不可变 SetArgs：NX/XX/EX/PX 从 2.6.12 开始；KEEPTTL 从 6.0 开始；
GET/EXAT/PXAT 从 6.2 开始；NX 与 GET 联用从 7.0 开始。服务端不支持时原样返回服务端错误，
不猜测版本、不移除选项降级、不自动重试。GET 返回旧值，即使 NX 未写入也可能返回非空旧值。
本次同时纠正 SetArgs.returnOldValue 的历史注释，不改变其参数生成或现有行为。

所有本批命令都是普通共享连接命令。单 Key 位于第一个参数；MGET 全部参数为 Key；
MSET/MSETNX 的 Key 位于交替键值对的奇数位置（以第一个参数为 1）。
已有 String Cluster Raw 路由验证全部 Key 同 Slot，不检查 value 的 Slot，不拆分跨 Slot 批次。
本批二进制 API 仍仅 Standalone，Cluster 的二进制路由尚未实现。

### 示例

```java
CompletionStage<List<byte[]>> values = client.binary().mget(key1, key2);
CompletionStage<byte[]> written = client.binary().mset(key1, value1, key2, value2);
CompletionStage<byte[]> conditional = client.binary().set(key1, value1, SetArgs.nx().ex(60));
```

仅示意调用形态；byte[] 参数由业务提供。Client 必须长期复用并按资源所有权关闭，
取消/超时不是服务端撤销，写入结果不确定时不能盲目重试。

## 验收与来源

2026-09-27 按命令开发 Skill 核对官方文档：
[MGET](https://redis.io/docs/latest/commands/mget/)、[MSET](https://redis.io/docs/latest/commands/mset/)、
[MSETNX](https://redis.io/docs/latest/commands/msetnx/)、[SET](https://redis.io/docs/latest/commands/set/)、
[APPEND](https://redis.io/docs/latest/commands/append/)、[STRLEN](https://redis.io/docs/latest/commands/strlen/)、
[GETRANGE](https://redis.io/docs/latest/commands/getrange/)、[SETRANGE](https://redis.io/docs/latest/commands/setrange/)。

- BinaryStringCommandsTest：字节级参数、SET 选项次序、整数偏移、RESP2/RESP3 空元素映射、
  参数拒绝、已有 String Cluster Key 元数据、MGET 取消后排空再交付下一响应。
- BinaryStringCompatibilityTest：四个真实服务端 × AUTO/RESP2；非 UTF-8 Key/值、零字节、空值、
  缺失/重复 Key、MSETNX 无部分写、范围/补零、WRONGTYPE 与后续连接可用、SET 版本门槛及条件返回。
- CMD-01 至 05、07、09、12 为本批重点；没有修改 decoder、专用连接、Pipeline/事务实现，
  既有相关测试通过全模块回归检查；不将本批声明为二进制 Pipeline/事务验收。

使用 UUID 二进制 Key，仅删除本次 Key，不清库。模拟测试检查 RESP3 空值形态，
真实 AUTO 测试覆盖 Redis 5 的 RESP2 回退及较新服务端 RESP3；显式 RESP2 在四服务端均执行。
无新增运行时依赖。公开 final 类仅增加方法，不移除/改签名；本次为源码级兼容复核，
不宣称 japicmp 或发布制品二进制兼容门禁已经完成。

### C5.1 实际结果（2026-09-27）

基线 `c82acdf` 加本批实现与测试；macOS x86_64 / Colima。先运行新增定向测试通过，
补充路由断言后使用最终源码执行根目录全模块回归：

```sh
sh scripts/run-compatibility-matrix.sh full /absolute/jdk8/home /absolute/jdk21/home
```

| JDK | Maven 全模块 clean test | 服务端 |
| --- | --- | --- |
| Oracle 8u202 | 112 tests，0 failures/errors/skipped | Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3；原有 Cluster/Sentinel 7.4.2 回归 |
| Oracle 21.0.7 | 112 tests，0 failures/errors/skipped | 同上 |

新增 5 个模拟/校验方法、2 个真实测试方法；真实方法各遍历四个服务端及两种协议选择。
源码快照与工作树 core/src 逐文件一致；未重试失败用例、未放宽断言或超时。
BinaryCommands 编译产物 major version 为 52（Java 8）。
报告保存在 `$TMPDIR/boba-straw-compatibility-GSRQN7/run-1` 和 `run-2` 下，
含 Maven 日志、环境、每模块 Surefire 报告与隔离 build。临时目录不是永久发布证据。

本批未重跑 JDK 11/17，JDK 25 与其他 OS 仍未验证；旧 C4 结果不代替本批验证。
Cluster/Sentinel 二进制、同步二进制、二进制 Pipeline/事务不在本批支持范围。
普通 facade 扩展未修改网络/编码热路径，本批未开展性能压测或长稳验收。

## 2026-09-28：C5.2/C5.3 高频范围与 C5.4/C5.5 基础落地

基线 `fc8ce41` 加本批改动。以下均为 Standalone typed 普通命令，不升级 Cluster/Sentinel typed 支持承诺。

| 分组 | 本批新增/补齐 | 结果契约 |
| --- | --- | --- |
| Binary Key/TTL | exists/existsCount、unlink、type、expire/pexpire、expireAt/pexpireAt、ttl/pttl、persist | Boolean、Long 或 String；TTL 的 -1/-2 原样保留 |
| Binary Counter/Bit | incr/incrBy/decr/decrBy、getBit/setBit、bitCount 全量/字节范围 | Long；溢出/非法位参数仍为服务端错误，不自动修正或重试 |
| Binary Hash | hget/hset/hmget/hgetall/hdel/hexists/hlen/hincrBy | byte[]、有序列表、字段值条目、Boolean、Long；空值与缺失分开 |
| Binary List | lpush/rpush/lpop/rpop/lrange/llen | byte[]/List/Long；只提供单元素 pop，不提供新版 count 选项 |
| Binary Set | sadd/srem/smembers/scard/sismember | List<byte[]>、Long、Boolean；服务器按字节内容去重，不用 Java Set<byte[]> |
| Binary ZSet | zadd/zrem/zrange/zscore/zcard/zrank | 基础分值/排名范围；缺失 score/rank 为 null，不变成 0 |
| String sync + async | bitCount 两种形式、hmget/hdel/hexists/hlen/hincrBy、lpop/rpop/llen、srem/scard/sismember、zrem/zscore/zcard/zrank | 与已有相邻接口一致；同步仍直接等待 transport completion |

Binary hgetall 返回 `List<Map.Entry<byte[], byte[]>>`，不是 Map<byte[], byte[]>。
条目保持本次服务端响应顺序，但 Hash 没有稳定排序承诺；比较字段内容请用 Arrays.equals。
Binary Set 也不提供 Java 数组内容相等语义。新增方法无需引入公共 DTO 或运行时依赖。
本批新增 public 方法不删除/改签名，新增模型保持 package-private；既有 facade 的全部 mapper 尚未迁移。

### 元数据与三层边界的实际路径

- CommandRegistry/CommandSpec：Key 规则、连接模式、已核实的读写属性和基础命令 since；
  少量历史 Raw 路由条目的 Access.UNKNOWN/since=null 明确表示尚未记录，不猜测默认值。
- CommandArgs：文本/字节参数视图，二进制 Slot 基础按原字节计算；未新增 Cluster binary 公开入口。
- CommandDecoder/CommandDecoders：新增 typed 方法消费；RESP2 Array/RESP3 Map、Set、Double、nullable number 映射复用。
- ClusterCommandRouting：移除自己的命令名单，消费注册表；EVAL numkeys、MSET 键值步长与显式 Key 保持同 Slot 策略。
- Standalone String/binary Raw、同步 transport facade、Pipeline、Sentinel Raw、事务 command：统一拒绝已知状态型/阻塞命令。
  内部握手、事务控制、Pub/Sub、BLPOP/BRPOP 使用已有专用路径，不经过普通入口。

未知普通命令保留 Raw 出口，未知副作用须由调用方核实；非完整安全沙箱。
XREAD/XREADGROUP 暂整体保守拒绝共享入口，尚未区分不带 BLOCK 的安全形式。
Raw/Pipeline 对已知受限命令改为发送前 IllegalArgumentException，事务 helper 同样收紧；这是有意的行为兼容变化。
不自动重试、不修改取消/FIFO、不根据版本自动删选项、不生成尚未实现的专用能力。

### 语义核实与测试

2026-09-28 官方语义参考：[BITCOUNT](https://redis.io/docs/latest/commands/bitcount/)、
[EXISTS](https://redis.io/docs/latest/commands/exists/)、[INCRBY](https://redis.io/docs/latest/commands/incrby/)、
[HGETALL](https://redis.io/docs/latest/commands/hgetall/)、[SMEMBERS](https://redis.io/docs/latest/commands/smembers/)、
[ZSCORE](https://redis.io/docs/latest/commands/zscore/)、[WAIT](https://redis.io/docs/latest/commands/wait/)。
另外在专用 Redis 7.4.2 上只读核对 COMMAND DOCS：Hash 基础命令 2.0；List/Set 基础 1.0；
ZADD/ZREM/ZRANGE/ZSCORE/ZCARD 1.2，ZRANK 2.0；毫秒过期/PTTL/BITCOUNT 2.6。
since 是基础命令版本，不含所有选项版本；本批 API 以 Redis 5+ 为兼容基线。
BITCOUNT 范围按字节解释，不引入 Redis 7 的 BIT/BYTE 选项；Hash/List/Set 可变参数均限 Redis 5 已支持的形式。

CommandModelTest：自动遍历注册表自一致性、Key 提取/跨 Slot、原始字节 Hash Tag、未知命令显式 Key、
Raw String/binary/Pipeline/事务拒绝且零命令写出、RESP2/RESP3 decoder 契约。
这不等于自动生成全部 Redis 语义测试；参数/版本仍需人工确认并执行真实环境验证。
HighFrequencyCommandsCompatibilityTest：三个方法各遍历四服务端 AUTO/RESP2，覆盖新增方法、
非 UTF-8/零字节字段值、缺失/空集合、重复成员、TTL 哨兵值、整数溢出、WRONGTYPE 及后续 PING。
全部使用 UUID Key 并定点清理；不存在 FLUSHDB/FLUSHALL。

原有 Cluster/Sentinel 测试用到共享 Raw WAIT；入口限制生效后，ReplicationTestFixture 将 SET+WAIT
移到同一独占连接，并仍断言副本确认数为 1。它仅是测试基础设施，不是给业务使用的 internal 绕过示例。
原有事务/阻塞/PubSub/超时取消测试继续参与根目录全模块回归。

剩余：Scan typed 页结果、复杂命令形式/选项元数据、旧 mapper 全量复用及 C6 拓扑专用组合。
冷门管理命令、完整 Stream/Geo/HLL typed 套件不作为本轮目标；C5 整体暂不标为完成。

### 本批实际验收（2026-09-28）

macOS x86_64 / Colima，基线 `fc8ce41` 加本批源码与测试；使用隔离执行器串行运行
根目录全模块 `mvn clean test`，启用 runCompatibility/runCluster/runSentinel 三个开关。

| JDK | 最终结果 | 实际服务端范围 |
| --- | --- | --- |
| Oracle 8u202 | 121 tests，0 failures/errors/skipped | Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3；Redis 7.4.2 Cluster/Sentinel |
| Oracle 21.0.7 | 121 tests，0 failures/errors/skipped | 同上 |

新增 6 个元数据/边界测试方法、3 个高频真实矩阵方法。源码快照与工作树 core/src 逐文件一致；
CommandSpec 编译字节码 major version 52。最终证据保存在
`$TMPDIR/boba-straw-compatibility-JoMlhR/run-1` 和 `run-2`，日志、源码与独立 Surefire 报告均保留。
临时目录不是永久发布证据；本批 JDK 11/17/25 与其他 OS 未运行。

过程记录：第一轮实现的 120 项测试在 JDK 8/21 均通过。补录元数据与注册表自一致性测试后，
JDK 21 出现既有 NioConnectionIoTest 公平性断言失败（121 项中 1 项失败），报告留在
`$TMPDIR/boba-straw-compatibility-ePrMOl`。检查发现测试等待原 Future 完成，却立即读取
whenComplete 写入的默认 true 标记，存在观察回调尚未结束的竞态。改为等待 whenComplete
返回的阶段，再读取原公平性断言；网络实现、超时、断言均未放宽。定向 I/O 测试及上述最终矩阵通过。
这是修正测试同步后重验，不是自动重跑隐藏失败；未启动独立 Agent，也不宣称跨模型审查已完成。

本批公共兼容复核为方法增量/原签名保留与明确的入口行为收紧，未运行 japicmp 发布制品门禁。
普通入口新增元数据校验，本批没有压测其开销或进行生产长稳，不能沿用旧网络基线宣称零性能影响。

## C5 typed 执行复用第一批（2026-09-28）

基线 `4531b6e` 加本批工作树。内部新增 TypedCommand<T>（普通 text 参数快照、decoder、
元数据准入）与 CommandExecutor（适配各拓扑既有执行和取消传播）。
BobaStrawAsyncCommands 普通 String typed 方法全部迁入该入口；EVAL 保持 RespValue，
BLPOP/BRPOP 保持 Standalone 专用路径。Cluster/Sentinel 新增 async() 并复用相同 facade，
没有增加自动重试、独立线程或另一套路由实现。

公共 API 为两个 Client 各增加 async()；原公开方法签名保留。Cluster 同 Slot 与无 Key 单主节点
语义不变；Cluster/Sentinel facade 上的阻塞方法发送前同步抛 UnsupportedOperationException。
本批不包含 binary/sync 执行统一、Cluster/Sentinel binary、Pipeline/事务 typed 结果与 Scan 页模型。

新增 TypedCommandExecutionTest 三项：参数快照、状态命令拒绝、取消传播、原异常保留且不重放、
映射异常可终止以及特殊入口不会调用普通 executor。ClusterIntegrationTest/SentinelIntegrationTest
各新增一项真实两协议 typed 用例，共享 TypedTopologyTestFixture 验证 String/Counter/TTL、
Hash/List/Set/ZSet、空值/错误和后续响应。既有 ASK 与 Sentinel 切换测试改用 typed GET/SET；
跨 Slot MGET/RENAME 发送前拒绝。没有新增 Redis 命令形式，语义来源沿用前一批官方记录。

执行 `sh scripts/run-compatibility-matrix.sh full`，传入 Oracle JDK 8u202 与 21.0.7：
根目录全模块 clean test 各 **126 tests，0 failures/errors/skipped**。
Standalone 四服务端 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3 RESP2/AUTO，
Cluster/Sentinel 为既有 Redis 7.4.2 专用测试容器。全部测试串行执行，UUID Key 定点清理。
证据：`$TMPDIR/boba-straw-compatibility-yL4xiH/run-1`、`run-2`；源码快照 core/src 与最终工作树一致，
TypedCommand 字节码 major 52。临时目录不是永久发布归档。

CMD-03/05/07/08/09/11/12 按本批范围核对；RESP wire decoder 未改，既有碎片/FIFO/专用连接测试回归。
兼容性为源码签名复核与 Java 8 编译验证，未执行 japicmp 制品比较、独立 Agent 审查或跨模型验证。
JDK 11/17/25、其他 OS、长稳及本批性能基线未运行；参数快照增加数组复制，不能宣称零分配/零开销。
按命令开发 Skill 保留专用入口和明确的未完成清单，C5 整体仍在进行中。

## C5 typed 批量结果第二批（2026-09-28）

基线 `d74d59c` 加本批工作树。Standalone Pipeline/事务新增 typed()，返回共享的
BobaStrawBatchCommands。初始 16 个 String 方法：GET、SET、DEL、EXISTS、INCR、TTL、MGET、
HGET、HSET、HGETALL、LPUSH、LRANGE、SADD、SMEMBERS、ZADD、ZSCORE。
返回 BobaStrawCommandHandle<T>，执行 executeTyped()/execTyped() 后用 BobaStrawBatchResult.get
取值。句柄绑定批次，复用 TypedCommand/decoder；不是 Future，不支持单条取消。

Raw/typed 可以混排，原 execute()/exec() 方法签名及行为保留。新 Pipeline 模式保留单条
服务端错误，读取对应句柄抛 BobaStrawServerException；网络/超时/容量失败仍整批异常。
事务结果区分 WATCH abort 与成功的空 EXEC；执行期单条错误不会伪装成回滚。
Pipeline 入队/执行快照现在在短同步区内协调，网络提交与结果交付不持该锁。
事务使用原专用池租约及 start/abort 路径，不经普通共享 executor，不创建额外线程。

语义来源（2026-09-28）：[Redis transactions](https://redis.io/docs/latest/develop/using-commands/transactions/)、
[Redis pipelining](https://redis.io/docs/latest/develop/using-commands/pipelining/)；命令参数形式未增加新版选项。
示例与详细错误语义见 [命令模型](../architecture/command-model.md)。

验证：

- TypedBatchResultTest：批次身份、空值、Simple/Blob Error、只读列表、WATCH abort 与回复数量校验。
- TypedBatchCompatibilityTest：16 方法、Raw/typed 混排、错误位置与后续成功结果、重复执行拒绝、
  空批次、WATCH 冲突、排队错误不发送 EXEC、原 Raw Pipeline 错误行为及下一事务租约可用。
- DedicatedConnectionLifecycleTest：增加 typed EXEC 取消/超时关闭专用 socket、下一租约重建，
  typed Pipeline 超时仍保留可能已执行异常而非转成服务端错误。
- BobaStrawProtocolNegotiationTest：增加 typed Pipeline 已发送取消、响应排空与后续命令匹配。

针对性测试先在 `/private/tmp/boba-straw-typed-batch-KuC7qo` 通过 37 项；随后补充两项 typed
超时测试，最终运行根目录全模块 `mvn clean test`，经 run-compatibility-matrix.sh full 隔离副本执行：
Oracle JDK 8u202 与 21.0.7 **各 134 tests、0 failures/errors/skipped**。
真实兼容包括 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3 的 RESP2/AUTO，以及原 Cluster/Sentinel 回归。
批量新 API 的真实用例只针对 Standalone，不表示支持拓扑批量组合。
最终 core/src 与验证快照一致，日志/报告保存在 `$TMPDIR/boba-straw-compatibility-fqIAzF/run-1`、`run-2`。

CMD-03/05/08/09/10/11/12 按本批边界核对；协议解码与 FIFO 未改，既有分片回归继续通过。
公开兼容复核为增量类型/方法与 Java 8 全量编译测试，未执行 japicmp、独立 Agent 或跨模型审查。
JDK 11/17/25、其他 OS、长稳及本批性能压测未运行，临时报告不是永久发布归档。
尚未覆盖：批量 binary、其余普通方法的批量 typed 包装、Cluster/Sentinel 批量专用组合、
Scan 页结果、binary/sync 执行统一；C5 仍未整体关闭。

## C5 Skill 同步与 Scan 第三批（2026-09-28）

基线 `5c8c4cc` 加本批工作树。先修正 command-development/usage/review Skill：
普通 Typed、特殊执行、普通 Raw 的选择；特殊能力可以有 typed 结果，Scan 不因此要求专用连接。
工作流加入模型复用与游标规则，验收补 CMD-13/14；使用审查补 BSU010/011。
清理 usage Skill 与审查卡里 Sentinel 一概“未实现”的旧描述，状态统一引用版本能力表。

Skill 校验：Ruby YAML frontmatter 与四个仓库 Skill 的全部 Markdown 引用路径检查通过。
skill-creator 的 quick_validate.py 已尝试，因本机缺 PyYAML 未完成；临时 venv 安装又被网络代理/TLS
失败阻断，没有修改系统 Python、关闭证书验证或伪造校验通过。未做独立 Agent/跨模型行为验收。

实现：BobaStrawScanCommands、ScanArgs、ScanPage<T>，三种 Client 新增 scan()。
Standalone/Sentinel 支持 SCAN/HSCAN/SSCAN/ZSCAN；Cluster 仅单 Key 后三者，数据库 SCAN 本地拒绝。
仅异步 String、MATCH/COUNT，基础命令元数据补 READ_ONLY/since=2.8.0；不改变重试/连接模式。
页保留 unsigned 64-bit 字符串游标、重复和空值字符串，空页非终止；Hash/ZSet 使用不可变条目列表。
不实现 TYPE/NOVALUES、binary Scan、同步 Scan、全库 iterator、Cluster 节点绑定或切换后的游标连续性。
语义来源与用法见 [命令模型](../architecture/command-model.md)，SCAN 官方总述覆盖 SSCAN 返回与参数语义；
本轮单独 SSCAN 网页读取失败，不把抓取成功作为实现证明。

测试：ScanCommandsTest 四项覆盖参数顺序/不可变选项、最大 unsigned 游标、空非终止页、重复、
本地拒绝、取消传播、畸形配对。ScanCompatibilityTest 一项遍历四服务端两协议，
逐页核对 32 个成员/字段/分值、缺失 Key、MATCH、WRONGTYPE 及后续请求；Cluster/Sentinel
各新增一项两协议扫描测试，使用 UUID Key 定点清理。测试上限仅为测试保护，不是客户端隐式遍历。

实际验证：

- Java 8 针对性 5 tests 全通过，目录 `/private/tmp/boba-straw-scan-1m830n`。
- 全模块矩阵 `$TMPDIR/boba-straw-compatibility-SfT9Hb`：JDK 21.0.7 的 141 tests 全通过；
  JDK 8u202 的 141 tests 中 1 error，为旧 BinaryStringCommandsTest 的
  preservesMissingEmptyDuplicateAndNonUtf8ResultsInBothProtocols 收到非法 RESP 标记 H，导致可能已执行异常。
  Scan 新测试全部通过。该轮 Java 8 不能记为全量通过。
- 在同一失败源码副本单独执行 BinaryStringCommandsTest，5 tests 通过，未复现；没有修改旧测试或放宽断言。
  现有日志不能确定非法字节来源，根因仍未定位，不把复核通过称为修复。
  原失败报告已由矩阵脚本保存在 run-1/boba-straw-core/surefire-reports，原 maven.log 保留。

真实兼容范围为 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3 RESP2/AUTO，及 Redis 7.4.2 Cluster/Sentinel；
未验证分页中途拓扑切换、JDK 11/17/25、其他 OS 或性能/长稳。公共 API 为增量方法/类，
Java 8 编译与源码兼容复核通过，未运行 japicmp。C5 暂不关闭；除剩余 binary/sync 统一外，
还需定位上述间歇性测试错误并恢复 Java 8 全量验收证据。
