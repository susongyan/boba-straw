# 使用方审查规则

仅审查 Boba Straw 相关使用；审查请求本身不授权修改业务代码、升级依赖或发布。

| 编号 | 严重程度 | 检查内容 | 判定方式 |
| --- | --- | --- | --- |
| BSU001 | error | 业务生产代码依赖 internal 包 | ArchUnit 自动阻断 |
| BSU002 | warning | Client 在高频请求内重复构建/关闭 | AI 结合生命周期判断；短任务允许 |
| BSU003 | error | 关闭注入/共享的 Client、提前关闭共享 Resources | AI 核对所有权 |
| BSU004 | error | 不确定执行后无条件重试写命令、吞掉异常假成功 | AI 核对失败路径 |
| BSU005 | error | 把 Pipeline 当原子事务、共享 Raw 执行状态/阻塞命令 | AI 核对命令语义 |
| BSU006 | warning | listener/future 回调阻塞、无界并发 | AI 核对调用上下文 |
| BSU007 | warning | 订阅、事务 builder 或自建 executor 没有释放路径 | AI 核对应用关闭 |
| BSU008 | error | 虚构公开 API/配置/支持范围，Skill/SDK 版本不匹配仍照搬 | 编译、版本检查与 AI |
| BSU009 | error | 提交真实凭据或把带密码 URI 写入日志 | AI/企业既有密钥扫描 |

每条发现输出：规则编号、确定违规/需人工判断/未验证、严重程度、文件及行号、代码证据、影响、
建议、验证命令。没有证据时标记未验证，不能宣称扫描过整个项目。

ArchUnit 模板在示例 java 模块的 PublicApiBoundaryTest，固定 ArchUnit 1.3.0（test scope）。
复制到业务项目并把包名 com.example.boba 改成自己的生产包；仅导入生产 classes，不扫描依赖 JAR。
RulesVerificationTest 通过正反例验证规则本身。该检查不检测反射访问，也不能证明没有连接泄漏。

## 行为验收卡

| 请求/输入 | 必须观察到的行为 |
| --- | --- |
| 为普通 Service 接入缓存 | 复用 Client，用真实公开 API，有失败路径 |
| 审查每次 HTTP 请求创建 Client | BSU002；仅审查，不自动改代码 |
| 审查短命 CLI 的 try-with-resources | 允许，不误报 BSU002 |
| catch 超时后重试 INCR | BSU004，解释可能重复执行 |
| 用 Pipeline 完成原子扣减 | BSU005，指出非原子且不虚构替代 API |
| 订阅后不保存 handle | BSU007，检查 shutdown 兜底 |
| 要求 rediss/Sentinel 生产接入 | BSU008，说明未实现 |
| 依赖版本未知或不同 | 报告版本未核实/不匹配，不擅自升级 |
| 业务项目已有其他用途的 Reactor | 不要求删除无关依赖 |

记录实际工具、模型、输入、输出证据和人工判定。未执行 AI 行为实验时必须标记“未验证”，
不能用 Skill frontmatter 校验或文本匹配代替行为验收。
