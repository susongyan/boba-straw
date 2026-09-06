# RESP Codec ABBA：`ca078f4` vs `7a2fe41`

本次正式 JMH 运行比较阶段 2 基线 `ca078f4` 与阶段 4 增量状态机候选 `7a2fe41`。同一份 harness
在 baseline API 上编译，运行顺序为 baseline / candidate / candidate / baseline。吞吐改善使用
`B1/A1`、`B2/A2` 的几何均值；allocation 改善使用相反方向的比值。

## 结论

- 64 KiB Bulk 解码吞吐改善 **1.59 倍**，allocation 约减半。
- 逐字节碎片 Bulk 解码吞吐改善 **2.54 倍**；allocation 从约 590 KiB/op 降至约
  1.06 KiB/op，改善 **559.57 倍**。两组配对均明确改善，验证了不再反复复制和重新解析累计
  输入的增量状态机目标。
- 128 回复 burst 解码吞吐改善 **1.94 倍**，allocation 改善 **8.74 倍**。
- RESP3 aggregate 吞吐几何均值约为 **1.01 倍**，两组配对方向不一致，按“基本持平”处理；
  allocation 稳定改善 **1.30 倍**。
- 64B Bulk 吞吐的两组配对分别为 1.44 倍和 1.01 倍，受 A1 基线偏低影响，不宣称稳定的
  1.20 倍收益；allocation 的两组结果稳定改善约 2.17 倍。
- GET 编码在本轮观察到 baseline 416 B/op、candidate 464 B/op。后续把相同 candidate 放到
  baseline 位置后测得 416 B/op，且两份 `RespCodec.class` 字节码相同，因此该 48 B/op 差异
  **不能可靠归因于版本变化**。后续精确尺寸编码优化与更正见
  [`7a2fe41` vs `da546da`](../20260906-7a2fe41-vs-da546da-codec-encode/summary.md)。

## Throughput

单位为 ops/s。`decodeResponseBurst128` 使用 `@OperationsPerInvocation(128)`，结果已按单个回复
归一化。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | B1/A1 | B2/A2 | 几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Bulk 64B | 2,948,240 | 4,254,695 | 3,954,472 | 3,933,785 | 1.44x | 1.01x | 1.20x |
| Bulk 64KiB | 26,104 | 42,002 | 43,053 | 27,394 | 1.61x | 1.57x | 1.59x |
| Fragmented Bulk byte-by-byte | 2,223 | 7,239 | 6,513 | 3,291 | 3.26x | 1.98x | 2.54x |
| RESP3 aggregate | 784,284 | 915,653 | 980,009 | 1,120,219 | 1.17x | 0.87x | 1.01x |
| Response burst 128 | 4,062,908 | 8,055,843 | 7,332,571 | 3,851,521 | 1.98x | 1.90x | 1.94x |
| Encode GET | 2,164,457 | 2,196,515 | 1,899,136 | 2,952,670 | 1.01x | 0.64x | 0.81x |

## Allocation

单位为 B/op；“改善”是两组 `baseline/candidate` 比值的几何均值，数值小于 1 表示回退。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | 改善 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Bulk 64B | 256.0 | 120.0 | 120.0 | 264.0 | 2.17x |
| Bulk 64KiB | 131,216.1 | 65,592.1 | 65,592.1 | 131,216.1 | 2.00x |
| Fragmented Bulk byte-by-byte | 604,645.4 | 1,080.5 | 1,080.5 | 604,601.1 | 559.57x |
| RESP3 aggregate | 824.0 | 632.0 | 632.0 | 824.0 | 1.30x |
| Response burst 128 | 559.1 | 64.0 | 64.0 | 559.1 | 8.74x |
| Encode GET | 416.0 | 464.0 | 464.0 | 416.0 | 0.90x |

上表保留本轮实际观测值，但 Encode GET 的版本因果判断已由后续对照实验取代。

## 复现与原始数据

运行环境和 artifact SHA 见 [`environment.md`](environment.md)。4 份原始 JMH JSON 以 gzip 保存于
[`raw/`](raw/)，并提供 SHA-256 校验文件。完整文本日志保留在执行机被 Git 忽略的
`benchmark-results/ab-codec-7a2fe41/`。

本结果只覆盖纯 CPU/内存 Codec workload；网络端到端结论见独立的 Redis critical 归档。
