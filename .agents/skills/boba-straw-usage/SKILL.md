---
name: boba-straw-usage
description: Help application teams integrate Boba Straw correctly without adding reactive libraries or hiding Redis failure semantics.
---

# Boba Straw usage

用于业务项目接入、审查和升级 Boba Straw；不用于修改客户端内部实现。

先检查实际 Maven 依赖版本，与包内 manifest.json 核对；在源码仓库中则读取根 usage-skill.json。
SNAPSHOT 必须核对来源提交，无法确认时标记未核实；不要擅自升级 SDK。

按需读取：
- 新接入：[快速开始](../../../docs/usage/quickstart.md)、[能力表](../../../docs/usage/supported-features.md)、[生命周期](../../../docs/usage/lifecycle.md)。
- 失败处理：[失败与重试](../../../docs/usage/failures-and-retries.md)。
- 代码审查：[规则与行为验收](../../../docs/usage/review-checklist.md)，报告编号、位置、证据、影响、建议和验证结果。
- 升级/分发：[版本与渠道](../../../docs/usage/distribution.md)。

使用 BobaStrawClient 公开 API，长期复用 Client，遵守资源所有权。Pipeline 不是事务。
subscribe 返回 CompletionStage<BobaStrawSubscription>；保存 handle，明确关闭路径。
超时和取消不等于服务端撤销。背压异常也可能出现在结果交付阶段，不能仅按异常类决定重试写命令。
未实现的 TLS/Sentinel、未验收的 Cluster/事务取消等按能力表报告，不生成不存在的接口。
不为接入引入响应式依赖；不干涉业务项目其他用途的既有依赖。

修改后编译并运行已有相关测试，说明跳过/未验证内容；不能用 Skill 校验替代行为测试。
仅要求审查时不修改代码。规则不授予安装、发布、发送消息或变更无关配置的权限。
