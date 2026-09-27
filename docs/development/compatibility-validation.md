# JDK 与平台兼容性验收

核心最低运行版本仍为 Java 8。编译通过、模拟服务器测试、真实 Redis 测试是不同层次的证据，
不能用其中一项替代另外两项，也不能用 macOS 的结果宣称 Windows/Linux 已通过。

## 本地可重复执行入口

在仓库中执行，显式提供各 JDK 的绝对路径（包含 `bin/java` 与 `bin/javac` 的目录）：

```sh
sh scripts/run-compatibility-matrix.sh unit /absolute/path/to/jdk8 /absolute/path/to/jdk21

sh scripts/redis-test-up.sh
sh scripts/cluster-test-up.sh
sh scripts/sentinel-test-up.sh
sh scripts/run-compatibility-matrix.sh full /absolute/path/to/jdk8 /absolute/path/to/jdk25
```

要求：Maven、Git、rsync；`full` 还需要 Docker CLI 与运行中的专用测试环境。
启动脚本不自动删除/替换现有容器；已存在但停止或配置不符的容器需要先人工核对。
不要同时运行多个 `full`，Cluster/Sentinel 故障测试共享测试拓扑并会触发主从切换。
这些容器必须是无业务数据的测试环境，不能将生产地址映射到测试端口。

执行器在临时目录复制当前源码（包括未提交改动），排除 `.git`、`target` 和 IDE 配置。
每个 JDK 串行运行全模块 `mvn clean test`；`full` 额外打开三个真实服务端测试开关。
既不修改默认 Java，也不在编辑器使用的 target 中构建。测试失败不重跑掩盖，继续收集其余
JDK 的结果，最终只要有一项失败就返回非零退出码。

输出目录由脚本打印，保留以下内容，不自动删除：

- `source/`：本次源码快照；每个 JDK 另用 `run-N/build/` 构建，避免失败时误收集上一轮报告。
- `environment.txt`：时间、平台、基线提交、工作树状态及执行模式。
- `results.tsv`：每次运行的 JDK 路径和退出码。
- `run-N/environment.txt`、`maven.log`：实际 JDK/Maven 版本和完整构建日志。
- `run-N/<module>/surefire-reports/`：该次运行独立保存的 XML 和文本报告。

默认 `unit` 不启动真实 Redis；被条件跳过的测试必须在验收记录中注明。
报告可能含本地路径、测试命令及测试凭据，分享前先检查；不要给测试环境注入生产凭据。
临时目录可能被系统清理，正式发布证据需另行归档，不能只引用本机临时路径。

## CI 验证层次

`.github/workflows/ci.yml` 定义：

| 作业 | JDK | 平台 | 范围 |
| --- | --- | --- | --- |
| test | 8/11/17/21/25 | Linux、Intel macOS、Windows | 全模块 verify，模拟服务器测试；真实 Redis 测试跳过 |
| redis-integration | 8/11/17/21/25 | Linux | 四 Standalone 服务端、六节点 Cluster、三个 Sentinel；全部真实测试开关开启 |

每个 CI 作业使用独立 runner，失败也上传 Surefire 报告；不会在本地触发提交或发布。
macOS 采用 [GitHub 官方 Intel runner 标签](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)，
避免将现有 x86_64 验证与 ARM64 验证混为一谈。ARM64 与跨主机分区测试尚不在此矩阵中。
配置好作业不等于作业已运行；应以对应提交的实际 Actions 结果作为平台验收证据。

服务端固定版本为 Redis 5.0.14/6.2.14/7.4.2、Valkey 8.1.3；Cluster/Sentinel 仅 Redis 7.4.2。
这不是所有 Redis/Valkey 版本的覆盖，也不代替 TLS、Starter Boot 版本矩阵或性能/长稳验收。
后续版本升级应修改固定镜像并重新记录报告，不能沿用旧版本通过结论。
