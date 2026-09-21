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
尚无 `docs/commands/coverage.yaml`，现阶段在任务报告或相关功能文档记录命令/选项级状态；
实现和验证分开，未运行与不适用分开。Skill 不替代 CI 或人工批准。

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
