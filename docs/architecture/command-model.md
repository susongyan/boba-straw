# 命令模型与 API 分层

设计起始日期：2026-09-28。目标是支持主要数据结构的高频 API，并让新增命令复用统一模型。
本文说明设计与执行边界；已发布版本的具体命令和选项见[命令速查](../usage/command-reference.md)，
测试环境与验收范围见[版本能力表](../usage/supported-features.md)。

## 三层边界

| 层 | 面向用户的能力 | 边界 |
| --- | --- | --- |
| 高频 Typed API | Key/TTL/Counter/Bit、String、Hash、List、Set、ZSet | 同步 String、异步 String、异步 binary；返回 Long/Boolean 等语义类型，binary 不等于所有结果都用 byte[] |
| 特殊能力 API | Transaction、Blocking、Pipeline、Pub/Sub、Scan | 事务/阻塞/订阅管理专用连接；Pipeline 批量保序不原子；Scan 逐页游标不承诺一致性快照 |
| Raw 普通命令出口 | 新版、低频、未包装普通命令 | 返回 RespValue；仍受连接安全、准入、错误/取消与路由约束，不是绕过安全规则的后门 |

未知普通 Raw 命令在 Standalone/Sentinel 保留出口；调用方负责核对其不会改变连接状态、阻塞或切换协议。
客户端无法在离线注册表中识别未来/模块命令的全部副作用；未知不意味着自动证明安全。
Cluster 未知命令必须显式声明全部 Key；已知命令不能通过显式声明绕过同 Slot 检查。
任何形式都不根据 read-only、幂等等元数据自动重试，超时/取消不代表服务端撤销。

## 命令元数据

内部模型不作为用户自定义插件 SPI 发布，先保持包内可演进：

- `CommandSpec`：命令名、Key 提取规则、连接模式、读写属性；版本信息仅在已核实处记录。
- `CommandArgs`：String/binary 参数访问，命令名与控制参数使用 ASCII；二进制 Key 不经 UTF-8 往返。
- `CommandDecoder<T>`：RESP2/RESP3 到结果模型，沿用统一 RespValue；服务端错误由执行内核处理。
- `TypedCommand<T>`：不可变普通 String/binary 调用，绑定命令名、参数快照与 decoder；复用注册表准入规则。
- `CommandExecutor`：拓扑无关的异步普通执行入口；结果通过 BobaStrawStages.map 映射并传播取消，
  不自行建连接、排队或重试。Standalone/Cluster/Sentinel 由各自既有 executeAsync 适配。
- `BinaryCommandExecutor`：原始字节执行适配，仅编码 ASCII 命令名；当前接入三种拓扑，
  Key 保留原始字节并在 Cluster 校验同 Slot。同步 facade 消费同一 TypedCommand，但仍直接等待 transport。

- `CommandRegistry`：单一元数据表和共享入口策略，Cluster 路由、Standalone/Sentinel Raw、Pipeline/事务校验消费它。
- `ClusterCommandRouting`：消费 Key 规则计算同 Slot，保留拓扑和重定向的原有职责。

binary 调用构建时额外保存不可变 Slot/路由错误摘要，不保留或解码整份原始参数。
跨 Slot 错误仅由 Cluster 适配器拒绝，Standalone/Sentinel 保留多 Key 能力。
Cluster 仍复用有界 MOVED/ASK；同步普通命令使用 transport completion，ASKING 也不依赖 callback worker。
三拓扑 String Pipeline/事务共用批量 typed 目录；事务、BLPOP/BRPOP、经典 Pub/Sub
保持专用连接，不穿过普通 CommandExecutor。Cluster 批量同 Slot，不事后恢复 MOVED/ASK；
专用操作绑定当前主节点，拓扑退休不重放。

元数据按“命令形式”逐步完善，不能仅靠命令名猜测可变 Key、BLOCK/STORE 等选项。
暂未实现选项感知元数据的 XREAD/XREADGROUP 保守拒绝普通入口；复杂未知命令走显式 Key Raw。
版本是语义文档而非自动探测或降级开关；HELLO 3/AUTO 回退策略不变。
注册表不承担解析响应、排队、建连接或调度，不创建另一套执行引擎。

```mermaid
graph TD
    App[应用调用] --> Typed[高频 Typed API]
    App --> Special[特殊能力 API]
    App --> Raw[普通 Raw API]
    Typed --> Meta[命令元数据和参数]
    Raw --> Meta
    Special --> Policy[连接模式校验与专用生命周期]
    Meta --> Policy
    Meta --> Keys[全部 Key 提取与同 Slot 校验]
    Keys --> Topology[既有拓扑路由]
    Policy --> Core[既有异步执行内核]
    Topology --> Core
    Core --> Resp[RespValue]
    Resp --> Decoder[类型化结果映射]
    Resp --> RawResult[Raw 结果]
```

图沿用 Mermaid 11.16.0 基线。元数据复用不能改变 FIFO、Push/Attribute、callback 隔离或失败分类。
同步 facade 继续等待 transport completion，不能改成等待 callback worker，以免重新引入饥饿。

## 高频范围与退出标准

冻结第一轮范围，不随 Redis 官网命令数增长：

| 分组 | 第一轮重点 |
| --- | --- |
| Key/TTL | EXISTS、DEL、UNLINK、TYPE、EXPIRE/PEXPIRE、EXPIREAT/PEXPIREAT、TTL/PTTL、PERSIST |
| String/Counter/Bit | 已有 GET/SET/MGET/MSET/MSETNX 与范围操作；INCR/INCRBY/DECR/DECRBY、GETBIT/SETBIT/BITCOUNT |
| Hash | HGET/HSET/HMGET/HGETALL/HDEL/HEXISTS/HLEN/HINCRBY |
| List | LPUSH/RPUSH/LPOP/RPOP/LRANGE/LLEN；BLPOP/BRPOP 继续专用连接 |
| Set | SADD/SREM/SMEMBERS/SCARD/SISMEMBER；集合间运算暂留 Raw 同 Slot 策略 |
| ZSet | ZADD/ZREM/ZRANGE/ZSCORE/ZCARD/ZRANK；复杂聚合/新版选项暂留 Raw |
| Scan | String 异步逐页 SCAN/HSCAN/SSCAN/ZSCAN，不隐式遍历全库；Cluster 仅单 Key 扫描 |

Binary Hash 不使用 `Map<byte[], byte[]>` 冒充按内容相等的 Map；字段结果采用有序键值条目。
Binary Set 同理保留字节列表，不宣称 Java 数组按内容去重。ZSet 分值使用可空 Double，排名可空 Long。
所有新增 typed 接口都要验证 key/value 非 UTF-8、Null/空聚合、错误及版本边界。

C5 退出须同时满足：上述高频范围与明确例外有覆盖表；元数据在真实执行路径被消费；
三层入口有机械测试；扩展指南与用法一致；Java 8 与真实 RESP2/AUTO 矩阵通过。
不是注册了命令名就算 typed 已实现，也不是生成了测试壳就算语义验收。
冷门管理命令、模块命令、全选项、Stream/Geo/HLL 的完整 typed 套件不作为 C5 阻塞项。

## 实现与查询入口

已发布版本的命令、选项、binary 与批量覆盖见[命令速查](../usage/command-reference.md)。
分批执行、基线和测试过程保存在[命令模型实施历史](../implementation/command-model-progress.md)
及[命令开发历史](../implementation/command-development-history.md)；其中的阶段性缺口不代表当前版本缺口。
