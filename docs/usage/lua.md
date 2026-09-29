# Lua 脚本使用指南

当前工作树提供 EVAL、SCRIPT LOAD、EVALSHA 的直接方法与 ScriptOutput 显式结果类型。
L2 增加 Client-owned `scripts()` 注册执行器；支持三种拓扑的 String 异步调用，
Standalone 另支持 `executeBinary`。完整机制见[设计稿](../architecture/lua-scripting.md)，阶段与验证分别见[实施进度](../implementation/lua-scripting-progress.md)和[测试记录](../testing/lua-scripting-validation.md)。

## 推荐：注册一次，按名称执行

```java
// 应用初始化时注册，本地操作，不连接 Redis、不执行业务。
client.scripts().register("increment-v1",
    "return redis.call('INCRBY', KEYS[1], ARGV[1])", ScriptOutput.integer());

// 业务代码不需要保存句柄或管理 SHA。
CompletionStage<Long> result = client.scripts().execute(
    "increment-v1", ScriptOutput.integer(), new String[] {"{order}:counter"}, "1");
```

首次在实际连接上用 EVAL；有成功提示后用 EVALSHA，明确 NOSCRIPT 最多补一次 EVAL。
不自动 SCRIPT LOAD。超时、断连、权限错误、脚本运行错误与结果解码错误均不重放。
注册脚本不得伪造或转发 NOSCRIPT 为顶层业务错误：客户端无法证明这种错误发生前没有写入；
不满足这个契约时使用下方直接命令，不启用注册执行器。

同名、相同正文原始字节、相同 output 的重复注册幂等；任一不同则拒绝，更新请用新版本名称。
execute 的 output 必须与注册时结构相同，例如 list(integer) 不等于 list(string)。
不要每次请求生成不同正文，将业务变量放入 KEYS/ARGV。

关闭 Client 清理注册信息并取消在途操作；取消不保证 Redis 未执行。

Standalone 二进制调用（String 或 byte[] 注册的正文均保存精确字节）：

```java
client.scripts().register("echo", "return ARGV[1]", ScriptOutput.bytes());
CompletionStage<byte[]> echoed = client.scripts().executeBinary(
    "echo", ScriptOutput.bytes(), new byte[0][], new byte[] {(byte) 0xff, 0});
```

Cluster/Sentinel 的 executeBinary 当前明确拒绝，拓扑 binary 仍留 C6。
注册执行器只提供 CompletionStage；不新增同步 facade、批量接口或自动预热。
显式直接 SCRIPT LOAD 不更新注册执行器提示，故随后首次按名称执行仍保守使用 EVAL。
Cluster 无 Key 只选一个主节点；跨 Slot 在脚本发送前失败，ASK 使用独占临时连接。
一次执行的取消状态与总 commandTimeout 跨 NOSCRIPT/MOVED/ASK 共享，不给每个子请求重置时限。

## 容量默认值与配置约定

以下限制作用于每个 Client 的 `scripts()` 注册执行器，不是进程全局限制，也不是 Redis 服务端的脚本缓存配置。
Cluster 的注册执行器在各节点间共用这份额度，不按节点数量倍增。
通过 `BobaStrawScriptOptions` 自定义额度；不配置时使用下表默认值。

| 配置项 | 默认值 | 统计范围与达到上限后的行为 |
| --- | --- | --- |
| `maxRegisteredScripts` | 1,024 | 本地注册名称对应的定义数量。继续注册新定义时抛出 `BobaStrawBackpressureException`，不淘汰已有定义。 |
| `maxScriptBytes` | 16 MiB（16,777,216 字节） | 所有注册定义的正文总字节数，不是单脚本上限。String 正文按 UTF-8 编码计数，byte[] 按原始长度计数；新增正文将超过额度时拒绝注册。 |
| `maxCacheHints` | 4,096 | 物理连接与 SHA 组合的成功提示数量。超过时按最近使用顺序淘汰旧提示，不删除脚本定义；无提示的下一次执行使用 EVAL。 |
| `maxInFlightExecutions` | 4,096 | 尚未结束的逻辑脚本执行数量；同一次执行中的 NOSCRIPT 恢复和重定向不另占逻辑额度。满额时抛出 `BobaStrawBackpressureException` 拒绝新执行，已有执行继续。 |

同名、同正文、同 output 的幂等注册不重复占额度；相同正文注册为不同名称则分别计数、计字节。
名称必须非空且 `String.length()` 不超过 256（UTF-16 code unit）；这是固定输入约束，不计划开放为容量选项。

Standalone、Sentinel、Cluster 的 Builder 均接收不可变的
`BobaStrawScriptOptions`，未指定时保持上表默认值；四项容量均必须为正数。
配置在 Client 创建时确定，不支持运行中缩容或静默移除定义。

```java
BobaStrawClient client = BobaStrawClient.builder()
    .scriptOptions(BobaStrawScriptOptions.builder()
        .maxRegisteredScripts(256)
        .maxScriptBytes(8L * 1024L * 1024L)
        .maxCacheHints(2048)
        .maxInFlightExecutions(1024)
        .build())
    .build();
```

Cluster 和 Sentinel 使用相同的 `scriptOptions(...)` 方法；配置对象可安全地在多个 Client 间复用，
但各 Client 的额度分别统计。

这些限制不是总内存上限，也不替代连接队列和 callback 的准入限制：即使脚本额度未满，执行仍可能因其他容量限制而被拒绝。
脚本正文总量也不包含参数、编码帧与在途结果的内存。通常沿用默认值即可；需要调整时，按注册定义数量、正文总量、
活跃连接/SHA 组合数量和实际并发量分别评估，而不是将四项一起放大。

## 执行与显式预热

以下片段假定 `client` 是已创建且长期复用的 Standalone BobaStrawClient：

```java
String script = "return redis.call('INCRBY', KEYS[1], ARGV[1])";
String[] keys = {"{order}:counter"};

// 直接执行一次，不需要 typed()。
Long value = client.sync().eval(script, ScriptOutput.integer(), keys, "1");

// 显式加载只返回 SHA，不执行业务；调用方仍需处理后续 NOSCRIPT。
String sha = client.sync().scriptLoad(script);
CompletionStage<Long> next = client.async().evalSha(
    sha, ScriptOutput.integer(), keys, "1"
);
```

任意脚本返回可以沿用 `eval(script, keys, args)` / `evalSha(sha, keys, args)` 的 RespValue。
ScriptOutput 提供 raw/integer/string/bytes/list；list 支持嵌套，类型不符时解码失败，
不能据此重试（脚本可能已修改数据）。Null、空字符串、空数组分别保留。
bytes 返回副本；raw 保留 RESP 原始结构，不承诺 payload 深度不可变。

二进制只提供 Standalone 异步入口：

```java
byte[] payload = {(byte) 0xff, 0};
CompletionStage<byte[]> echoed = client.binary().eval(
    "return ARGV[1]".getBytes(java.nio.charset.StandardCharsets.UTF_8),
    ScriptOutput.bytes(), new byte[0][], payload
);
```

`binary().scriptLoad(byte[])` 返回 ASCII SHA 字符串，`binary().evalSha(...)` 的 Key/参数保持 byte[]。
不要用 String 保存任意二进制值，也不要在方法执行期间从其他线程修改输入数组。

## 拓扑与失败边界

- Cluster/Sentinel 的 `async()` 提供上述 String 方法；没有同步或 binary facade。
- 声明全部 Key，Cluster 多 Key 必须同 Slot；脚本不得在 ARGV 中藏 Key 或动态生成未声明 Key。
- Cluster `async().scriptLoad(script)` 仅加载到一个选定主节点，不广播。
  `cluster.scriptLoadForKey(routingKey, script)` 加载到当前 Slot 主节点；routingKey 不发送给 Redis。
- 缓存归属于具体服务器，切换、迁移或清理后仍可能 NOSCRIPT；直接 evalSha 不自动补载或回退。
- 直接命令只走现有普通执行内核；Cluster 仍保留有界 MOVED/ASK，不自动重放网络失败。
- 取消/超时不是服务端撤销；脚本原子执行不等于运行错误时回滚。
- SCRIPT DEBUG 会改变连接行为，普通 Raw、Pipeline、事务 command 入口本地拒绝。
- typed Pipeline/事务脚本方法留在 L3；不要自行循环普通 async 调用来冒充批量执行。

只读脚本也走主节点，不引入读写分离。验证记录见[Lua 测试记录](../testing/lua-scripting-validation.md)。
