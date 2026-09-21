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

当前 helper 会获取专用连接，构建后不要遗弃，按顺序提交命令并执行 exec。
不要用取消 future 作为可靠的事务归还手段，也不要虚构 AutoCloseable 事务 API。
WATCH 中止与执行结果检查、取消/异常归还需要单独验证。Redis 事务也不提供命令运行错误的回滚。
