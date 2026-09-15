# Gathering-write 128 frames ABBA：`b9de0a0` vs `0f3506a`

本次正式 JMH 运行比较默认 `maxGatheringFrames=32` 与候选值 128。运行顺序固定为
baseline / candidate / candidate / baseline，共享 harness 固定为 `b9de0a0`。吞吐侧只执行
Async window 1024 与 Pipeline 128；公平性侧使用两个 Client 共享一个 EventLoop，noisy Client
持续执行 Pipeline 128，同时测量 healthy Client 的同步 GET 延迟和 noisy 完成量。

以下配对比值分别计算 `B1/A1` 和 `B2/A2`，再取几何均值。吞吐和 noisy 完成量越高越好；
延迟越低越好。

## 结论：拒绝 128 作为默认值

- Async window 1024 吞吐提升 **1.493x**，Pipeline 128 吞吐提升 **1.441x**，说明减少小命令
  gathering write 次数确实能显著改善当前本机/Colima 路径的批量吞吐。
- 共享 EventLoop 公平性不满足验收：healthy GET 平均延迟的 baseline/candidate 几何比仅
  **0.842x**，即候选延迟高约 **18.8%**；P50 高约 **8.1%**，P99 高约 **60.0%**。
- 两组配对方向一致：B1 比 A1 慢 4.4%，B2 比 A2 慢 35.1%。但同一基线 A2 相比 A1 也明显
  变慢，存在时间漂移。JMH 样本级置信区间不能消除跨 fork/运行的环境影响；这些结果不足以
  证明帧数导致退化，因此结论是暂不采用 128，不能把点估计当作确定的因果关系。
- noisy Pipeline 完成量的候选/基线几何比为 **0.842x**，候选低约 **15.8%**；128 frames
  没有通过牺牲 healthy 连接换来 noisy workload 的实际完成量。
- 吞吐 allocation 基本不变：Async 的 baseline/candidate 为 **1.0003x**，Pipeline 为
  **1.0017x**。
- 因此 128-frame 候选被拒绝。下一候选采用 64 frames，与单轮最多分发 64 个响应的预算一致，
  在保留部分 syscall 合并收益的同时缩短单连接连续写入切片；仍需独立正式 ABBA 验收。

## 吞吐原始 score

单位为 Redis commands/s；Pipeline 已按 128 commands/invocation 归一化。

| workload | A1 32 | B1 128 | B2 128 | A2 32 | B1/A1 | B2/A2 | 配对几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 115,229.0 | 178,270.3 | 170,821.9 | 118,553.2 | 1.547x | 1.441x | 1.493x |
| Pipeline 128 | 39,457.1 | 51,837.7 | 47,801.3 | 30,259.3 | 1.314x | 1.580x | 1.441x |

JMH 99.9% score error 依次为：Async 22,306.8 / 7,004.6 / 6,471.7 / 7,547.1 commands/s；
Pipeline 8,012.8 / 6,366.2 / 8,975.9 / 5,362.3 commands/s。

## 共享 EventLoop 公平性

延迟单位为 us/op。最后一列对延迟使用 baseline/candidate，对 noisy 完成量使用 candidate/baseline。

| metric | A1 32 | B1 128 | B2 128 | A2 32 | 配对 1 | 配对 2 | 几何比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Healthy GET mean | 2,725.3 | 2,846.2 | 6,023.5 | 4,459.8 | 0.958x | 0.740x | 0.842x |
| Healthy GET P50 | 2,416.6 | 2,535.4 | 4,116.5 | 3,698.7 | 0.953x | 0.899x | 0.925x |
| Healthy GET P99 | 7,060.5 | 7,580.1 | 36,697.5 | 15,385.4 | 0.931x | 0.419x | 0.625x |
| Noisy commands | 1,463,040 | 1,399,040 | 663,296 | 895,360 | 0.956x | 0.741x | 0.842x |

四轮 healthy GET mean 的 99.9% score error 依次为 40.7 / 50.9 / 445.3 / 125.6 us/op。
后半程整体变慢。ABBA 配对可以减轻部分漂移影响，但不能消除非线性负载变化。

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。吞吐与公平性共八份原始 JMH JSON
以 gzip 保存于 [`raw/`](raw/)，并提供 SHA-256。完整文本日志保留在执行机的 Git 忽略目录
`benchmark-results/ab-gathering-write-bea3c0a/`。
