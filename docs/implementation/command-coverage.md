# C5 命令与二进制接口覆盖

本清单以源码公开方法为准，Raw 能发送不等于已提供类型化 API。当前范围是核心 Redis 命令，
不承诺覆盖未来版本新增命令或 Redis 模块命令。每批实现和验收分别记录，不使用“完整命令”笼统结论。

## 基线缺口与执行分组

基线 `c82acdf`：String 同步/异步有常用 Key/String 命令、Hash 三个、List 三个普通命令和
BLPOP/BRPOP、Set 两个、ZSet 两个及 EVAL。二进制类型化仅异步 GET/SET/DEL。
Cluster/Sentinel 仅 String Raw 普通命令，不存在可直接复用的二进制 facade。

| 批次 | 范围 | 当前状态 |
| --- | --- | --- |
| C5.1 | Standalone 二进制 String 批量、SET 选项、字节范围 | 本批实现，验证记录见下 |
| C5.2 | Key/TTL、String 数值和位操作的二进制接口 | 待补齐 |
| C5.3 | Hash/List/Set/ZSet 的缺失普通命令与二进制接口 | 待按结构分批实现；现有少量方法不代表整组完成 |
| C5.4 | Scan 结果模型、Stream、Geo、HyperLogLog、Lua 扩展 | 待设计与实现，不将 Raw 返回 RespValue 视作类型化支持 |
| C5.5 | 更多阻塞命令与二进制专用连接接口 | 待实现，必须复用专用连接生命周期，不走共享 Raw |
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
