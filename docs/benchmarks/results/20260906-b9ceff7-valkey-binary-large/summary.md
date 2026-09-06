# Valkey 8.1.3 binary large-value baseline：`b9ceff7`

本次正式 JMH 运行使用 `BobaStrawBinaryCommands` 测量 1 KiB、64 KiB、1 MiB `byte[]` GET/SET，
用于拆分协议内核复制与 String/UTF-8 Codec 成本。它与 String 大 value 使用相同主机、固定镜像和
容器资源，但属于独立 run，因此仅直接比较稳定的 allocation 数量，不把吞吐/延迟差异解释为
Codec 因果。

## 结论

- 1 MiB binary GET/SET 分别分配约 **1,053,007/1,052,860 B/op**，只比 payload 多约
  4.3/4.2 KiB；String 对应为 2,100,478/2,099,806 B/op，几乎正好多一个 1 MiB payload。
- 64 KiB binary GET/SET 分别约 67,297/67,379 B/op；String 对应约 132,486/132,562 B/op，
  同样相差约一个 payload。
- 这证明当前 binary transport/decoder 的大 value 内存成本约为 **1x payload copy + 固定请求开销**，
  String 路径的第 2x 主要来自编码或解码的 UTF-8/String 转换，不应误判为 NIO 内核再次复制。
- `BobaStrawBinaryCommands` 当前只提供 `CompletionStage`，本 benchmark 在 stage 上 `join()`；
  String large-value benchmark 使用同步 facade。两者 API 调度路径不同，所以本次不直接比较
  吞吐和延迟高低。

## Throughput and allocation

| operation | bytes | throughput ops/s | 99.9% error | allocation B/op | String allocation B/op | String/binary |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| GET | 1,024 | 163.66 | 15.58 | 2,707.5 | 3,363.9 | 1.24x |
| GET | 65,536 | 100.85 | 13.66 | 67,297.0 | 132,485.6 | 1.97x |
| GET | 1,048,576 | 18.96 | 2.72 | 1,053,006.7 | 2,100,478.3 | 1.99x |
| SET | 1,024 | 137.11 | 11.71 | 2,862.4 | 3,409.8 | 1.19x |
| SET | 65,536 | 92.88 | 15.05 | 67,378.9 | 132,562.1 | 1.97x |
| SET | 1,048,576 | 19.81 | 2.01 | 1,052,860.2 | 2,099,806.3 | 1.99x |

String allocation 来自独立的
[`Valkey full baseline`](../20260906-0cbf813-valkey-full/summary.md)，用于数量级拆分；吞吐不做跨 run
比较。

## Sample-time latency

单位为 us/op。

| operation | bytes | mean | P50 | P95 | P99 | P999 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| GET | 1,024 | 6,408.46 | 5,292.03 | 11,745.69 | 24,343.02 | 85,834.86 |
| GET | 65,536 | 10,622.95 | 8,830.98 | 18,612.22 | 47,074.51 | 113,014.73 |
| GET | 1,048,576 | 54,698.79 | 43,909.12 | 111,542.27 | 184,538.89 | 238,288.90 |
| SET | 1,024 | 6,427.41 | 5,357.57 | 11,337.73 | 23,014.93 | 86,641.21 |
| SET | 65,536 | 10,597.62 | 8,814.59 | 19,202.05 | 40,903.64 | 134,975.85 |
| SET | 1,048,576 | 61,805.48 | 47,513.60 | 144,021.91 | 225,863.27 | 383,254.53 |

这些延迟是当前异步 binary facade 后 `join()` 的容量基线；若未来增加同步 binary facade，应作为
新的 workload 单独对比，不能混用本表。

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。吞吐与 sample-time 原始 JMH JSON
以 gzip 保存于 [`raw/`](raw/)，并提供 SHA-256。完整文本日志保留在执行机的 Git 忽略目录
`benchmark-results/valkey-binary-large-b9ceff7/`。
