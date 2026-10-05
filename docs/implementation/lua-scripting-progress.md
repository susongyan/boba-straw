# Lua 脚本实施进度

更新日期：2026-10-06。L3 已提交为 `bcde420`；C6 工作树补齐 L4 拓扑组合并通过 JDK 8/21 full 各 200 项验收。
设计约束见[Lua 设计](../architecture/lua-scripting.md)，验证结果及历史失败见[测试记录](../testing/lua-scripting-validation.md)，业务接入见[使用指南](../usage/lua.md)。
本文维护阶段进度与实现差异，不重复维护测试日志。

## 阶段计划

当前 L1/L2/L3 已实现并通过各自限定范围的专项与矩阵验证；
L4 随 C6 补齐 Cluster/Sentinel binary、String sync 与 String Pipeline/事务；
本批最终验收记录以[核心收尾计划](core-completion-plan.md)为准，binary batch 不在本轮范围。
后续文档整理的 JDK 21 回归出现等待相关失败及脚本请求未到达断言失败，历史异常仍未定位；
最新结果见[测试记录](../testing/lua-scripting-validation.md)，不能宣称整个客户端发布验收完成。

| 批次 | 内容 | 退出依据 |
| --- | --- | --- |
| L1 | 三个直接命令、ScriptOutput、Standalone sync/async/binary、Cluster/Sentinel String async、Cluster 定向加载 | 参数/typed/Key 路由/取消与真实缓存测试 |
| L2 | 注册表、按名称执行、目标感知提示与有界 NOSCRIPT → EVAL 恢复 | 首次/命中/失效、并发、路由、总超时与取消竞态测试 |
| L3 | Standalone String typed Pipeline/事务脚本、示例与使用规范 | 混排顺序、NOSCRIPT 单项错误、WATCH abort、取消与租约测试 |
| L4 | 随 C6 补拓扑 binary/sync/批量组合 | 对应拓扑前置能力完成后独立验收，不提前宣传 |

L1～L3 是新增常用 Lua 工作包，不改写历史 C5 冻结验收。
L3：批量入队时捕获注册定义及参数，使用现有 String 批量编码 EVAL，不调用逐条注册执行器，不在批次结束后补发。
L4 依赖 C6 的对应拓扑前置能力。

## 实施前基线

实施前基线：String sync/async 已有 `eval(script, keys, arguments)` 返回 RespValue；异步 EVAL
直接调用 executor，同步 EVAL 已经使用 typed helper。注册表已有 EVAL/EVALSHA 的 SCRIPT Key 规则。
当时尚无专用 scriptLoad/evalSha 方法、脚本结果描述类型和 binary Lua 方法；L1 已补代码，见下文。
Raw 可发送部分形式不代表这些 API 已实现或验收。

## 实施记录

### L1：直接命令与结果类型（2026-09-29）

- 新增 ScriptOutput、ScriptCommandFactory；三个 facade 复用参数与结果规则。
  原 String EVAL 方法签名保留；async EVAL 也使用 TypedCommand。
- 新增 scriptLoad/evalSha、EVAL/EVALSHA typed 重载、Standalone binary Lua、Cluster scriptLoadForKey。
  三种拓扑 String async 复用 facade；无注册表、缓存提示或 NOSCRIPT 自动恢复。
- CommandRegistry.resolve 按 SCRIPT 子命令区分 LOAD（普通无 Key）与 DEBUG（状态型拒绝）。
  其他未知子命令保留 Raw 策略；Cluster 未知形式仍要求显式 Key 声明。
  DEBUG 的版本/行为依据：[官方文档](https://redis.io/docs/latest/commands/script-debug/)（2026-09-29）。

### L2：注册与目标感知执行（2026-09-29）

- 三种 Client 新增 scripts()；String 按名称异步执行，Standalone executeBinary 保留原始 Key/参数。
  注册快照正文并由 JDK SHA-1 计算摘要，结构化 output 校验，无网络注册或隐式 SCRIPT LOAD。
- 注册默认上限 1,024 定义/16 MiB 正文；提示 4,096 条、逻辑在途 4,096 个。
  三种拓扑均通过 Builder 的 `scriptOptions(BobaStrawScriptOptions)` 调整这四项额度，
  名称长度仍固定为 256 个 UTF-16 code unit；连接和 callback 的原准入仍独立生效。
- 提示按物理 NioConnection 对象身份与 SHA 隔离，同端点重连获得新身份；成功先记提示再解码。
  旧对象成功需通过当前目标判定；NOSCRIPT 比较观察 token 后失效。关闭连接的无效条目在
  下一次提示访问时清理（内存仍受硬上限约束），Client close 全部清理，不注册无界 close listener。
- Standalone/Cluster/Sentinel 选择实际连接并绑定发送；Cluster 同 Slot 复用 CommandSpec，
  MOVED 更新对应 Slot 后重评估提示，最多一次重定向；ASK 每次建独占连接并在脚本前 ASKING，
  完成/失败/取消均关闭。ASK 连接不跨操作复用，不保留提示，因此当前 ASK 路径直接 EVAL。
- 单次 NOSCRIPT 恢复只作用于注册 EVALSHA；恢复标记跨重定向保留。初次或恢复 EVAL 的 NOSCRIPT
  原样失败，网络/运行期/解码失败不恢复。Sentinel 错误仍触发原失效发现机制，不迁移该次业务调用。
- NioConnection 增加内部预编码 + 绝对单调 deadline 入口，普通调用保持原超时路径。
  子请求共享逻辑执行开始时的截止时间；保留内核写前/写后超时分类、取消 drain 与 callback 隔离。
  回调排队仍受原 dispatcher 调度影响；不得据此承诺用户 continuation 在 deadline 前获调度。
- 显式直接 SCRIPT LOAD 暂不更新注册提示，调用后首次注册执行仍 EVAL；这不是自动预热 API。
  L3 批量脚本、L4 拓扑 binary/sync 组合和生产长稳未包含在 L2。

### L3：Standalone String 批量脚本（2026-10-05）

- BobaStrawBatchCommands 新增 eval/evalSha/scriptLoad/script，Pipeline 与事务共享目录。
- 注册脚本按名称入队捕获定义与参数，始终 EVAL，不读写缓存提示；无额外网络执行器或重试。
- 非 UTF-8 正文入 String 批量时拒绝；binary 批量仍待后续阶段。
- 新增 ScriptBatchTest、真实服务 TypedBatchCompatibilityTest 脚本用例、
  ScriptRegistryTest 批量取消/FIFO 用例；事务取消、超时和 WATCH abort 测试补入 Lua。
- 首次全量验证因权限审核服务容量不足两次未能启动；恢复后已完成 JDK 8/21
  full 根目录 clean test，各 187 项、零失败/错误/跳过，所有模块成功。
  证据 `$TMPDIR/boba-straw-compatibility-O9ExBu`，详细环境与覆盖见测试记录。
  L3 限定范围验收完成；L4、C6、历史偶发问题根因及发布验收仍未完成。
- 本轮 JDK 8u202 离线 core `test-compile` 通过；不使用网络的 ScriptBatchTest、
  TypedBatchResultTest、ScriptOutputTest 共 7 项通过，零失败/跳过。
  新增公开方法为增量 API，既有公开签名未修改；不替代完整二进制兼容门禁。
