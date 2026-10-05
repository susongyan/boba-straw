# Sentinel 主节点发现与连接生命周期

更新：2026-10-05；C6.1a 增加 binary 普通异步 facade 与注册脚本二进制执行。
现已增加 sync 普通 facade、String Pipeline、事务、BLPOP/BRPOP 和经典 Pub/Sub；验收见核心收尾计划。

## 接入

```java
try (BobaStrawSentinelClient client = BobaStrawSentinelClient.builder()
    .masterName("orders")
    .sentinel("sentinel-1", 26379)
    .sentinel("sentinel-2", 26379)
    .sentinel("sentinel-3", 26379)
    .sentinelCredentials(sentinelUser, sentinelPassword)
    .credentials(redisUser, redisPassword)
    .protocol(ProtocolVersion.AUTO) // 可以强制 RESP2
    .discoveryTimeout(Duration.ofMillis(500))
    .commandTimeout(Duration.ofSeconds(2))
    .topologyRefreshInterval(Duration.ofSeconds(1))
    .reconnectInterval(Duration.ofMillis(200))
    .reconnectMaxInterval(Duration.ofSeconds(5))
    .build()) {
    client.executeAsync("GET", "order:42").toCompletableFuture().get();
}
```

两套认证彼此独立，禁止将 Redis 凭据自动用作 Sentinel 凭据；没有认证时可省略对应配置。
每条物理连接分别握手。AUTO 使用 HELLO 3 协商，而非识别服务端版本；明确认证失败不触发 RESP2 回退。
URI、DB 选择暂未提供，不通过 Raw SELECT 改变共享连接状态。
外部 Resources 可以复用；先关闭 Client 再关闭其外部 Resources。

`client.binary()` 复用既有二进制命令目录，Key/value 不经过 String 转码；
例如 `client.binary().get(keyBytes)` 返回 `CompletionStage<byte[]>`。
与 String 共用同一已验证主节点连接和失效处理；切换时不重发在途请求，
未发现有效主节点时明确未发送。注册脚本也可使用 `scripts().executeBinary(...)`。
本批不提供 Raw binary 或 binary Scan/批量；普通 binary 入口不绕过命令元数据准入。
`sync()` 等待 transport 完成，不依赖用户 callback worker；BLPOP/BRPOP 仍使用专用连接。
`pipeline()` 在提交时绑定主节点，不重放失败批次；typed 返回保留单项服务端错误。

## 状态型能力的边界

`client.transaction()`、`client.async().blpop(...)` 和 `client.pubSub().subscribe(...)`
复用 Standalone 的租约、单次阻塞连接和订阅确认/退订机制。事务与 Pipeline 的 typed 脚本
使用 Sentinel 的注册表；批内按名称调用入队 EVAL，不恢复 NOSCRIPT 或重放部分批次。

没有专用调用时，不创建专用连接管理器或事务池。首次专用调用为当前已验证的主节点地址
惰性创建一个内部 Client，共享外部 Resources，但会多一条控制用共享连接；事务池仍到首次事务才创建。
专用连接分别握手/认证，目标绑定在 Sentinel 已验证的主节点代次；不会在每条专用连接重复 ROLE。
原有周期 ROLE 校验与切换失效是整体保护，不能消除角色检查之后切换的窗口，不承诺网络分区 fencing。

主节点失效、物理连接替换或地址切换时，退休这个管理器及全部旧租约、阻塞和订阅连接。
新请求必须等待发现有效主节点后由业务再次发起；不把在途操作迁移到新主节点。
事务取消/失败销毁、成功 EXEC/确认 UNWATCH 后归还；BLPOP/BRPOP 结束或取消即关闭，均有既有默认容量上限。
本版 Sentinel 未暴露独立事务池调优配置，不能套用 Standalone Builder 的所有参数。

保留 `pubSub()` facade 可以在切换后重新 subscribe；它每次选择当前代次，不缓存旧管理器。
订阅成功后观察 `subscription.termination()`，意外断连异常完成，由业务决定重订阅。
没有自动重订阅/消息补偿；主动 close 后正常完成只代表传输关闭，不保证退订 ACK 或业务回调排空。
取消 termination 观察不关闭订阅。详细契约与 Cluster 的[专用能力边界](cluster-topology.md#专用能力如何绑定拓扑)一致。

## 发现与切换

首次随机遍历配置的 Sentinel；成功使用的 Sentinel 优先用于下一次发现。配置去重，不自动增加未知节点。
每次发现查询 `SENTINEL get-master-addr-by-name`，临时查询连接结束即关闭。
Null、连接失败、认证失败或无效地址可继续检查其他配置的 Sentinel；全失败保留最后一次失败根因。
全体返回 Null 时错误明确表示 masterName 不存在，不将其混为网络故障。

取得地址后，在实际将要用于业务的连接上验证 `ROLE`。只有 master 才能交付应用；
相同地址且仍有效的物理连接直接复用并重新校验，不在每次周期发现时替换业务连接。
新地址验证通过后原子替换主节点，立即退休旧连接，不复制、迁移或重发在途业务请求。

断线后重新查询 Sentinel，再连接和验证角色，不盲目重连缓存的旧地址。
周期发现不依赖业务流量；READONLY、命令超时也使当前连接失效，触发重新发现。
发现单次合并，失败以 reconnectInterval 到 reconnectMaxInterval 的指数退避重试**发现**，
不是重试业务命令。新请求在没有有效主节点时明确以 `BobaStrawCommandNotSentException` 失败，不排队等待切换。

配置的 Sentinel 全部不可达但现有主连接仍有效时，继续使用现有连接并后台发现；
若 ROLE 指明现有节点不再是 master，则关闭它。此策略不提供网络分区时的写入 fencing，
ROLE 检查也不能消除检查之后发生主从切换的竞态。
ROLE 因本地准入容量不足被拒绝时，保留仍有效的主连接，不把业务繁忙当作网络故障。

发现定时器使用共享 EventLoop deadline，不另建调度线程；ROLE/SENTINEL 内部响应不执行用户回调。
公开命令结果由既有有界 callback dispatcher 交付。`refreshTopology()` 的调用视图可取消，
但不取消其他调用者共享的发现。关闭 Client 会终止查询、候选、主节点和发现 Future。

## 失败与兼容性

- 拓扑退休使用内部 `closeForTopologyChange()`：尚未写出和可能已写出的请求分别分类，不把主动切换误解为服务端撤销。
- 相同修复用于 Cluster 旧节点摘除，补足 C2 对在途失败分类的承诺；普通用户 close 的既有行为不变。
- HELLO/AUTH/CLIENT SETNAME 失败保留根因，未发送的应用请求不再只得到模糊的 Client closed。
- 保留已有 public 方法签名；新增 Sentinel 独立入口，不改变 Standalone Builder 行为。
- 共享 Raw 入口拒绝已知事务、订阅、阻塞和连接状态命令。XREAD/XREADGROUP 暂整体拒绝。
  未知模块命令仍由使用方确认其连接语义，Raw 不是任意命令安全执行保证。
- 不读取副本，不依赖订阅 `+switch-master` 才能恢复，不自动重放任何业务命令。

本地观测入口：`masterAddress()`、`connectionState()`、`successfulDiscoveries()`、
`failedDiscoveries()`。主节点地址为空代表尚无可交付主连接；READY 表示角色验证已完成且连接仍开放，
不是持续的服务端可用性保证。更完整的 Metrics、配置和 Starter 集成留在 C8。

## 测试环境与验证

```sh
sh scripts/sentinel-test-up.sh
mvn test -pl boba-straw-core -Dtest=SentinelLifecycleTest
mvn test -Dboba.straw.runSentinel=true
```

新建 `boba-straw-sentinel-test` 专用容器，所有权 label 为 `io.github.susongyan.boba-test=sentinel`。
Redis 7.4.2 一主一副本使用 17501–17502，三个 Sentinel 使用 27501–27503；只发布到本机 loopback。
`boba-test-data` 和 `boba-test-sentinel` 是公开的测试密码，不得用于生产。容器无持久数据卷。
测试运行前验证 label，使用 UUID Key，只清理本次数据，禁止 FLUSHALL；测试套件不可并发运行。
真实测试执行 Sentinel FAILOVER，主副本角色会交换；后续运行动态发现角色并等待副本同步。
测试不自动关闭容器，使用后可 `docker stop boba-straw-sentinel-test`；需要重建时先人工检查并移除该专用容器。

真实测试覆盖 RESP2/AUTO 的独立密码认证、错误密码和主从切换。当前未验证命名 ACL 用户、
Redis 5/6.2/Valkey Sentinel、多宿主分区或长稳压力，不能将本环境结果泛化。
详细结果见 [核心收尾计划](../implementation/core-completion-plan.md)。

协议依据（2026-10-05 再次核对，主节点验证与重发现机制未变）：
[Redis Sentinel client specification](https://redis.io/docs/latest/develop/reference/sentinel-clients/)。
