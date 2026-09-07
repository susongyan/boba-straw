# Redis 7.4.2 transport/system observation：`9dfa609`

本次专用 JMH run 同时观测 Boba Straw 当前共享物理连接的 socket 调用、传输字节，以及实际 fork
JVM 与 Redis 容器资源。workload 固定为 async window 1024 和 Pipeline 128；外部 `ps`/Docker
采样会带来扰动，因此数据用于解释 I/O 结构和容量，不与未采样的吞吐 baseline 直接比较高低。

## 结论

- 两个 workload、3 个 fork 和所有 measurement 均正常完成，没有报告协议失败、超时或断连。
- 64-byte value 的 GET 响应和请求分别稳定为 **71/56 B/command**，Core socket byte counter 与
  workload 命令总数完全对应。
- Async window 1024 平均每次正字节 read 承载 **176.02** 条响应，每次 gathering write 承载
  **31.88** 条请求；Pipeline 128 分别为 **127.20/32.00** 条。
- Pipeline 的 32 commands/write 精确命中当前 `maxGatheringFrames=32`，说明 frame 上限而不是
  64 KiB byte budget 限制了小命令的单次 gathering write 数。该结论把增大 gathering frame
  数量确定为可测优化候选，但在完成隔离 A/B 和公平性复核前不直接修改默认值。
- 活跃 fork 样本中，客户端线程数中位数/P95 为 **30/33**，RSS 中位数约 **368 MiB**；客户端
  CPU 中位数/P95 为 **56.3%/143.0%**。CPU 百分比按 macOS 逻辑核口径，可超过 100%。
- Redis 容器 CPU 中位数/P95 为 **31.5%/59.8%**，内存中位数约 **5.96 MiB**。

## JMH 与 socket 指标

吞吐已经按 command 归一化。辅助计数是全部 measurement iteration/fork 的累计值，不能解释为
单个 iteration。

| workload | throughput commands/s | 99.9% error | commands | read ops | commands/read | write ops | commands/write |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 147,483.1 | 8,184.7 | 7,107,584 | 40,379 | 176.02 | 222,931 | 31.88 |
| Pipeline 128 | 46,405.2 | 1,883.5 | 2,233,344 | 17,558 | 127.20 | 69,792 | 32.00 |

| workload | bytes read | bytes/read op | read B/command | bytes written | bytes/write op | write B/command | allocation B/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 504,638,464 | 12,497.5 | 71.0 | 398,024,704 | 1,785.4 | 56.0 | 1,400.7 |
| Pipeline 128 | 158,567,424 | 9,031.1 | 71.0 | 125,067,264 | 1,792.0 | 56.0 | 1,194.7 |

## 系统采样

共采集 67 个进程/容器样本。为排除 launcher 和 fork 切换瞬间，稳定统计仅使用 JVM 线程数不少于
30 的 63 个活跃 fork 样本。

| metric | median | P95 | min | max |
| --- | ---: | ---: | ---: | ---: |
| Client CPU | 56.3% | 143.0% | 15.5% | 213.6% |
| Client RSS | 368.1 MiB | 371.6 MiB | 79.5 MiB | 374.0 MiB |
| Client threads | 30 | 33 | 30 | 33 |
| Redis CPU | 31.5% | 59.8% | 15.1% | 76.3% |
| Redis memory | 5.96 MiB | - | 5.93 MiB | 7.84 MiB |

从首个到最后一个系统样本，容器 `eth0` RX/TX counter 分别增加 **815,429,208 B** 和
**1,031,028,868 B**。该差值覆盖预热、测量、fork 切换和采样边界，不等同于 JMH measurement-only
wire bytes；精确的 measurement 命令/字节比以 JMH 辅助计数为准。

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。JMH JSON 和系统 TSV 以 gzip 保存于
[`raw/`](raw/)，并提供 SHA-256。完整文本日志保留在执行机的 Git 忽略目录
`benchmark-results/redis-observe-9dfa609/`。
