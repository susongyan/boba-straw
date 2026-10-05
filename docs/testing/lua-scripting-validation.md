# Lua 脚本测试与验收记录

更新日期：2026-10-05。设计见[Lua 设计](../architecture/lua-scripting.md)，阶段状态见[实施进度](../implementation/lua-scripting-progress.md)。
以下为对应源码快照的历史证据，不自动覆盖后续修改；临时目录可能被系统清理。
各轮结果独立保留；最近一次文档整理附带回归见末尾，不覆盖此前矩阵证据。

## 验收清单

### 场景覆盖与剩余验收

| 场景 | 当前证据 | 尚未完成的专项 |
| --- | --- | --- |
| NOSCRIPT 有界恢复 | 模拟服务端验证请求序列、一次恢复、错误不重放 | 独立专用实例 SCRIPT FLUSH 后的注册执行器恢复 |
| 服务端脚本淘汰 | 使用与缓存失效相同的 NOSCRIPT 处理机制 | Redis 7.4 脚本 LRU 真实触发与恢复；Valkey 版本行为独立核实 |
| 重连/重启 | 模拟断连、物理连接重建和旧代次提示隔离 | 专用真实实例重启后的脚本调用 |
| MOVED/ASK | 模拟 MOVED 与取消；真实 ASK 目标未缓存 | 完整扩容、Slot 重分配、缩容下线全过程及持续流量组合 |
| 主节点切换 | 真实 Cluster 晋升及 Sentinel 双协议切换前后脚本调用 | 跨主机网络分区与生产长稳 |
| Pipeline/事务脚本 | L3 Standalone String：真实混排、NOSCRIPT 单项错误、WATCH abort；模拟取消/FIFO/事务租约销毁 | binary 与 Cluster/Sentinel 批量组合 |

上述缺口不因相邻机制或一次矩阵通过而关闭。SCRIPT FLUSH、重启、淘汰压力试验必须使用独立专用实例。

### 通用检查项

验收遵循命令 Skill CMD-01～05、07、09～13；若改解码器则追加 CMD-06 碎片测试。

- 单元：零/多 Key、参数次序、空值、非 UTF-8、SHA 校验、输入修改、嵌套 typed/null/error。
- 模拟连接：未发送取消、写后超时/断连、迟到响应不串位；直接命令不补载或重放。
- 注册执行器：首次只有 EVAL、命中只有 EVALSHA、NOSCRIPT 只补一次 EVAL、不出现 SCRIPT LOAD；
  首次并发各自执行、类型解码失败不重放、伪造 NOSCRIPT 的脚本约束和精确错误识别。
- 提示与资源：每节点/代次隔离、版本比较失效、旧代次迟到响应、容量淘汰、Client 关闭清理。
- 状态竞态：NOSCRIPT 与取消/超时同时到达、回退前取消、回退写后断连、总 deadline 不重置。
- 真实 Redis 5/6.2/7.4、Valkey 8.1，RESP2/AUTO：LOAD 不执行业务、EVALSHA 执行、缺失 SHA、
  重复 LOAD、脚本错误后的后续连接可用、整数/字节/嵌套响应。使用唯一 Key，清理仅本次数据。
- Cluster：同 Slot/跨 Slot/零 Key、加载仅选定节点、MOVED/ASK 目标缓存缺失、切换后的缓存行为。
- Sentinel：在切换前后检查目标主节点与 NOSCRIPT 传播，不回旧连接、不重放不确定执行。
- SCRIPT FLUSH/重启等缓存失效试验只用独立专用测试实例，不清共享测试或业务实例的脚本缓存。
- 批量：EVAL 与普通命令混排；EVALSHA 错误不改变后续位置，不把单条错误解释为整批未执行。
- 每批运行根 mvn test 与适用真实矩阵；公共 API 兼容复核，文档/使用 Skill 参考同步。

已有 NioEventLoopDeadlineTest 等待超时失败与历史 H 待办不因设计完成而关闭。

## L1 验证记录（2026-09-29）

- JDK 8 定向 17 tests 通过，含 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3 × RESP2/AUTO。
  证据 `/private/tmp/boba-lua-l1-E5PswR`。验证 LOAD 不执行业务、原始二进制、Null/嵌套、
  NOSCRIPT、脚本运行期错误与类型解码错误不重放；模拟 executor 验证取消传播。
- 四个既有公开 facade 的 javap 方法与 descriptor 对照基线构建，无移除或改签名；未运行 japicmp。
  null 参数现在明确本地拒绝；新增重载使部分全 null 字面量调用需显式类型转换，不承诺这些无效调用源码无歧义。
- 首轮根目录全模块 full 矩阵：JDK 21.0.7 的 163 tests 全通过；JDK 8u202 为
  163 tests / 1 failure（DedicatedConnectionLifecycleTest.typedExecTimeoutIsAmbiguousAndClosesDedicatedSocket，
  等待 3 秒抛 TimeoutException，而非预期 ExecutionException）。证据 `$TMPDIR/boba-straw-compatibility-yZ8dOn`。
- 增补 SCRIPT DEBUG 多入口零发送断言后，JDK 21 CommandModelTest 6 tests 通过。
  最终 core/src 的 JDK 8 full 复核为 163 tests / 1 error：
  SentinelLifecycleTest.periodicDiscoveryChangesPrimaryWithoutBusinessTraffic 出现 Unsupported RESP marker: H。
  证据 `$TMPDIR/boba-straw-compatibility-pJst4q`；源码快照与最终 core/src 一致。
  两轮 Java 8 均无 skipped，不放宽超时或断言，不将复跑解释为根因修复。
- 新增 Lua/结果类型测试及真实 Cluster/Sentinel Lua 用例在上述矩阵均通过；
  Cluster 验证定向加载不广播、同 Slot/跨 Slot、ASK 目标 NOSCRIPT 不隐式补载、显式 EVAL 后 EVALSHA 成功。
  但全量 Java 8 门禁未绿，L1 状态为“已实现并通过专项验证，全量回归待收尾”，不宣布完整发布验收。
- 失败证据与后续追踪见 [协议诊断](../testing/binary-resp-diagnostics.md)。性能、长稳及 L2/L3 不在本批验收内。

## L3 验证记录（2026-10-05）

- 基线 `3ad5fef` 加 L3 工作树；隔离 full 脚本执行根目录 `mvn clean test`，
  JDK 8u202 / 21.0.7 各 187 tests，0 failures/errors/skipped，所有模块成功。
  源码、环境与报告保留于 `$TMPDIR/boba-straw-compatibility-O9ExBu`。
- macOS/Colima，Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3；
  TypedBatchCompatibilityTest 在各版本 RESP2/AUTO 上验证 Raw PING 与
  注册 EVAL、显式 EVALSHA NOSCRIPT、SCRIPT LOAD、直接 EVAL、Null 混排，
  覆盖 Pipeline 和事务；WATCH 冲突使用 Lua 写命令且确认未执行。
- ScriptBatchTest 验证本地入队、名称/output 校验、参数快照、非 UTF-8 拒绝、
  关闭注册表后已捕获定义仍不变；ScriptRegistryTest 验证批量取消后的迟到回复排空且不补发。
  DedicatedConnectionLifecycleTest 的 typed EXEC 取消与超时路径包含 Lua，验证租约销毁。
- 同轮包含现有 Cluster/Sentinel 真实回归，但不因此宣称拓扑支持批量脚本。
  本轮未修改传输内核、未新增重试策略；公开 API 为增量，既有签名保留。
- 此前权限审核两次失败时未执行 full；离线 JDK 8 编译和 7 项纯本地测试已通过。
  完整矩阵随后通过不关闭历史非法 H、等待超时的根因待办。
  未做 L3 压测、长稳或完整发布兼容门禁；L4 仍待实施。

## L2 验证记录（2026-09-29）

- 最终生产源码 full 根目录 clean test：JDK 8u202 与 21.0.7 各 176 tests、0 failure/error/skip，
  所有模块成功。证据 `$TMPDIR/boba-straw-compatibility-gCRfI6`；源码快照的 core/src/main 与交付一致。
  包含 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3 的 RESP2/AUTO，真实 Cluster ASK 目标未缓存、
  Cluster 主节点晋升和 Sentinel 双协议切换前后的注册脚本调用；没有清共享脚本缓存。
- ScriptRegistryTest 13 tests 覆盖精确请求序列、一次恢复、错误不重放、二进制快照、Null、
  并发注册/首次执行、取消 drain、回退后的取消、共享截止时间、ASKING 超时未发送分类、
  重连身份、旧目标迟到响应、容量淘汰与关闭。缓存失效恢复是确定性模拟测试，未声称做了真实 SCRIPT FLUSH。
- 后补 ClusterLifecycleTest 的实际 Cluster 适配器 MOVED 提示切换与 ASK 取消测试，
  再跑最终测试源码 unit 根矩阵：两种 JDK 各 178 tests，其中 152 执行通过、26 个 opt-in
  真实集成测试跳过。证据 `$TMPDIR/boba-straw-compatibility-qcsMjX`；full 已覆盖同一最终生产源码，
  不将 unit 的 178 误写为 178 个真实全矩阵通过。
- 过程中的两次测试编译失败保留在 `boba-straw-compatibility-1OfnTO` 与 `-dAWthE`：
  Cluster 集成失败断言辅助方法原限定 RespValue，现改为 CompletionStage<?> 并直接等待结果。
  修正后首轮 full（ASKING 分类补丁之前）两种 JDK 各 174 tests 通过，证据 `-7I7fPr`。
- javap 对照基线 `/private/tmp/boba-straw-h-evidence-7PAYZ1`：三个 Client、三个命令 facade
  与 NioConnection 的既有公开签名/descriptor 均保留；BobaStrawScripts major version 52。
  未运行 japicmp/完整发布门禁、性能或长稳；按命令开发/审查 Skill 核对 CMD-03/07/09/11/12，
  不宣称独立 Agent 审核。旧 JDK 8 H 与事务等待超时本轮未复现，仍未定位根因，不关闭历史待办。

## 设计文档重写后的回归（2026-09-29）

仅修改文档，在隔离源码副本运行 JDK 21.0.7 根目录 unit 矩阵。
结果为 178 tests：150 通过、2 failures、0 errors、26 个 opt-in 集成测试跳过；后续模块未执行。
失败项为 DedicatedConnectionLifecycleTest.typedExecTimeoutIsAmbiguousAndClosesDedicatedSocket
（等待 3 秒得到 TimeoutException，而非预期 ExecutionException），以及
NioEventLoopDeadlineTest.runsDueDeadlineOnTheOwningEventLoop（等待断言失败）。
证据：`$TMPDIR/boba-straw-compatibility-q56IjU`。本轮未诊断根因、未修改代码或放宽测试，
不能把这些等待问题限定为 Java 8；Lua 注册器 13 项测试通过不代表全量回归通过。
文档本身的本地链接检查和两张 Mermaid 11.16.0 语法解析通过，未声称在 VS Code 中视觉验收。

API 示例前移后的文档回归：JDK 21 unit 为 178 tests，151 通过、1 failure、26 skipped。
ScriptRegistryTest.oldTargetLateSuccessCannotInstallCurrentHint 在等待 EVAL 请求时未收到请求，
证据 `$TMPDIR/boba-straw-compatibility-DQpKNY`。本次未改 Java 源码，未定位根因；
不将其归因为环境，也不以其他用例通过关闭先前失败。
