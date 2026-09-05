# Redis critical ABBA：`ca078f4` vs `7a2fe41`

本次正式 JMH 运行比较阶段 2 基线 `ca078f4` 与网络模型阶段 5 及 benchmark harness
提交 `7a2fe41`。运行顺序固定为 baseline / candidate / candidate / baseline，以下“改善倍数”
分别计算 `B1/A1` 和 `B2/A2`，再取几何均值；吞吐越高越好，延迟与 allocation 越低越好。

这是一台 macOS 开发机经 Colima 访问固定资源 Redis 容器的本地结果，用于判断代码演进方向和
发现回归，不构成生产环境性能承诺。

## 结论

- 异步窗口 1024 的吞吐改善 **2.30 倍**，每命令 allocation 从约 6.4 KiB 降至 1.8 KiB，
  改善 **3.57 倍**。
- Pipeline 128 的吞吐改善 **1.29 倍**，平均延迟改善 **1.23 倍**，每命令 allocation
  改善 **2.98 倍**。
- 慢用户 continuation 隔离场景中，健康 GET 平均延迟改善 **3.59 倍**、P50 改善
  **4.25 倍**、P99 改善 **2.16 倍**；噪声侧完成量仅低约 2.9%。这支持 callback 不再占用
  Selector EventLoop 的设计目标。
- 共享 EventLoop noisy Pipeline 场景中，候选版本的噪声命令量高 **1.40 倍**，同时健康 GET
  平均延迟仍改善 **1.39 倍**、P99 改善 **2.33 倍**。公平预算没有通过压低繁忙连接吞吐来换取
  健康连接延迟。
- 同步 GET 平均延迟的配对几何均值改善 **1.08 倍**，但第二组置信区间接近重叠，只能视为
  “未观察到回归”，不据此宣称确定的单请求延迟收益。

## 原始 score

吞吐单位为 Redis commands/s；Pipeline 的 `@OperationsPerInvocation(128)` 已完成按命令归一化。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | 配对改善几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 throughput | 47,140.3 | 110,244.8 | 112,410.9 | 49,625.9 | 2.30x |
| Pipeline 128 throughput | 28,088.9 | 38,692.2 | 38,969.6 | 32,253.4 | 1.29x |

延迟单位为微秒/操作；Pipeline 延迟同样按单条命令归一化。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | 配对改善几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pipeline 128 mean | 36.066 | 27.458 | 26.224 | 30.257 | 1.23x |
| Sync GET mean | 2,520.279 | 2,216.429 | 2,183.501 | 2,233.842 | 1.08x |
| Shared loop healthy GET mean | 6,005.829 | 4,212.425 | 3,675.599 | 4,972.395 | 1.39x |
| Slow callback healthy GET mean | 8,362.477 | 2,332.046 | 2,226.814 | 8,002.463 | 3.59x |

## 尾延迟与干扰负载

| workload | P50 改善 | P99 改善 | 噪声侧负载变化 |
| --- | ---: | ---: | ---: |
| Pipeline 128 | 1.23x | 1.35x | - |
| Sync GET | 1.06x | 1.07x | - |
| Shared loop healthy GET | 1.22x | 2.33x | 1.40x |
| Slow callback healthy GET | 4.25x | 2.16x | 0.97x |

`Shared loop` 的辅助计数依次为 656,000 / 942,336 / 1,083,776 / 793,984；`Slow callback`
依次为 5,574 / 5,513 / 5,535 / 5,804。四轮 measurement 时长相同，因此可以直接做配对比值。
P999 样本对单次调度抖动较敏感，完整值保留在原始 JSON 中，不单独把某一轮极值作为结论。

## Allocation

| workload | A1 baseline B/op | B1 candidate B/op | B2 candidate B/op | A2 baseline B/op | 改善 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 6,401.8 | 1,799.4 | 1,799.3 | 6,432.4 | 3.57x |
| Pipeline 128 throughput | 4,718.4 | 1,586.2 | 1,586.0 | 4,744.1 | 2.98x |

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。原始 JMH JSON 以 gzip 保存于
[`raw/`](raw/)；解压后可直接交给 `jq` 或 JMH 结果分析工具。完整未压缩 JSON 和文本日志仍保留在
本次执行机的 `benchmark-results/ab-redis-critical-7a2fe41/`，该目录由 Git 忽略。

本轮只完成 Redis critical 子集。Redis/Valkey 全 workload、Codec、大 value、CPU/socket 观测及
故障注入仍属于阶段 6 的后续验收项。
