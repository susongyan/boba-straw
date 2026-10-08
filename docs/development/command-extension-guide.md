# 用 AI 扩展 Redis 命令

本指南用于维护 Boba Straw 源码，不用于业务应用接入。
命令扩展 Skill、流程、验收清单和任务模板已落地；覆盖 schema/全量命令盘点、
专用 CI 检查器、跨模型行为验证和分发包尚未完成。阶段定义见
[设计与计划](../implementation/command-development-skill-design.md)。

## 第一次使用

1. 在 Boba Straw 仓库工作，保留当前源码及 `.agents`、`docs` 和 AGENTS.md。
2. 让工具读取 [AGENTS.md](../../AGENTS.md) 和
   [命令扩展 Skill](../../.agents/skills/boba-straw-command-development/SKILL.md)。
3. 首次任务检查 Agent 是否说明目标命令、源码基线和验收范围；不能只看目录存在就认为已加载。

支持 Skill 自动发现的工具可按任务选择；根 AGENTS.md 也有路由。
不支持发现的工具使用下方显式读取提示。新建 Skill 未出现在当前会话列表时，也直接读取文件，
不必依赖工具特有安装机制。本版依赖仓库相对路径，不能只复制一个 SKILL.md 到其他项目。

## 派任务

### 新增普通命令

```text
使用 $boba-straw-command-development 补齐 Set 的普通命令。
先盘点已有接口与测试，只实现不需要新增连接管理能力的缺口。
按验收清单验证，列出未覆盖 API 和未运行测试。本次不提交或推送。
```

### 扩展命令选项或数据结构

```text
按仓库命令扩展 Skill 补齐 Stream 命令。
先按普通、阻塞和管理形式分组，核对选项版本与已有连接能力。
列出前置依赖及分阶段计划，再实施已确认范围，不用共享 Raw API 绕过隔离。
```

### 不支持 Skill 发现的 Agent

```text
读取 AGENTS.md 和 .agents/skills/boba-straw-command-development/SKILL.md，
按其中引用的流程扩展指定 Redis 命令。先确认文件可读取，缺失时报告。
仅审查时不要改代码；未执行的测试不得报告通过。
```

### 审查已完成实现

```text
用命令扩展验收清单及 boba-straw-review 审查本次命令实现，不修改代码。
检查参数与返回语义、Key 路由、连接隔离、失败/取消及公共 API 兼容性。
报告文件位置、证据、影响和建议，区分已验证与待验证。
```

复杂任务可复制[任务模板](../../.agents/skills/boba-straw-command-development/assets/command-task-template.md)。
不用每次重写项目约束。实际流程以[开发流程](../../.agents/skills/boba-straw-command-development/references/command-workflow.md)
为准，本指南不维护第二套实现规则。

## 如何验收

用[验收清单](../../.agents/skills/boba-straw-command-development/references/acceptance-checklist.md)
逐项核对适用范围和证据，重点看：方法存在是否等于完整选项支持、协议与拓扑是否真实验证、
缺失连接能力是否被偷偷绕过、失败是否被转换成成功或自动重试。

交付应有实际命令、测试类/方法、源码基线、环境及结果。
根目录执行 `mvn test`；真实服务端测试另按任务范围执行，不能把单元测试成功当全版本兼容。
使用已有测试环境与独立 Key 前缀，不把生产实例当测试环境，不使用全库清理。

## 元数据与三层接口

实施设计见 [命令模型](../architecture/command-model.md)。高频范围以
[命令速查](../usage/command-reference.md) 为准，不要求按 Redis 官网逐一生成所有方法。

新增普通命令时先检查包内 CommandRegistry：已有条目复用，缺失则核实 Key 规则、连接模式与
版本后注册。参数视图 CommandArgs 区分文本与原始字节；复杂 Key 位置不能猜作 first-key。
可空数值、RESP3 Double、Hash Map 与 Set 优先复用 CommandDecoders；异步映射继续使用
BobaStrawStages.map 保留取消传播；同步使用 transport completion，不能等待 callback worker。

String 异步普通方法统一使用 BobaStrawAsyncCommands 的 typed helper，构造 TypedCommand<T>
并经 CommandExecutor 执行。该 facade 已由 Standalone/Cluster/Sentinel 共同复用，不再复制
每个拓扑的方法；必须补同 Slot/跨 Slot、RESP2/AUTO 与拓扑适用性测试。
命令参数由调用对象持有快照；不要暴露可修改数组。不要用 thenApply 替代保留取消传播的映射。
BLPOP/BRPOP 在三种拓扑均走专用连接；Cluster 要求同 Slot，不可用普通 executor 实现。
Standalone binary 普通方法使用 TypedCommand.binary + BinaryCommandExecutor + 共享 decoder，
参数必须保留原始字节快照；当前直接编码为不可变 EncodedCommand，不做两轮 payload 防御复制。
只读帧与每请求独立 position/limit 保证所有权；不能用 String 转码来复用文本执行器。
Standalone 同步普通方法同样构造 TypedCommand，但直接等待 transport 后在调用线程解码。
Pipeline/事务复用调用对象本地入队，不能因此改为普通 executor 逐条发送。
三种拓扑已接入普通 binary、String sync 和注册脚本 executeBinary；Pipeline 为 String 批量，
Cluster 整批同 Slot、不事后恢复 MOVED/ASK。拓扑事务绑定主节点代次，Cluster 显式 routingKey；
拓扑 BLPOP/BRPOP 和经典 Pub/Sub 复用专用生命周期，不重放/自动重订阅。binary Scan/batch 仍未提供。

不要在各 facade 再维护阻塞/状态命令黑名单。新增特殊能力走独立生命周期，不能将注册表属性改成
ORDINARY 来绕过限制。注册为 read-only 也不授权自动重试；since 记录基础命令版本，不代表所有选项同版本。
冷门普通命令优先文档化 Raw 用法；未知 Cluster 命令必须显式声明全部 Key。

CommandModelTest 自动遍历注册表检查查找、Key 元数据和连接模式一致性，但它不是自动的 Redis 语义证明。
每种新增形式仍需参数/空值/错误/协议/路由测试；遵循指南不代表已完成独立 Agent 或跨模型验收。
尚无 `docs/commands/coverage.yaml`，现阶段在任务报告或相关功能文档记录命令/选项级状态；
实现和验证分开，未运行与不适用分开。Skill 不替代 CI 或人工批准。

### 批量 typed 扩展

Pipeline/事务的 BobaStrawBatchCommands 返回 BobaStrawCommandHandle<T>，不返回 CompletionStage<T>。
普通方法复用 TypedCommand/decoder，由所属批次本地入队；不能调用普通 executor 绕过批量提交或事务租约。
result.get(handle) 在调用线程映射，参数及结果类型需与普通 facade 一致。
新方法应进入 TypedBatchCompatibilityTest，覆盖 Raw/typed 混排、位置、错误与 RESP2/AUTO。
不得将网络错误转成单条 RESP Error，也不得把 WATCH abort 当作合法空值。
批量取消由 executeTyped/execTyped 的 Stage 控制；不要添加绕过已有 FIFO/租约销毁语义的单条取消。

## 换模型、恢复任务与排障

| 情况 | 处理 |
| --- | --- |
| 工具没加载 Skill | 显式指向仓库 SKILL.md，确认文件可读；不复制多套规则 |
| 加载了但未遵循 | 对照验收编号指出缺少的产物或测试，要求补齐证据 |
| 规范本身遗漏 | 修订规范及对应测试，避免只修一次聊天提示 |
| 中断后继续 | 重读 git 状态、现有改动和最近报告，列出剩余范围，保留已有修改 |
| 全局安装版与仓库版冲突 | 使用当前仓库维护的版本并报告冲突，不静默混用 |
| 无测试环境/权限 | 报告未运行项及需要的环境，不伪造通过、不擅自探测生产 |
| 公共 API 与规范冲突 | 说明历史兼容性风险，提出方案，不直接破坏已有调用者 |

仓库版以源码提交标识，未提交修改也应在报告中注明。未来离线分发需要携带规范快照和版本，
目前不能宣称已有统一市场发布或跨工具安装命令。
显式调用或自动选择 Skill 都不授予提交、推送、环境安装、发布或启动其他 Agent 的权限。
