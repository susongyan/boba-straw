# C5 收尾审查

日期：2026-09-28；本轮基线 `c49bcdf` + 第五批工作树。
范围冻结于 [命令模型](../architecture/command-model.md)，不是 Redis 全命令覆盖。

后续更新（2026-10-06）：C6 已补拓扑能力，现状见[核心收尾](core-completion-plan.md)。
H 诊断已定位并修复 Java 通配监听与 VS Code HTTP loopback 监听共存的具体路径，见
[诊断记录](../testing/binary-resp-diagnostics.md)；不据此追认所有历史等待失败同因。
本文后续条目保留 C5 当时的实现和证据范围。

## 功能与验收对应

| 范围 | 已实现入口 | 验收测试 |
| --- | --- | --- |
| C5.1 String/MGET/MSET/SET 选项/范围 | Standalone String sync/async、binary async | BinaryStringCommandsTest、BinaryStringCompatibilityTest、RedisCompatibilityTest |
| C5.2 Key/TTL/Counter/Bit | 同上；binary Key 保留原始字节，计数等仍为数值 | HighFrequencyCommandsCompatibilityTest |
| C5.3 Hash/List/Set/ZSet 冻结高频目录 | 同上；binary Hash 用 Entry 列表、Set 用字节列表，可空 score/rank | HighFrequencyCommandsCompatibilityTest、CommandModelTest |
| C5.4 元数据与 typed 执行 | Registry/Args/Spec/Decoder/TypedCommand；String async 跨三个拓扑复用；sync 直接等待 transport | CommandModelTest、TypedCommandExecutionTest、BobaStrawClientResourcesTest |
| C5.5 Pipeline/事务 | Standalone String 16 方法 typed 目录，与 Raw 混排；句柄归属检查、WATCH abort | TypedBatchResultTest、TypedBatchCompatibilityTest、DedicatedConnectionLifecycleTest |
| C5.5 Scan | String 异步一页，MATCH/COUNT；Standalone/Sentinel 四种，Cluster 单 Key 三种 | ScanCommandsTest、ScanCompatibilityTest、ClusterIntegrationTest、SentinelIntegrationTest |
| 普通 Raw 与特殊隔离 | 已知状态/阻塞命令拒绝普通入口；未知 Cluster 声明全部 Key；不隐式跨 Slot 拆分 | CommandModelTest、ClusterSlotTest、ClusterIntegrationTest |

对应 CMD-02/03/04/05/07/08/09/10/11/12/13/14；命令版本/参数来源沿用
[命令开发历史](command-development-history.md)，本轮无新增命令形式。协议 decoder 未改动，碎片回归仍运行。
审查为本任务内复核，未进行独立 Agent 或跨模型行为验收。

## 第五批帧所有权检查

- 旧路径：参数快照、执行器参数副本、RESP frame，共三份 payload 复制。
- 新路径：准入语义校验后直接生成不可变 RESP frame。只读 view 不暴露 backing array，
  view 之间 position/limit 独立，传输不再次编码；不是 socket/OS 级零拷贝。
- EncodedCommand 和 NioConnection 新 internal 入口不会改变客户端 API，不是业务扩展 SPI。
- 与原路径共用 command/write-byte/callback reservation、deadline、取消排空、FIFO；不添加重试。
- 新增 128 KiB wire 完整性/原数组修改测试和预编码帧背压零发送测试；
  更新 typed 测试验证只读内容、数组不可获取、独立游标与 text/binary 执行器误配拒绝。

## 边界与未关闭风险

1. 历史非法 RESP 标记 H 未定位，保留原失败证据和诊断入口，后续通过不能替代根因说明。
   见 [Binary RESP 诊断](../testing/binary-resp-diagnostics.md)。因此不作“所有风险清零”的 C5 声明。
2. Cluster/Sentinel binary/sync、Pipeline/事务/PubSub/阻塞拓扑组合仍未实现；按 C6 验收，
   不能把普通 String typed 路由成功当作专用能力完成。
3. binary Scan/batch、全部命令/选项、Stream/Geo/HLL 完整 typed 套件不属于本轮冻结范围。
4. JDK 11/17/25、其他 OS、生产长稳及完整网络性能矩阵不由本轮通过自动覆盖。
5. AI Skill 指导不等于 CI 门禁；Skill 发布与跨模型验证状态仍以各自设计文档为准。

## 本轮功能验证

- Java 8 针对性 25 tests 全通过；目录 `/private/tmp/boba-straw-c5-frame-zyVa7X`。
- 根 `mvn clean test` 全模块矩阵启用兼容、Cluster 和 Sentinel；JDK 8u202 / 21.0.7
  各 147 tests，0 failures/errors/skipped。证据 `$TMPDIR/boba-straw-compatibility-e9HETy`，
  源码副本 core/src 与本轮工作树一致。
- Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3，RESP2/AUTO；Cluster/Sentinel 为 Redis 7.4.2。
- Client/Binary/Sync 三个公开 facade 的 `javap -public -s` 与第四批 Java 8 构建对比一致。
  新增 internal EncodedCommand 和 NioConnection.executeEncodedCommand，不移除旧签名；未运行 japicmp。
- 冻结功能的实现及上述环境回归已核对通过；历史非法 H 未定位，完整风险退出仍未关闭。

## 分配量验证

同 harness、A/B/B/A 的 JDK 21 Redis 7.4.2 SET 诊断已完成：1 MiB 的每次分配从
3,151,721.7 B 降至 1,054,878.0 B；64 KiB 从 199,134.0 B 降至 68,041.0 B。
与移除两份 payload 副本一致。原始数据、源码补丁、构建哈希与复跑说明见
[分配量诊断](../benchmarks/results/20260928-c5-binary-frame-diagnostic/summary.md)。

主机正式负载预检失败，保留失败记录后仅运行明确标记的高负载分配量诊断，
没有宣称吞吐/延迟提升；低负载正式网络性能矩阵仍待验收。

## 两项收尾跟进（推送 `2757079` 后）

1. **H 根因：未关闭。** 增加合成服务端写出字节、失败解码器输入窗口与最后一次
   socket 读取缓冲的有限取证；使用主动注入 H 的用例校验取证链路。
   JDK 8 下原场景 1,000 轮 / 2,000 连接未复现，不能据此归因或宣布修复。
   详见 [诊断记录](../testing/binary-resp-diagnostics.md)。
2. **正式性能：未关闭。** 本轮三次主机预检 load/CPU 为 2.728、2.071、2.994，均超过
   1.50 门槛；没有关闭门槛，也未启动正式 JMH。需要空闲窗口，不停止其他应用来造条件。

本轮全模块 full 回归 JDK 8 / 21 各 149 tests、零失败/跳过；最终分片断言的两组
定向回归各 25 tests 通过。测试证据与源码快照差异说明见上述诊断记录。

正式验收先运行同 harness 的 Redis binary GET/SET A/B/B/A，对比 C5 帧优化前后；
之后以独立结果目录运行 Valkey 对应矩阵。固定基线、候选与 harness，避免 HEAD 漂移：

```sh
sh scripts/run-ab-benchmarks.sh full redis-binary-large \
  benchmark-results/c5-binary-redis-formal c49bcdf 706e616 706e616
sh scripts/run-ab-benchmarks.sh full valkey-binary-large \
  benchmark-results/c5-binary-valkey-formal c49bcdf 706e616 706e616
```

选择 JDK 21，串行运行；结果目录必须不存在，复跑时使用新目录。每个参数 3 forks，
5 × 2s 预热、8 × 2s 测量，吞吐及 sample-time 两模式，保留 GC 分配量、原始 JSON、
环境与 JAR 哈希。报告波动与不确定性，不预先承诺吞吐/P99 改善。
该范围验证本次 binary 优化，不替代整个网络模型的全场景性能矩阵。
