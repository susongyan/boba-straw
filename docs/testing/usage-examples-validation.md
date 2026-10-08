# 接入示例历史核验

本页保留原命令使用指南中的示例核验记录，描述对应日期和基线的实际结果。
当前调用方式见[命令、批量与分页](../usage/commands.md)，已发布版本的支持范围见[命令速查](../usage/command-reference.md)。

## 示例核验记录

2026-09-28，核心基线 `706e616`：本页 CommandExamples 与快速开始 QuickStart 均经 JDK 8u202
编译；在 Redis 7.4.2 上验证普通/二进制读取、Pipeline、事务成功路径和 Scan（RESP2/AUTO），
使用随机 Key 并定点清理。该示例检查不包含 WATCH 竞争、断连或全部服务端版本。
文档共 26 个本地链接检查通过。根 Maven 回归 147 项，0 failures/errors、22 项 opt-in 集成测试
未启用；报告 `$TMPDIR/boba-straw-compatibility-7OYGxm`。临时示例核验目录
`/private/tmp/boba-straw-guide-check-6zPIKG`，不是对外发布的示例模块。
