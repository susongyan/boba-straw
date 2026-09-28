# Cluster 普通命令连接与拓扑

更新：2026-09-22。属于核心收尾 C2；不表示 Cluster 的事务、Pipeline、Pub/Sub 和阻塞命令已完成。

## 对外入口

```java
BobaStrawClusterClient cluster = BobaStrawClusterClient.builder()
    .seeds("redis-1:6379", "redis-2:6379", "redis-3:6379")
    .protocol(ProtocolVersion.AUTO) // 也可以明确指定 RESP2
    .topologyRefreshInterval(Duration.ofSeconds(30))
    .reconnectInterval(Duration.ofSeconds(1))
    .reconnectMaxInterval(Duration.ofSeconds(30))
    .maxRedirectConnections(32)
    .build();

cluster.executeAsync("MGET", "{account}:name", "{account}:status");
// 未内置 Key 元数据的普通命令：必须声明全部 Key，不能借此绕过已知命令的校验。
cluster.executeWithKeysAsync(new String[] {"{account}:name"}, "GETDEL", "{account}:name");
// 结束时 close；应用长期复用 client，不要每次请求创建。
```

上例 GETDEL 需要支持该命令的服务端；这里只展示 Raw 路由，不表示已经提供类型化或二进制接口。
无 Key 命令选择一个可用主节点（没有 READY 节点时提交到候选主节点并保留失败），
不是全节点广播；SCAN、KEYS、DBSIZE 等也只是单节点结果。当前只读写主节点，不读取副本。

## 节点与刷新

- 启动在调用线程随机遍历 seed，读取 `CLUSTER SLOTS`；所有 seed 失败则关闭已建节点。
- 每个已知节点使用一个 Standalone 内核，共享 Resources 的 EventLoop 和 callback workers，
  复用已有有界指数退避重连；每条新物理连接独立认证和协议协商。
- 周期刷新默认 30 秒，无业务流量也会运行。网络失败、MOVED/ASK、CLUSTERDOWN、READONLY、
  TRYAGAIN 触发 250ms 去抖刷新；同一时间只运行一次发现，期间的新事件合并为后续刷新。
- 候选包括当前 Slot 主节点和配置的 seed，去重后随机遍历。失败不会清空旧快照。
- 新快照必须覆盖 16384 Slot，范围不可重叠、越界或缺失，地址合法后才原子替换。
  地址为 Null/空字符串时使用提供快照的节点 host。暂不接受部分覆盖的降级拓扑。
- MOVED 更新版本号；刷新响应若基于更老版本开始，则不覆盖更新，并安排后续发现。
- 不再属于快照的非 seed 节点会关闭并停止重连；seed 保留用于发现，但不再作为旧 Slot owner。
  摘除立即关闭，不等待在途命令 drain；在途失败保留“可能已执行”，不迁移到新节点重放。
- close 取消刷新任务、关闭节点及 ASK 连接；只有自建 Resources 才随 Client 关闭。

`refreshTopology()` 可主动触发刷新，取消调用方 Future 不取消共享刷新。
`nodeMetrics()` 返回无网络 I/O 的节点快照，包含 discovery seeds；`topologyVersion()` 与
刷新成功/失败计数用于观察发现状态。成功计数表示发现完成，不代表每条节点连接 READY。
发现应答处理使用既有 callback workers；应用不得长期占用这些 worker，否则发现推进也可能延迟。

## 重定向与失败边界

| 结果 | 处理 |
| --- | --- |
| MOVED | 仅明确服务端拒绝、Slot 匹配时更新该 Slot；最多跟随一次 |
| ASK | 新建有界单次专用连接；ASKING 成功后才发送目标命令；结束/取消/失败关闭；不修改永久 Slot owner |
| 第二次重定向、CLUSTERDOWN、TRYAGAIN、READONLY | 原样暴露失败，必要时触发发现，不自动重发业务命令 |
| 超时、连接中断、未知执行结果 | 暴露原执行语义，只恢复连接/发现拓扑，不重放 |
| 已知多 Key 跨 Slot | 发送前拒绝，不隐式拆分 |
| 未知普通命令 | executeAsync 拒绝；executeWithKeysAsync 要求调用者准确提供全部 Key |

顶层 RESP Error/Blob Error 映射为 `BobaStrawServerException`，它继承既有
`BobaStrawConnectionException`；仅该明确服务端错误允许解释 MOVED/ASK。
嵌套的 EXEC 错误仍保留在返回结构中。取消不是撤销已发送命令，ASK 的目标命令也一样。

commandTimeout 作用于每个物理请求；初次命令、ASKING 和目标命令各自计时，不是整个
跨节点调用共享的总 deadline。应用可以取消最外层 Future，但不能由此推断未执行。
ASK 默认最多同时 32 条连接，满时以 backpressure 拒绝；不等待、不建立无界池。

## Key 元数据与兼容性

元数据在 `ClusterCommandRouting`：单 Key、全参数 Key、MSET 的交替 Key/value、双 Key、
EVAL/EVALSHA 的 numkeys。Hash Tag 与 CRC16 保持一致。扩展命令时必须同时审查此表。
显式 Key 入口仍校验已知命令，不能通过漏报 Key 绕过 CROSSSLOT。
未知模块命令的 Key/状态语义由调用者负责，Raw 不是安全执行任意命令的保证。

拒绝共享入口上的 MULTI/WATCH、订阅、连接状态、已知阻塞命令等；XREAD/XREADGROUP
暂时整体拒绝，等待专用接口分离 BLOCK 语义。已提供普通 String async typed facade，尚不提供 binary/sync facade、
Pipeline、事务、阻塞和订阅入口；这些组合留在 C5/C6 验收。

保留既有 public 方法签名，新增配置/观测/显式 Key 入口。行为收紧：以前猜测第一参数为 Key
的未知命令现在必须声明 Key；以前会发送的跨 Slot 和状态命令提前拒绝。
这属于快照版本的有意行为变化，不能以“方法签名没变”声称行为完全兼容。

## 验证与环境

```sh
mvn test -pl boba-straw-core -Dtest=ClusterSlotTest,ClusterLifecycleTest
sh scripts/cluster-test-up.sh
mvn test -Dboba.straw.runCluster=true
```

真实测试只访问 `boba-straw-cluster-test`，先验证所有权 label，端口固定 17401–17406。
六个 Redis 7.4.2 进程在一个可丢弃容器内组成三主三副本，loopback 公告地址适配 macOS/Colima。
端口只发布到宿主 loopback；无认证且没有数据卷，禁止用于业务或暴露到公网。
测试修改迁移状态、执行主从切换，并临时 SIGSTOP 一个主节点；finally 恢复，另设容器内
30 秒自动 CONT 保护（即使测试 JVM 中断也会恢复）。节点角色可能改变，重复测试动态发现角色。
不可并发运行这套测试；使用 UUID Key，只删除本次数据，不执行 FLUSHALL/FLUSHDB。
结束后可 `docker stop boba-straw-cluster-test`；重新建立测试环境前显式检查并移除该测试容器，
脚本不会自动删除已有容器。容器内无持久化，因此不要依赖停止/重建保留数据。

`ClusterLifecycleTest` 验证模拟网络故障、ACK 次序、取消、资源归属和坏拓扑；
`ClusterIntegrationTest` 验证 RESP2/AUTO、真实 ASK、主动切换和主节点不可用后的自动选主。
具体运行记录在 [核心收尾计划](../implementation/core-completion-plan.md)。
未将单容器测试等同跨主机网络分区、全量重分片压力、认证轮换、Replica 读或生产长稳验收。

## 官方依据

核对日期：2026-09-22。
[Cluster specification](https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/)、
[CLUSTER SLOTS](https://redis.io/docs/latest/commands/cluster-slots/)、
[ASKING](https://redis.io/docs/latest/commands/asking/)、
[CLUSTER SETSLOT](https://redis.io/docs/latest/commands/cluster-setslot/)、
[CLUSTER FAILOVER](https://redis.io/docs/latest/commands/cluster-failover/)。
