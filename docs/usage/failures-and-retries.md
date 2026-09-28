# 失败、取消与重试

结果不确定是业务必须处理的状态。重连成功不会补发已失败命令。

| 情况 | 可作出的判断 | 应用处理 |
| --- | --- | --- |
| 明确的 CommandNotSent 或入队前准入拒绝 | 该次请求未发送 | 仍需考虑业务期限、限流与幂等，不能无限重试 |
| 已写出后的超时、断连或 MayHaveExecuted | 可能已经执行 | 写命令不能无条件重试；使用业务幂等机制或对账 |
| 取消 | 调用方不再等待，不等于服务端撤销 | 保留不确定执行语义 |
| 服务端 Error | 服务端拒绝或命令错误 | 按具体命令与错误处理，不能把整批操作当作未执行 |
| 其他连接/结果交付错误 | 未必能确定执行阶段 | 保守处理，不按异常大类猜测 |

特别注意：BobaStrawBackpressureException 既可能来自发送前的容量拒绝，也可能来自命令执行后的
callback dispatcher 关闭。不要只 catch 这个类就重试 INCR/LPUSH 等写命令。异常消息不是稳定的
机器分类 API；无法证实发送前阶段时按“可能执行”处理。

join/get 可能包装异常，检查 CompletionException/ExecutionException 的 cause 链，
不要把异步失败转换成 null 或假成功。原始 GET 返回 null 表示 key 不存在，与失败不同。

Pipeline 不保证原子性；整体 Future 失败时，部分命令可能已经执行。
事务 EXEC 内单条命令错误也不意味着其他命令被回滚。业务重试策略必须结合具体操作定义。

## 批量结果分两层处理

- `executeTyped()/execTyped()` 的 Stage 异常：整批结果未正常交付；可能已有命令执行。
- Stage 成功：逐条 `result.get(handle)` 仍可能抛 BobaStrawServerException；不能把 Stage 成功当作全部成功。
- 事务 `isAborted()` 为 true：WATCH 冲突导致事务未执行，不是网络不确定失败；由业务决定冲突处理，
  客户端不会自动重试。成功的空事务为 false。冲突时不能读取句柄。
- 旧 Pipeline `execute()` 的单条服务端错误仍使整批 Stage 异常；旧事务 `exec()` 则在数组内保留错误，
  WATCH 冲突返回空列表。不要将新旧入口的结果契约混用。

示例见[命令、批量与分页](commands.md)。协议解析异常会关闭物理连接；
如果命令字节已写出，仍按“可能已执行”处理，不因异常来自客户端就认定 Redis 没执行。
