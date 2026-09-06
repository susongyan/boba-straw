# RESP encoder ABBA：`7a2fe41` vs `da546da`

本次正式 JMH 运行验证 RESP 命令编码由 `ByteArrayOutputStream` 与临时字符串改为精确计算长度、
一次分配最终 frame 后直接写入的效果。基线是 `7a2fe41`，候选是 `da546da`，harness 固定为
`7a2fe41`，顺序为 baseline / candidate / candidate / baseline。

## 结论

- GET 编码吞吐两组分别改善 **1.45 倍**和 **2.30 倍**，配对几何均值为 **1.82 倍**。
- GET 编码 allocation 两组均从约 416 B/op 降至 144 B/op，稳定改善 **2.89 倍**，每次调用
  减少约 272 字节分配。
- 未修改的五个解码 workload，其 allocation 在四轮中逐项一致。`javap -c -p` 也确认修复前后
  `RespCodec.Decoder` 指令相同。
- 解码吞吐点估计在约 -10% 到 +0% 之间，逐字节碎片和 burst 的两组配对方向相反；各项置信区间
  重叠，结合相同指令与相同 allocation，不判定为编码改动导致的解码回退。64KiB Bulk 的点估计
  约为 0.90 倍，应继续由后续常规基准观察，而不从本次噪声中推导代码因果。

## Throughput

单位为 ops/s。“配对几何均值”按 `sqrt((B1/A1) * (B2/A2))` 计算。

| workload | A1 baseline | B1 candidate | B2 candidate | A2 baseline | B1/A1 | B2/A2 | 几何均值 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Encode GET | 3,283,782 | 4,745,683 | 4,238,470 | 1,839,619 | 1.45x | 2.30x | 1.82x |
| Bulk 64B | 5,815,440 | 5,725,678 | 3,981,237 | 4,000,545 | 0.98x | 1.00x | 0.99x |
| Bulk 64KiB | 64,761 | 57,215 | 43,165 | 47,051 | 0.88x | 0.92x | 0.90x |
| Fragmented Bulk byte-by-byte | 8,691 | 9,317 | 4,856 | 6,618 | 1.07x | 0.73x | 0.89x |
| RESP3 aggregate | 961,364 | 859,683 | 740,632 | 774,587 | 0.89x | 0.96x | 0.92x |
| Response burst 128 | 8,664,662 | 7,573,158 | 7,280,739 | 6,483,136 | 0.87x | 1.12x | 0.99x |

## Allocation

| workload | A1 baseline B/op | B1 candidate B/op | B2 candidate B/op | A2 baseline B/op |
| --- | ---: | ---: | ---: | ---: |
| Encode GET | 416.001 | 144.001 | 144.001 | 416.002 |
| Bulk 64B | 120.001 | 120.001 | 120.001 | 120.001 |
| Bulk 64KiB | 65,592.054 | 65,592.061 | 65,592.081 | 65,592.074 |
| Fragmented Bulk byte-by-byte | 1,080.402 | 1,080.371 | 1,080.802 | 1,080.529 |
| RESP3 aggregate | 632.004 | 632.004 | 632.005 | 632.005 |
| Response burst 128 | 64.000 | 64.000 | 64.000 | 64.001 |

## 对上一轮观察的更正

上一轮 `ca078f4` vs `7a2fe41` 中，GET 编码测得 baseline 416 B/op、candidate 464 B/op。此次将
同一个 `7a2fe41` 放在 baseline 位置后重新测得 416 B/op；两次使用的 `RespCodec.class` 已校验为
完全相同字节码。因此 48 B/op 差异不能可靠归因于 `7a2fe41` 的代码变化，上一轮的“版本回退”
判断由本结果取代。精确尺寸编码仍带来了可重复、显著的真实优化。

## 复现与原始数据

运行环境和 artifact SHA 见 [`environment.md`](environment.md)。4 份原始 JMH JSON 以 gzip 保存于
[`raw/`](raw/)，并提供 SHA-256 校验文件。完整文本日志保留在执行机被 Git 忽略的
`benchmark-results/ab-codec-encode-da546da/`。
