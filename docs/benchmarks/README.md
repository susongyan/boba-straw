# Boba Straw 性能基准

本目录定义网络模型演进后的可复跑性能验收。基准用于发现回归和校准容量，不代表生产环境
承诺。不同提交、JDK 或 Redis/Valkey 的结果只有在相同硬件、容器资源和 JMH 参数下才可比较。

## 基准范围

| 基准类 | 关注点 |
| --- | --- |
| `RedisCommandBenchmark` | 同步与 `CompletionStage` GET/SET 的端到端吞吐和延迟 |
| `AsyncWindowBenchmark` | 16、128、1024 个并发 in-flight GET 的异步提交与批量完成 |
| `RedisBatchBenchmark` | 1、16、128 命令的真实 Pipeline 写入与响应排空 |
| `RedisLargeValueBenchmark` | 1 KiB、64 KiB、1 MiB value 的 GET/SET |
| `SharedEventLoopFairnessBenchmark` | 共享 EventLoop 时繁忙 Pipeline 对健康连接尾延迟的影响，并记录每个测量周期的 noisy commands |
| `SlowCallbackIsolationBenchmark` | 5 ms 慢用户回调对同 EventLoop 健康连接尾延迟的影响，并记录每个测量周期的 noisy completions |
| `RespCodecBenchmark` | 编码、Bulk、RESP3 aggregate、128 回复 burst 和逐字节碎片解析 |

Pipeline 方法使用 JMH `@OperationsPerInvocation`，所以吞吐结果已经换算为 Redis commands/s，
不是 batches/s。整批速率可用结果除以 1、16 或 128 得到。sample-time 结果同样是按单条命令
归一化后的延迟；估算整批端到端延迟时，需要再乘以对应 batch size。

两个隔离基准的 `noisyCommands` / `noisyCompletions` 是 JMH `#` 辅助计数，表示每个 measurement
iteration 内完成的数量。换算速率时除以该档位的 measurement 时长；比较版本时必须同时报告这个
负载计数，避免把 noisy 路径变慢误判为健康连接更公平。

## 环境

macOS 推荐使用 Colima：

```bash
colima start
./scripts/benchmark-up.sh
```

脚本启动独立于兼容性测试矩阵的两个容器，并固定镜像 digest、2 CPU、2 GiB、关闭 RDB/AOF：

- Redis 7.4.2：`redis://127.0.0.1:17379`
- Valkey 8.1.3：`redis://127.0.0.1:17380`

停止环境只会删除这两个明确命名的容器：

```bash
./scripts/benchmark-down.sh
```

若本机 Docker 配置引用了已经卸载的 credential helper，应修复该配置或通过临时
`DOCKER_CONFIG` 运行；基准脚本不会改写用户的全局 Docker 配置。

## 执行

构建并查看全部 workload：

```bash
mvn -pl boba-straw-benchmarks -am -DskipTests package
java -jar boba-straw-benchmarks/target/benchmarks.jar -l
```

快速验证：

```bash
./scripts/run-benchmarks.sh smoke all
```

未显式指定目录时，结果写入被 Git 忽略但不会被 Maven `clean` 删除的
`benchmark-results/<UTC-run-id>/`。

正式基线：

```bash
./scripts/run-benchmarks.sh full all docs/benchmarks/results/<run-id>
```

`full` 对每项执行 5 次预热、8 次测量、3 个 fork，并记录 JMH GC profiler；网络 workload
分别输出 throughput 与 sample-time JSON，并保留对应的 JMH 文本日志。分析时至少报告
throughput、P50/P95/P99/P999、
allocation rate、GC，以及 Pipeline 换算后的 commands/s。JMH 自带的 fork 控制通道会绑定本地
回环端口，受限沙箱中需要允许该本地网络操作。

正式档位默认要求 Git 工作区干净，并严格检查所需容器的镜像、端口、健康状态和资源配额。
只有在保存试验性结果且明确接受不可复现风险时，才可设置 `BOBA_BENCHMARK_ALLOW_DIRTY=1`；
环境清单仍会记录当时的工作区状态。runner 每次先执行 `clean package`，并记录 core 与 shaded
benchmark JAR 的 SHA-256。

runner 不允许复用已经存在的结果目录，避免第二次执行覆盖原始 JSON 或环境清单。重跑时应使用
新的 `<run-id>`；若旧结果确认无用，应由操作者显式归档或删除，而不是由脚本代为清理。

## 结果目录

每次正式结果使用独立目录：

```text
docs/benchmarks/results/<run-id>/
  environment.txt
  codec-throughput.json
  redis-throughput.json
  redis-latency.json
  valkey-throughput.json
  valkey-latency.json
  summary.md
```

`environment.txt` 由 runner 自动记录提交、运行前工作区状态、OS、JDK、Maven、JMH、完整执行档位，
并在 Docker 可访问时追加 Colima 状态、Docker 版本、镜像标识、容器 CPU/内存限制和服务端
`INFO server`。正式归档时还应补充测试期间的 CPU/内存采样。原始 JSON 必须保留，
`summary.md` 不能替代原始数据。

## 历史提交 A/B

阶段 2 提交 `ca078f4` 没有 benchmark 源码。对比它与当前实现时，应使用当前同一份 harness，
分别链接两个提交构建出的 core，并按 A/B/B/A 顺序运行。对比 workload 只能使用两边公共 API：
Client endpoint/protocol/resources、同步 GET/SET、Pipeline 和 `RespCodec.Decoder`。不能让旧版本
运行的 harness 引用阶段 5 新增的 Metrics 或容量 API。

`prepare-ab-benchmarks.sh` 从 Git 对象导出三份隔离源码：基线、候选和 harness。harness 在基线
源码树中编译，因此编译期就会阻止误用新 API；产物不包含 Core class，运行时由明确的
`baseline-core.jar` 或 `candidate-core.jar` 提供。构建只使用临时 Maven 仓库，不安装同名
SNAPSHOT，也不切换当前工作区：

```bash
./scripts/prepare-ab-benchmarks.sh \
  ca078f4 <candidate-ref> <harness-ref> benchmark-results/ab-artifacts/<run-id>
```

完整 runner 固定使用 `baseline / candidate / candidate / baseline` 顺序。`smoke` 用于验证链接和
运行路径，`full` 才能进入性能结论；网络目标会先启动或严格复用固定的 benchmark 容器：

```bash
./scripts/run-ab-benchmarks.sh \
  smoke redis benchmark-results/ab-redis-smoke \
  ca078f4 <candidate-ref> <harness-ref>
```

准备正式比较网络模型时，可先运行 `redis-critical`：它保留 `full` 的 5/8/3 统计档位，但只覆盖
异步窗口 1024、Pipeline 128、同步 GET、共享 EventLoop 公平性和慢回调隔离，适合先验证网络模型
的主要收益与风险，再决定是否执行耗时更长的 Redis、Valkey、Codec 全矩阵：

```bash
./scripts/run-ab-benchmarks.sh \
  full redis-critical benchmark-results/ab-redis-critical \
  ca078f4 <candidate-ref> <harness-ref>
```

每个序列使用独立 JVM/JMH fork 和独立 JSON。汇总时按 benchmark、参数和 mode 对齐，吞吐报告
`candidate / baseline`，延迟与分配报告 `baseline / candidate`，并同时给出两组配对比值与离散度，
不能只选一次对候选最有利的结果。

正式结果完成前，阶段 6 仍属于进行中；一次 smoke run 只能证明 runner 和真实网络路径可执行。
