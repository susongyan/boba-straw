# 生命周期与并发

## 所有权

- 应用启动时构建 BobaStrawClient，普通请求复用。应用停止时停止接收新工作、等待自身在途任务、
  释放订阅、关闭 Client。
- Client 自建 Resources 时由 Client 关闭。传入共享 Resources 时，先关闭所有使用它的 Client，
  最后由资源创建者关闭 Resources。
- Spring 注入的 Client 由容器关闭；业务方法不能用 try-with-resources 包裹注入的 Client。
- 命令行短任务在 main 中使用 try-with-resources 是正确的，不应被审查为“每请求建连接”。
- Pipeline 和 transaction builder 每个操作单独创建，不跨线程共用；Client 可以长期复用。

## 异步和订阅

公开异步返回 CompletionStage；不要为接入添加 Reactor/RxJava。
这不限制业务项目其他用途的已有依赖。应用自己的 thenApply/thenCompose 派生 future 不保证
取消传播到原始命令；需要取消时保留并操作最初返回的 future，并理解取消不撤销服务端执行。

回调线程有界，避免在回调中进行长时间阻塞操作；必要时把业务工作交给应用管理的有界 executor，
由应用负责其关闭。不要无界提交任务或用无限重试消耗资源。

subscribe/psubscribe 返回 CompletionStage<BobaStrawSubscription>，完成意味着确认成功。
在应用生命周期中保存 handle，在停止时 close；close 发起异步退订，不能视为全部 listener 已结束。
Client.close 是最终资源兜底。listener 容量耗尽可能关闭连接，不能假设自动恢复订阅或消息不丢。
当前 API 没有完整的订阅故障通知/恢复契约，业务需要持久投递时不能仅依赖 Pub/Sub。

## 事务

helper 按需从有界事务池获取专用连接，支持 AutoCloseable，推荐 try-with-resources 防止遗弃。
WATCH/UNWATCH 必须等待其 CompletionStage 完成，再开始下一操作；不跨线程混用 builder。
成功 EXEC 或 discard 的 UNWATCH 确认后归还；取消、超时、错误或放弃时销毁连接，不重放事务。
discard 清理本地命令和 WATCH；本实现仅在 exec 时才发送 MULTI。
close/取消不能证明 EXEC 未执行。EXEC 内的错误值不导致其他命令回滚。
为保持兼容，WATCH 冲突仍返回空列表，与空事务成功的空列表无法区分；需要业务自行保留上下文。

```java
try (BobaStrawTransaction transaction = client.transaction()) {
    transaction.watch("key").toCompletableFuture().join();
    transaction.command("SET", "key", "value").exec().toCompletableFuture().join();
}
```

## 阻塞 List 命令

Standalone 的 sync()/async() 提供 blpop(long timeoutSeconds, String... keys) 和 brpop。
每次调用按需创建单次专用连接，完成、超时、取消或 Client 关闭后销毁，不占用共享连接。
默认同时最多 32 条，可用 Builder.maxBlockingConnections(...) 设置；超限明确拒绝，不排无限队列。
返回列表为 [key, value]；服务端正常等待超时返回空列表。客户端 commandTimeout 始终生效，
即使 timeoutSeconds=0 也不是无限等待。需要等待服务端超时结果时，将客户端超时设得更长。
取消从客户端方法最初返回的 Future 发起；同步等待被中断也关闭该次专用连接。
不承诺被取消的 POP 没有消费元素，也不自动补发。其他阻塞命令、Cluster 和二进制阻塞接口仍待扩展。
不要通过共享 Raw/Pipeline 发送阻塞命令。
