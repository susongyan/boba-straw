# Skill 分发与发布约定

## 使用方导入

离线 ZIP 内有 boba-straw-usage/，包含 Skill、references、assets/examples、manifest.json、
SHA256SUMS 和 validator.py。整体导入业务仓库的工具约定目录；不要只复制 SKILL.md。
不支持 Skill 的工具可用项目指令引用 references/quickstart.md。

1. 解压到新目录，在信任来源后执行 validator.py verify。
2. 从 Maven dependency:tree 获取实际 SDK 版本，用 validator.py version 检查匹配。
3. SNAPSHOT 还需企业制品来源提交；未知时只能标记未核实。
4. 将目录提交进业务仓库，记录 ZIP 的 SHA-256（可使用包外发布描述文件），由 PR 审核升级。
5. 先显式调用一次，确认工具确实读取规范；首版不保证各工具自动发现。

项目规则引用片段（AGENTS.md 或工具对应文件，按真实位置修改）：

> 修改或审查 Boba Straw 调用前，读取 <skill目录>/SKILL.md，核对依赖版本与 manifest；
> 只使用配套公开 API。审查输出规则编号、证据和验证结果。不要改动无关依赖。

企业补充规则单独维护，引用固定 Skill 版本与包摘要；不得改写客户端真实失败语义。
Skill 指令不能覆盖业务仓库授权边界，不能自行发布、发送消息或批量更新依赖。

## 维护方打包

`python3 scripts/usage_bundle.py build --output target/usage-bundles` 从干净 Git 提交生成 ZIP。
版本源为 usage-skill.json，Skill 独立版本，SDK 支持版本是显式验证清单，不是推测的范围。
SNAPSHOT 与当前源码提交绑定。Skill 修改时更新独立版本；同名制品不得覆盖。
产物包含规范快照，示例从可编译源码复制；只有源文档需要编辑。

构建会验证本地 Markdown 链接、文件完整性、SDK 版本与根 POM 一致性。sourceRevision 是构建来源，
不是对内容可信性的签名。发布描述只登记 ZIP SHA-256；生产注册中心需另提供认证与制品信任机制。

## 发布适配接口 v1

生成的 publication.json 是 Boba 自有约定，不是任何市场的官方标准：
schemaVersion、name、version、sdk、sourceRevision、artifact（文件名、SHA-256）、
licenseStatus、channels。所有适配器读取此文件和 ZIP，先重新验证摘要与版本，不重新编写 Skill。

| 渠道 | 输入/行为约定 | 发布完成证据 |
| --- | --- | --- |
| private-git | 目标仓库、目标分支、子目录；以新版本目录提交 PR，由企业审批 | PR URL、合并提交、包摘要 |
| private-registry | endpoint、命名空间、通过环境/密钥系统提供的凭据；上传不可变版本再登记索引 | 制品 URL、版本、服务端校验摘要 |
| public-marketplace | 市场标识和其适配器；按市场格式转换、审核后发布 | 市场条目 URL、市场版本与原包摘要 |

同版本同摘要可视为幂等成功，同版本不同摘要必须失败；超时后先查询结果，不盲目重复创建。
适配器返回 channel、status、version、digest、location；失败必须保留原因，不返回成功。
不得把 token 写入 manifest、ZIP 或日志。首版只有渠道合同，没有虚构注册中心 API 或网络上传命令。

当前 licenseStatus=undecided，publicPublishAllowed=false。公开发布前必须明确 Skill、文档和
示例授权并满足目标市场要求；企业私有分发同样需要组织批准。本次 CI 仅保存构建产物，
不自动提交企业仓库或公开发布。转换后的市场包若内容发生变化，需要单独摘要和验证记录。
