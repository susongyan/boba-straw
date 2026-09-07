# 网络故障注入验收

本套件使用 JDK `ServerSocket` 构造确定性的 Redis wire 场景，不依赖真实 Redis、代理软件或定时
概率。它验证网络故障发生时的协议顺序、失败分类和资源释放；真实 Redis/Valkey 兼容矩阵负责验证
服务端兼容性，两者用途不同。

## 执行方式

日常开发只运行带 `fault-injection` 标签的测试：

```bash
./scripts/run-fault-injection-tests.sh
```

需要保存可审计结果时传入一个尚不存在的目录：

```bash
./scripts/run-fault-injection-tests.sh benchmark-results/fault-injection-<run-id>
```

保存模式会记录 Git revision、工作区状态、OS、JDK、Maven、完整 Maven 日志和每个测试类的
Surefire XML。普通 `mvn test` 仍会执行这些测试，标签只提供独立验收入口，不会将其排除在默认
回归之外。

## 故障矩阵

| 故障 | 注入方式 | 必须保持的语义 |
| --- | --- | --- |
| RESP 任意分片 | 在每个 wire offset 拆分 Attribute、Push、Bulk 和 CRLF | 不提前发布半个值；普通响应 FIFO 不错位 |
| 大 Bulk 分片与缓冲复用 | 分段输入后覆盖来源数组 | 已解析 payload 不引用可复用网络缓冲 |
| 畸形或超限回复 | 注入错误 trailer、类型和超限 Bulk | 协议失败是终态；关闭连接且不继续解析污染后的字节 |
| 有界/部分写 | 极小单轮写预算和 gathering frame 上限 | 大命令不被截断；后续命令保持 FIFO |
| 回复 burst | 单次写入多条完整回复 | 达到预算后主动让出 EventLoop，另一连接可获得执行机会 |
| 写后断连 | 服务端读完整命令后直接关闭 socket | 调用方收到“可能已执行”，不得自动重试 |
| 握手前连接失败 | 连接不可达或握手连接被关闭 | 调用方收到“未发送”；共享连接按 capped backoff 重建 |
| 超时/取消后迟到回复 | 先让 Future 超时或取消，再发送两条回复 | 原请求保留 drain 占位，下一请求不得消费错误回复 |
| 单连接故障 | 同一 EventLoop 上只关闭一条连接 | 其他物理连接和 EventLoop 继续服务 |
| 慢 Pub/Sub listener | 阻塞 listener 并耗尽有界 callback 队列 | 关闭专用连接而非静默丢消息；不影响共享命令连接 |
| 退订与关闭竞态 | ACK 前排入消息，ACK 后关闭或等待 listener | ACK 前消息保序；socket 及时释放；关闭后不启动新 callback |

## 验收边界

- 测试不得放宽“默认不重试”规则。
- 已经写入 socket 的命令一律按执行结果不确定处理，即使命令本身通常具有幂等性。
- 取消或超时只终止调用方等待；已写请求仍占据响应槽，直至回复排空或连接关闭。
- Pub/Sub、事务等状态型场景只能使用专用连接，故障后不得归还为可复用健康连接。
- 所有等待均有上限，测试失败时不得遗留 socket、Selector 或非 daemon 测试线程。

这里不模拟随机丢包、带宽整形或宿主 CPU 抢占。此类概率性扰动适合容量诊断，不适合作为 CI 的
协议正确性门禁；如需执行，应单独归档环境和原始结果，不能替代本套确定性验收。
