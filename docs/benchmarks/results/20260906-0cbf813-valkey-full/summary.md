# Valkey 8.1.3 full network baseline：`0cbf813`

本次正式 JMH 运行建立 Boba Straw 当前网络模型在 Valkey 8.1.3 上的完整单版本基线，覆盖同步、
`CompletionStage`、异步窗口、Pipeline、1 KiB/64 KiB/1 MiB value、共享 EventLoop 公平性和
慢 callback 隔离。它用于后续版本回归比较，不是与其他客户端的横向对比，也不构成生产容量承诺。

## 结论

- 全部 workload、3 个 fork 和所有 measurement 均正常完成，没有协议失败、超时、断连或线程泄漏。
- 异步窗口 1024 达到约 **96.7k commands/s**；Pipeline 128 达到约 **36.2k commands/s**。
- 同步 GET/SET 平均延迟约 **1.59/1.61 ms**；单请求异步 GET/SET 约 **1.69/1.63 ms**。
- Pipeline 128 按单命令归一化后的平均延迟约 **19.1 us**，整批 128 命令的估算时间约
  2.45 ms。异步窗口延迟也按单命令归一化，不能解释为单个请求独立 RTT。
- 共享 EventLoop noisy Pipeline 场景中，健康 GET 平均/P99 为 **2.92/7.97 ms**，同期辅助计数
  为 1,372,544 条 noisy commands；慢 callback 场景健康 GET 平均/P99 为 **1.66/5.02 ms**，
  同期辅助完成计数为 6,461。
- 1 MiB GET/SET 吞吐约 **46.0/66.4 ops/s**；GET P99 达到 **143 ms**，明显高于 SET 的
  36.2 ms。两者 allocation 均约 2.10 MiB/op。后续同尺寸 `byte[]` 正式结果约为 1.053 MiB/op，
  已确认额外一份 payload 主要来自 String/UTF-8 转换；详见
  [`binary large-value baseline`](../20260906-b9ceff7-valkey-binary-large/summary.md)。

## Throughput

单位为 ops/s；窗口和 Pipeline 已按 Redis command 数归一化。

| workload | score | 99.9% error | allocation B/op |
| --- | ---: | ---: | ---: |
| Async window 16 | 5,934.7 | 453.8 | 1,407.1 |
| Async window 128 | 36,865.9 | 2,934.9 | 1,385.9 |
| Async window 1024 | 96,710.3 | 18,139.9 | 1,385.3 |
| Pipeline 1 | 406.1 | 39.0 | 2,478.8 |
| Pipeline 16 | 6,234.8 | 623.1 | 1,213.8 |
| Pipeline 128 | 36,203.5 | 2,515.2 | 1,169.6 |
| Async GET round-trip | 415.5 | 30.1 | 1,818.8 |
| Async SET round-trip | 393.9 | 28.7 | 1,777.8 |
| Sync GET | 385.7 | 42.5 | 1,431.7 |
| Sync SET | 350.4 | 36.0 | 1,477.3 |

大 value：

| operation | bytes | score ops/s | 99.9% error | allocation B/op |
| --- | ---: | ---: | ---: | ---: |
| GET | 1,024 | 369.6 | 36.3 | 3,363.9 |
| GET | 65,536 | 235.9 | 13.9 | 132,485.6 |
| GET | 1,048,576 | 46.0 | 4.7 | 2,100,478.3 |
| SET | 1,024 | 562.5 | 105.8 | 3,409.8 |
| SET | 65,536 | 398.5 | 19.2 | 132,562.1 |
| SET | 1,048,576 | 66.4 | 2.2 | 2,099,806.3 |

## Latency

单位为 us/op；表中 Pipeline 和 Async window 均按单命令归一化。

| workload | mean | P50 | P95 | P99 | P999 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Async window 16 | 119.96 | 100.61 | 211.97 | 384.70 | 1,046.01 |
| Async window 128 | 19.52 | 17.47 | 31.04 | 48.83 | 126.93 |
| Async window 1024 | 6.37 | 5.89 | 9.39 | 14.06 | 55.36 |
| Pipeline 1 | 1,708.85 | 1,472.51 | 2,899.97 | 5,316.61 | 15,020.29 |
| Pipeline 16 | 109.22 | 97.15 | 170.05 | 283.65 | 1,000.42 |
| Pipeline 128 | 19.11 | 17.06 | 28.77 | 48.21 | 157.02 |
| Async GET round-trip | 1,690.77 | 1,458.18 | 2,912.26 | 4,808.70 | 15,461.20 |
| Async SET round-trip | 1,634.28 | 1,404.93 | 2,846.72 | 4,661.25 | 14,571.42 |
| Sync GET | 1,585.27 | 1,396.74 | 2,494.46 | 4,164.94 | 15,132.44 |
| Sync SET | 1,606.33 | 1,423.36 | 2,449.41 | 4,096.00 | 15,190.56 |
| Shared loop healthy GET | 2,921.04 | 2,600.96 | 5,210.11 | 7,965.49 | 15,958.18 |
| Slow callback healthy GET | 1,659.87 | 1,449.98 | 2,920.45 | 5,020.55 | 9,098.76 |

大 value：

| operation | bytes | mean us | P50 us | P99 us | P999 us |
| --- | ---: | ---: | ---: | ---: | ---: |
| GET | 1,024 | 1,635.53 | 1,419.26 | 4,668.29 | 14,935.95 |
| GET | 65,536 | 2,761.84 | 2,428.93 | 7,290.88 | 21,939.13 |
| GET | 1,048,576 | 22,489.05 | 15,728.64 | 143,130.62 | 437,387.26 |
| SET | 1,024 | 3,404.57 | 2,347.01 | 15,951.95 | 86,627.71 |
| SET | 65,536 | 2,831.32 | 2,457.60 | 7,976.71 | 19,318.05 |
| SET | 1,048,576 | 16,074.82 | 15,138.82 | 36,175.87 | 95,551.49 |

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。吞吐与 sample-time 的原始 JMH JSON
以 gzip 保存于 [`raw/`](raw/)，并提供 SHA-256。执行机上的完整文本日志保留在 Git 忽略目录
`benchmark-results/valkey-full-0cbf813/`。
