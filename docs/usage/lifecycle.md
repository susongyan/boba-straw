# 生命周期与并发

## 所有权

- 应用启动时构建 BobaStrawClient，普通请求复用。应用停止时停止接收新工作、等待自身在途任务、
  释放订阅、关闭 Client。
- Client 自建 Resources 时由 Client 关闭。传入共享 Resources 时，先关闭所有使用它的 Client，
  最后由资源创建者关闭 Resources。
- Spring 注入的 Client 由容器关闭；业务方法不能用 try-with-resources 包裹注入的 Client。
- 命令行短任务在 main 中使用 try-with-resources 是正确的，不应被审查为“每请求建连接”。
- Pipeline 和 transaction builder 每个操作单独创建，不跨线程共用；Client 可以长期复用。

## 连接配置

普通命令按节点复用共享连接，不需要设置通用连接池大小。
事务专用池按需创建；Standalone 可设置池上限、获取等待和空闲回收：

```java
BobaStrawClient.builder()
    .transactionPoolMaxSize(8)
    .transactionAcquireTimeout(Duration.ofSeconds(1))
    .transactionIdleTimeout(Duration.ofMinutes(1))
    .build();
```

共享连接默认不发送主动心跳。需要检测空闲连接时可设置：

```java
BobaStrawClient.builder()
    .idlePingInterval(Duration.ofSeconds(30))
    .build();
```

仅在连接空闲超过该间隔时发送 PING，业务流量活跃时不会额外发送心跳。
Standalone 可通过 `reconnectInterval(...)`、`reconnectMaxInterval(...)` 设置共享连接退避区间；
重连不会重放失败命令，退避期间新请求明确以未发送失败，而非无界排队。

多个 Client 可共享 `BobaStrawClientResources`；`eventLoopThreads` 是 I/O 线程数量，
不是连接池大小。`callbackThreads` 和 `callbackQueueCapacity` 管理应用回调，
不执行 socket I/O。共享配置示例见[网络模型](../architecture/network-model.md)，
容量配置见[背压指南](backpressure-and-capacity.md)。
回复大小与嵌套限制由 `respLimits(...)` 配置，默认值与失败边界同样见网络模型文档。

## 异步和订阅

公开异步返回 CompletionStage；不要为接入添加 Reactor/RxJava。
这不限制业务项目其他用途的已有依赖。应用自己的 thenApply/thenCompose 派生 future 不保证
取消传播到原始命令；需要取消时保留并操作最初返回的 future，并理解取消不撤销服务端执行。

回调线程有界，避免在回调中进行长时间阻塞操作；必要时把业务工作交给应用管理的有界 executor，
由应用负责其关闭。不要无界提交任务或用无限重试消耗资源。

subscribe/psubscribe 返回 CompletionStage<BobaStrawSubscription>，完成意味着确认成功。
在应用生命周期中保存 handle，在停止时 close；close 发起异步退订，不能视为全部 listener 已结束。
Client.close 是最终资源兜底。listener 容量耗尽可能关闭连接，不能假设自动恢复订阅或消息不丢。
内置 handle 的 `termination()` 提供连接终止观察：未主动 close 时的断连/拓扑退休异常完成，
主动 close 后传输终止正常完成。订阅确认后就建立一次观察；取消观察 Future 不会关闭订阅。
它不是退订 ACK 或回调排空屏障，也不提供自动恢复。需要持久投递时不能仅依赖 Pub/Sub。
三种拓扑都可使用经典订阅；Cluster/Sentinel 切换后可再次使用保存的 pubSub facade 订阅当前主节点，
由业务决定恢复时机，不能承诺切换期间消息无损。

## 事务

helper 按需从有界事务池获取专用连接，支持 AutoCloseable，推荐 try-with-resources 防止遗弃。
WATCH/UNWATCH 必须等待其 CompletionStage 完成，再开始下一操作；不跨线程混用 builder。
成功 EXEC 或 discard 的 UNWATCH 确认后归还；取消、超时、错误或放弃时销毁连接，不重放事务。
discard 清理本地命令和 WATCH；本实现仅在 exec 时才发送 MULTI。
close/取消不能证明 EXEC 未执行。EXEC 内的错误值不导致其他命令回滚。
新代码推荐 `transaction.typed()` 入队、`execTyped()` 提交：先检查结果的 `isAborted()`，
WATCH 冲突时不要读取句柄；未冲突再通过 `result.get(handle)` 获取结果，逐条处理服务端错误。
只有旧 Raw `exec()` 为保持兼容仍将 WATCH 冲突返回为空列表，不能与空事务成功区分。
完整示例见[命令、批量与分页](commands.md)。普通 sync()/async()/binary() 不需要 typed()。

示例中的 join() 仅用于允许阻塞的调用线程；若用异步编排，应等待 watch 完成后再提交事务，
并明确异常、取消和 close 的路径，不能在异步提交尚未结束时退出 try-with-resources。

## 阻塞 List 命令

三种拓扑的 sync()/async() 提供 blpop(long timeoutSeconds, String... keys) 和 brpop。
每次调用按需创建单次专用连接，完成、超时、取消或 Client 关闭后销毁，不占用共享连接。
每个内部节点 Client 默认同时最多 32 条，Standalone 可用 Builder.maxBlockingConnections(...) 设置；
拓扑 Builder 暂不暴露此参数，超限明确拒绝，不排无限队列。
返回列表为 [key, value]；服务端正常等待超时返回空列表。客户端 commandTimeout 始终生效，
即使 timeoutSeconds=0 也不是无限等待。需要等待服务端超时结果时，将客户端超时设得更长。
取消从客户端方法最初返回的 Future 发起；同步等待被中断也关闭该次专用连接。
不承诺被取消的 POP 没有消费元素，也不自动补发。Cluster 多 Key 必须同 Slot；
MOVED/ASK 也返回失败、不重放阻塞操作，后续调用使用刷新的拓扑。其他阻塞命令和二进制阻塞接口仍待扩展。
不要通过共享 Raw/Pipeline 发送阻塞命令。
