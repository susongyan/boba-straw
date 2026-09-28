# C5 binary 帧分配量诊断

日期：2026-09-28。**高负载诊断，不是正式吞吐/延迟 A/B 验收。**
初次正式负载检查因每 CPU load=4.293 超过 1.50 拒绝执行，保留
[预检失败记录](../20260928-c5-binary-frame/environment.txt)。随后明确限定分配量用途，
以 `BOBA_BENCHMARK_MAX_LOAD_PER_CPU=0` 运行；实际初始 load/CPU=2.791。
不对日志中的 ops/s 做改善或回归结论，低负载下的正式网络性能矩阵仍待运行。

## 方法与可复核材料

- A：`c49bcdf20daa171ae7e70b2a6791bcaf1bfbfe6a`；B：该基线加
  [candidate-core.patch](candidate-core.patch)，已对 A 源码执行 `git apply --check --unidiff-zero`。
  包含新增 EncodedCommand 文件；功能矩阵验证了同一 core/src。
- 同一份未修改的 RedisBinaryLargeValueBenchmark，thin harness 在 A 上构建且确认不包含 Core。
  两个 Core 分别构建、运行时替换；构建日志与三个 JAR SHA256 均保留。
- JDK 21.0.7、JMH 1.37、macOS x86_64；专用 Redis 7.4.2 / Colima，2 CPU、2 GiB。
  详见 [客户端环境](environment.txt) 与 [服务端环境](server-environment.txt)。
- SET，AUTO，1 KiB / 64 KiB / 1 MiB，单线程，1 GiB JVM heap，gc profiler。
- 顺序 A/B/B/A，每段每个参数独立 fork 1 次；每 fork 3 × 1s 预热、5 × 1s 测量。
  每个版本每参数合计 2 个 fork、10 次测量；短程诊断不替代正式长预热测试。
- 原始日志和 JSON：01-A、02-B、03-B、04-A；指标 `gc.alloc.rate.norm`，单位 B/op。
  数据含框架与后台线程分配，不能把总 B/op 全部等同业务 payload copy。
- 候选不改协议、路由或重试策略，只把参数快照与执行器副本收敛为一次不可变 wire 编码。

复跑（在仓库根，先选定 JDK，正常环境不要设置关闭负载检查的变量）：

```sh
sh scripts/run-binary-allocation-ab.sh \
  /absolute/baseline-core.jar /absolute/candidate-core.jar \
  /absolute/benchmarks-harness.jar /absolute/new-result-dir
```

本轮隔离构建保留在 `/private/tmp/boba-straw-c5-ab-uSfXF0`，临时文件可能被系统清理；
正式复核可由上述基线/补丁重建。脚本仅使用既有专用 benchmark Redis 17379，不自动安装环境。

## 观察结果

两段同版本均值，仅描述本次诊断：

| SET payload | A：旧 typed 路径 B/op | B：不可变帧 B/op | 本轮分配下降 |
| --- | ---: | ---: | ---: |
| 1 KiB | 5,300.7 | 3,290.8 | 37.92% |
| 64 KiB | 199,134.0 | 68,041.0 | 65.83% |
| 1 MiB | 3,151,721.7 | 1,054,878.0 | 66.53% |

大值的两段 A 均约 3x payload，两段 B 均约 1x payload 加固定开销，与去除两份完整 payload
副本的源码变化一致。1 KiB 固定开销占比更高，且高负载后台分配噪声更明显。
结果支持“这条路径不再做两轮参数 payload 复制”，不证明所有 binary 命令、所有 JDK/平台，
也不证明吞吐、P99 或生产资源使用会按相同比例改善。未运行 Valkey 或完整网络 ABBA。
