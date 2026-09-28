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
