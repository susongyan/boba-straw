# 命令速查

适用版本：**`0.1.0-alpha.1`**。下表列出已发布客户端的公开 API；按 Redis 命令名或 Java 方法名搜索即可查找。
先看[入口与拓扑](#入口与拓扑)，再查对应数据结构。调用示例见[命令、批量与分页](commands.md)。

## 入口与拓扑

| 入口 | 返回方式 | Standalone | Sentinel | Cluster |
| --- | --- | --- | --- | --- |
| `sync()` | String 命令的同步结果 | 支持 | 支持 | 支持；多 Key 同 Slot |
| `async()` | String 命令的 `CompletionStage<T>` | 支持 | 支持 | 支持；多 Key 同 Slot |
| `binary()` | byte[] 命令的 `CompletionStage<T>` | 支持 | 支持 | 支持；按原始 Key 字节路由，多 Key 同 Slot |
| `pipeline().typed()` | 入队句柄，`executeTyped()` 后取结果 | String | String | String；整批同 Slot |
| `transaction().typed()` | 入队句柄，`execTyped()` 后取结果 | String | String | 使用 `transaction(routingKey)`；所有 Key 同 Slot |
| `scan()` | String 异步页结果 | 四种扫描 | 四种扫描 | HSCAN / SSCAN / ZSCAN |
| `pubSub()` | 异步订阅确认与关闭句柄 | 经典订阅 | 经典订阅 | 经典订阅 |
| `scripts()` | 按名称执行，返回 `CompletionStage<T>` | String / binary | String / binary | String / binary；多 Key 同 Slot |

普通命令直接调用 `sync()/async()/binary()`，无需开启 `typed()`。
binary 不提供同步 facade、Pipeline、事务或 Scan。下面的“typed 批量”同时适用于 Pipeline 和事务，
仅表示对应方法已经封装，不表示全部普通方法都有批量版本。

## 普通命令

下表所有方法均有 String 同步和异步入口；“binary”列表示是否同时提供 byte[] 异步入口。
`✓` 表示已封装，`—` 表示没有对应 typed 方法。多 Key 命令在 Cluster 中要求所有 Key 同 Slot。

### Key 与 TTL

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| DEL | `del` | ✓ | ✓ | 删除数量 |
| UNLINK | `unlink` | ✓ | — | 删除数量 |
| EXISTS | `exists` / `existsCount` | ✓ | `exists` | 单 Key 返回 Boolean，多 Key 返回计数 |
| TYPE | `type` | ✓ | — | 类型名称 String |
| EXPIRE / PEXPIRE | `expire` / `pexpire` | ✓ | — | Long：1 成功，0 未设置；无 NX/XX/GT/LT 选项 |
| EXPIREAT / PEXPIREAT | `expireAt` / `pexpireAt` | ✓ | — | 分别使用 Unix 秒 / 毫秒时间戳，返回 Long |
| TTL / PTTL | `ttl` / `pttl` | ✓ | `ttl` | Long，保留 -1 无过期 / -2 不存在 |
| PERSIST | `persist` | ✓ | — | Long：1 移除过期，0 未移除 |
| RENAME / RENAMENX | `rename` / `renameNx` | — | — | String / Boolean；两个 Key 同 Slot |
| TOUCH | `touch` | — | — | Long |
| KEYS | `keys` | — | — | `List<String>`；Cluster 仅查询一个主节点 |
| RANDOMKEY | `randomKey` | — | — | String 或 null；Cluster 仅查询一个主节点 |

大量 Key 的遍历优先使用下方 Scan 页接口；Cluster 没有跨节点的全库遍历入口。

### String

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| GET | `get` | ✓ | ✓ | String / byte[]；不存在为 null |
| SET | `set` | ✓ | 基础形式 | String / byte[]；普通接口支持 SetArgs，批量仅支持 key/value |
| MGET | `mget` | ✓ | ✓ | List，保持输入顺序；不存在或非 String 的元素为 null |
| MSET | `mset` | ✓ | — | String 输入 Map；binary 输入交替 key/value；返回 OK |
| MSETNX | `msetNx` | ✓ | — | Boolean；输入形式同 MSET |
| SETNX | `setNx` | — | — | Boolean；binary 可用 SET + SetArgs.nx() |
| SETEX / PSETEX | `setEx` / `psetEx` | — | — | String；binary 可用 SET + SetArgs.ex()/px() |
| GETSET | `getSet` | — | — | 旧值 String 或 null |
| APPEND | `append` | ✓ | — | 追加后的字节长度 Long |
| STRLEN | `strlen` | ✓ | — | 字节数 Long |
| GETRANGE | `getRange` | ✓ | — | String / byte[]；起止为字节闭区间，可为负索引 |
| SETRANGE | `setRange` | ✓ | — | 修改后的字节长度 Long；offset 非负 |

### 数值与 Bitmap

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| INCR / DECR | `incr` / `decr` | ✓ | `incr` | Long |
| INCRBY / DECRBY | `incrBy` / `decrBy` | ✓ | — | Long |
| INCRBYFLOAT | `incrByFloat` | — | — | Double |
| GETBIT | `getBit` | ✓ | — | Long：0 或 1 |
| SETBIT | `setBit` | ✓ | — | 修改前的位值 Long |
| BITCOUNT | `bitCount` | ✓ | — | Long；支持全量及 start/end 字节范围，无 BIT/BYTE 单位选项 |

### Hash

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| HGET | `hget` | ✓ | ✓ | String / byte[]；字段不存在为 null |
| HSET | `hset` | ✓ | ✓ | Long；一次设置一个 field/value，无 Map 批量重载 |
| HMGET | `hmget` | ✓ | — | List，保持字段顺序；缺失为 null |
| HGETALL | `hgetall` | ✓ | ✓ | String 返回 Map；binary 返回 `List<Map.Entry<byte[], byte[]>>` |
| HDEL | `hdel` | ✓ | — | 删除字段数量 Long |
| HEXISTS | `hexists` | ✓ | — | Boolean |
| HLEN | `hlen` | ✓ | — | 字段数量 Long |
| HINCRBY | `hincrBy` | ✓ | — | Long |

binary 字段和值保持原始字节，比较内容时使用 Arrays.equals；Hash 不保证稳定遍历顺序。

### List

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| LPUSH / RPUSH | `lpush` / `rpush` | ✓ | `lpush` | Long；支持一次传入多个元素 |
| LPOP / RPOP | `lpop` / `rpop` | ✓ | — | String / byte[] 或 null；仅单元素，无 count 选项 |
| LRANGE | `lrange` | ✓ | ✓ | List；按 start/stop 范围读取 |
| LLEN | `llen` | ✓ | — | Long |

BLPOP / BRPOP 见[阻塞与订阅](#阻塞与订阅)，它们使用专用连接。

### Set

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| SADD / SREM | `sadd` / `srem` | ✓ | `sadd` | Long；支持多个成员 |
| SMEMBERS | `smembers` | ✓ | ✓ | String 返回 `Set<String>`；binary 返回 `List<byte[]>` |
| SCARD | `scard` | ✓ | — | 成员数 Long |
| SISMEMBER | `sismember` | ✓ | — | Boolean |

binary 使用 List 保留字节成员，不以 Java 数组的引用相等判断成员内容相等。

### ZSet

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| ZADD | `zadd` | ✓ | ✓ | Long；一次添加一个 score/member，无 NX/XX/CH/INCR 选项 |
| ZREM | `zrem` | ✓ | — | Long；支持多个成员 |
| ZRANGE | `zrange` | ✓ | — | List；仅按排名的 start/stop，无 WITHSCORES/BYSCORE/BYLEX/REV 选项 |
| ZSCORE | `zscore` | ✓ | ✓ | Double；成员不存在为 null |
| ZCARD | `zcard` | ✓ | — | Long |
| ZRANK | `zrank` | ✓ | — | Long；成员不存在为 null |

### 连接

| Redis 命令 | Java 方法 | binary | typed 批量 | 返回与限制 |
| --- | --- | :---: | :---: | --- |
| PING | `ping` | — | — | String；无 message 参数重载 |

## SET 选项

普通 String 和 binary 的 `set(key, value, SetArgs)` 支持以下选项：

| Redis 选项 | SetArgs 方法 | 服务端要求 |
| --- | --- | --- |
| NX / XX | `nx()` / `xx()`，或 `onlyIfAbsent()` / `onlyIfPresent()` | Redis 5 兼容基线内 |
| EX / PX | `ex(seconds)` / `px(milliseconds)` | Redis 5 兼容基线内 |
| KEEPTTL | `keepTtl()` | Redis 6.0+ |
| GET | `returnOldValue()` | Redis 6.2+；NX 与 GET 联用需 Redis 7.0+ |
| EXAT / PXAT | `exAt(unixSeconds)` / `pxAt(unixMilliseconds)` | Redis 6.2+ |

选项对象不可变，可链式组合；KEEPTTL 与显式过期不能同时配置。
不带 GET 时返回 OK 或条件未满足的 null；带 GET 时返回旧值，不可根据旧值判断是否写入成功。
客户端不按服务端版本移除选项；不支持的选项由服务端返回错误。

## 游标扫描

均从 `client.scan()` 调用，返回 `CompletionStage<ScanPage<T>>`。

| Redis 命令 | Java 方法 | 页元素类型 | 拓扑 |
| --- | --- | --- | --- |
| SCAN | `scan` | String Key | Standalone / Sentinel |
| HSCAN | `hscan` | Map.Entry<String, String> | 三种拓扑 |
| SSCAN | `sscan` | String 成员 | 三种拓扑 |
| ZSCAN | `zscan` | Map.Entry<String, Double> | 三种拓扑 |

通过 `ScanArgs.none().match(...).count(...)` 配置 MATCH / COUNT，无 TYPE/NOVALUES、binary 扫描或同步扫描。
每次只获取一页；空页不代表结束，COUNT 是提示值，允许重复，不提供一致性快照或切换后的游标连续性。
见[分页用法](commands.md#scan-的边界)。

## Lua

| 能力 | 公开入口 | 覆盖与语义 |
| --- | --- | --- |
| EVAL | `sync().eval` / `async().eval` / `binary().eval` | 三种拓扑；返回 RespValue 或使用 ScriptOutput<T> |
| EVALSHA | `sync().evalSha` / `async().evalSha` / `binary().evalSha` | 三种拓扑；直接入口不恢复 NOSCRIPT |
| SCRIPT LOAD | `sync().scriptLoad` / `async().scriptLoad` / `binary().scriptLoad` | 返回 SHA String；Cluster 只加载到一个主节点 |
| 定向预加载 | `cluster.scriptLoadForKey(routingKey, script)` | String 异步；加载到对应 Slot 的主节点 |
| 本地注册与按名称执行 | `scripts().register` / `execute` / `executeBinary` | 三种拓扑；首次 EVAL，成功提示后 EVALSHA，明确 NOSCRIPT 最多补一次 EVAL |
| typed Pipeline / 事务 | `typed().eval` / `evalSha` / `scriptLoad` / `script` | 三种拓扑 String；返回句柄，按名称入队始终 EVAL，不事后补发 |

ScriptOutput 支持 `raw()`、`integer()`、`string()`、`bytes()`、`list(...)`。
Cluster 脚本必须声明全部 Key 且同 Slot；缓存淘汰和节点切换的处理见[Lua 使用指南](lua.md)。

## 阻塞与订阅

| Redis 命令 | 入口 | 当前覆盖 |
| --- | --- | --- |
| BLPOP / BRPOP | `sync().blpop/brpop` 或 `async().blpop/brpop` | 三种拓扑 String；参数为 timeoutSeconds 与多个 Key；返回 [key, value]，服务端等待超时为空列表 |
| SUBSCRIBE / PSUBSCRIBE | `pubSub().subscribe` / `psubscribe` | 三种拓扑 String；每次一个 channel / pattern，listener 接收消息正文 |
| UNSUBSCRIBE / PUNSUBSCRIBE | 订阅句柄的 `close()` | 发起退订与专用连接释放，不是独立普通命令方法 |

客户端 commandTimeout 对阻塞命令仍生效；Cluster 多 Key 阻塞要求同 Slot。
订阅不自动重订阅，不保证切换期间消息无损。更多阻塞命令、binary 阻塞/订阅及 sharded Pub/Sub 尚未封装。
详见[生命周期与取消](lifecycle.md)。

## 未封装命令与限制

Stream、Geo、HyperLogLog、Set/ZSet 的集合运算、更多 Server/ACL 命令和新版选项没有专用 typed API。
需要时先核对服务端语义，再使用公开 Raw 出口；Raw 返回 RespValue，不等于已封装支持：

- 普通 String：三种 Client 的 `executeAsync(command, arguments...)`。
- Standalone 二进制 Raw：`executeBinaryAsync(commandBytes, arguments...)`。
- Cluster 未知普通命令：`executeWithKeysAsync(keys, command, arguments...)`，明确声明全部 Key。

Raw、Pipeline 和事务普通入队都会拒绝已知的阻塞、订阅及连接状态命令，不能用于手工发送
MULTI/WATCH/SUBSCRIBE/SELECT 等控制指令。XREAD/XREADGROUP 当前整体拒绝共享入口，
包括未带 BLOCK 的形式。发布、管理或模块命令也需逐项核对路由与连接状态影响。
不隐式拆分跨 Slot 命令，不自动重放不确定的业务请求；错误处理见[失败与重试](failures-and-retries.md)。

## 维护依据

此表依据已发布 Core 的公开方法，与源码 `BobaStrawSyncCommands`、`BobaStrawAsyncCommands`、
`BobaStrawBinaryCommands`、`BobaStrawBatchCommands` 及各特殊能力入口核对。
新增 API 时更新本表；方案变更与测试过程记录在[命令开发历史](../implementation/command-development-history.md)
或相应实施文档中。测试范围与环境见[版本能力表](supported-features.md)。
