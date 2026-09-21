---
name: boba-straw-command-development
description: Add, extend, or review Redis command implementations in the Boba Straw source repository, including command options, binary APIs, response mapping, and routing tests. Not for application-side SDK integration.
---

# Boba Straw command development

用于本仓库命令实现，不用于业务接入。开始时简述目标命令、源码基线与本次验收范围；
读取 [AGENTS.md](../../../AGENTS.md) 和[架构决策](../../../docs/architecture/decisions.md)。
检查当前改动，不覆盖已有工作。规范不可读时报告缺失，不声称已遵循。

按任务读取：

- 开发或补齐命令：完整读取[开发流程](references/command-workflow.md)。
- 开发验收或只读审查：读取[验收清单](references/acceptance-checklist.md)，选择适用项。
- 需要细化任务输入：使用[任务模板](assets/command-task-template.md)，不要强迫用户填写可从仓库推导的信息。
- 人员接入、跨 Agent 使用和恢复任务：见[使用指南](../../../docs/development/command-extension-guide.md)。

先核实命令形式、Key 位置、连接状态影响、返回结构及版本，再实现。
普通命令复用执行内核；阻塞、事务、订阅及连接状态命令须检查专用连接前置能力，
不能用共享 Raw API 绕过隔离。缺失且超出授权范围的前置能力需报告并请求方向。

实现状态与验证状态分开。Raw API 能发送不等于类型化支持，取消/超时不等于未执行，
读取或幂等属性不授权自动重试。公开 API 对齐现有风格，但不复制已知历史缺陷。

交付命令/选项及 API 范围、兼容性影响、实际测试结果、未验证项、限制和后续动作。
只读审查不默认修改代码；Skill 不授予启动环境、提交、推送、发布或启动其他 Agent 的权限。
