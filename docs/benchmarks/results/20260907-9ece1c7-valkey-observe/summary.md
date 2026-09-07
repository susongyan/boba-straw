# Valkey 8.1.3 transport/system observation：`9ece1c7`

本次专用 JMH run 同时观测 Boba Straw 当前共享物理连接的 socket 调用、传输字节，以及实际 fork
JVM 与 Valkey 容器资源。workload 固定为 async window 1024 和 Pipeline 128；外部 `ps`/Docker
采样会带来扰动，因此数据用于解释 I/O 结构和容量，不与未采样的吞吐 baseline 直接比较高低。

## 结论

- 两个 workload、3 个 fork 和所有 measurement 均正常完成，没有报告协议失败、超时或断连。
- 64-byte value 的 GET 响应和请求分别稳定为 **71/56 B/command**，Core socket byte counter 与
  workload 命令总数完全对应。
- Async window 1024 平均每次正字节 read 承载 **173.44** 条响应，每次 gathering write 承载
  **31.89** 条请求；Pipeline 128 分别为 **127.32/32.00** 条。
- Valkey 与 Redis observation 都显示 Pipeline 精确命中 **32 commands/write**，再次确认当前
  `maxGatheringFrames=32` 是小命令批量写的直接上限。增大 frame 上限仍须通过隔离 A/B 和
  event-loop 公平性复核后才能修改默认值。
- 活跃 fork 样本中，客户端线程数中位数/P95 为 **30/33**，RSS 中位数约 **367.2 MiB**；客户端
  CPU 中位数/P95 为 **49.4%/169.9%**。CPU 百分比按 macOS 逻辑核口径，可超过 100%。
- Valkey 容器 CPU 中位数/P95 为 **31.6%/67.9%**，内存中位数约 **17.94 MiB**。
- 启动前 1 分钟宿主负载为 11.70（8 个逻辑核，1.462/core），虽通过 1.50 门禁但接近上限；
  因此本次吞吐仅作为同一 observation 的容量记录，不用于判定细微性能差异。

## JMH 与 socket 指标

吞吐已经按 command 归一化。辅助计数是全部 measurement iteration/fork 的累计值，不能解释为
单个 iteration。

| workload | throughput commands/s | 99.9% error | commands | read ops | commands/read | write ops | commands/write |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 105,032.4 | 7,861.0 | 5,074,944 | 29,261 | 173.44 | 159,136 | 31.89 |
| Pipeline 128 | 42,670.3 | 1,964.1 | 2,055,552 | 16,145 | 127.32 | 64,236 | 32.00 |

| workload | bytes read | bytes/read op | read B/command | bytes written | bytes/write op | write B/command | allocation B/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async window 1024 | 360,321,024 | 12,314.0 | 71.0 | 284,196,864 | 1,785.9 | 56.0 | 1,399.5 |
| Pipeline 128 | 145,944,192 | 9,039.6 | 71.0 | 115,110,912 | 1,792.0 | 56.0 | 1,195.0 |

## 系统采样

共采集 64 个进程/容器样本。为排除 launcher 和 fork 切换瞬间，稳定统计仅使用 JVM 线程数不少于
30 的 61 个活跃 fork 样本。

| metric | median | P95 | min | max |
| --- | ---: | ---: | ---: | ---: |
| Client CPU | 49.4% | 169.9% | 16.4% | 246.8% |
| Client RSS | 367.2 MiB | 370.8 MiB | 79.6 MiB | 370.9 MiB |
| Client threads | 30 | 33 | 30 | 33 |
| Valkey CPU | 31.6% | 67.9% | 14.9% | 78.7% |
| Valkey memory | 17.94 MiB | 18.66 MiB | 17.90 MiB | 19.92 MiB |

从首个到最后一个系统样本，容器 `eth0` RX/TX counter 分别增加 **629,909,710 B** 和
**796,220,380 B**。该差值覆盖预热、测量、fork 切换和采样边界，不等同于 JMH measurement-only
wire bytes；精确的 measurement 命令/字节比以 JMH 辅助计数为准。

## 复现与原始数据

环境和 artifact SHA 见 [`environment.md`](environment.md)。JMH JSON 和系统 TSV 以 gzip 保存于
[`raw/`](raw/)，并提供 SHA-256。完整文本日志保留在执行机的 Git 忽略目录
`benchmark-results/valkey-observe-9ece1c7-rerun2/`。
