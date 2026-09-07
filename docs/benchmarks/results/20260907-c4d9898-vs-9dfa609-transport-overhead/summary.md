# Socket instrumentation ABBA：`c4d9898` vs `9dfa609`

本次正式 JMH 运行隔离比较加入 socket read/write 次数与字节计数前后的 Core。运行顺序固定为
baseline / candidate / candidate / baseline；共享 harness 固定为计数器加入前的 `c4d9898`，不会
读取新指标 API。以下配对比值分别计算 `B1/A1` 和 `B2/A2`，再取几何均值。

这是一台 macOS 开发机经 Colima 访问固定资源 Redis 容器的本地结果，用于判断 instrumentation
是否造成可分辨回归，不构成生产环境性能承诺。

## 结论

- Async window 1024 的候选/基线配对几何均值为 **1.085x**；两组候选都更高，因此没有观察到
  instrumentation 吞吐回归。该正向差异不能归因于计数器，应视为运行波动或不可控环境因素。
- Pipeline 128 的候选/基线配对几何均值为 **0.972x**，点估计低 **2.8%**；两组结果的 99.9%
  误差区间均重叠，且 A1/A2 自身存在约 7% 漂移，因此当前数据不能把该差异归因于计数器。
- Async allocation 的候选/基线几何比为 **0.99946x**，Pipeline 为 **0.99997x**；实际差异小于
  **1.5 B/op** 和 **0.2 B/op**，没有观察到 allocation 回归。
- 结论是：当前每次正字节 socket read/write 更新 64-bit 计数器，在这两个高吞吐 workload 下
  没有产生可分辨的实质开销。计数器保留；后续 gathering-write A/B 使用同一 instrumentation
  版本作为两侧共同基线，避免把两个变量混入一次试验。

## 吞吐原始 score

单位为 Redis commands/s；Pipeline 的 `@OperationsPerInvocation(128)` 已完成按命令归一化。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | B1/A1 | B2/A2 | 配对几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 112,097.6 | 124,632.6 | 126,889.5 | 119,787.9 | 1.112x | 1.059x | 1.085x |
| Pipeline 128 | 46,222.2 | 45,415.5 | 41,529.3 | 43,195.9 | 0.983x | 0.961x | 0.972x |

JMH 99.9% score error 依次为：Async 4,175.0 / 5,916.1 / 9,548.2 / 6,543.0 commands/s；
Pipeline 1,737.4 / 1,716.1 / 2,107.6 / 3,239.6 commands/s。

## Allocation

| workload | A1 baseline B/op | B1 candidate B/op | B2 candidate B/op | A2 baseline B/op | candidate/baseline 几何比 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 1,385.145 | 1,383.657 | 1,383.500 | 1,383.498 | 0.99946x |
| Pipeline 128 | 1,170.254 | 1,170.054 | 1,170.105 | 1,169.967 | 0.99997x |

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。四段原始 JMH JSON 以 gzip 保存于
[`raw/`](raw/)，并提供 SHA-256。完整文本日志保留在执行机的 Git 忽略目录
`benchmark-results/ab-transport-overhead-e2d2d28/`。
