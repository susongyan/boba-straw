# Gathering write 32 vs 64：正式 ABBA

## 决策

采用 **64 frames / 64 KiB** 单连接每轮写预算。两组公平性配对均显示健康连接延迟降低，
同时繁忙连接完成量增加；Pipeline 吞吐整体持平。该选择仅由本次 macOS/Colima 实验支持，
不构成跨平台或生产性能承诺。

基线为 `b9de0a0`（32 frames），候选为 `2214adb`（64 frames），共享 harness 为 `b9de0a0`。
固定 A1/B1/B2/A2 顺序，每段吞吐包含两个 workload，公平性包含一个 workload；全部通过
3 forks × 8 measurements 完整性检查，runner 退出码为 0。

## 结果

下表配对比统一为 candidate/baseline，几何比为 sqrt((B1/A1) × (B2/A2))。
吞吐及 noisy 完成量越高越好，延迟及 allocation 越低越好。

| 指标 | A1 | B1 | B2 | A2 | B1/A1 | B2/A2 | 几何比 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Async-1024 commands/s | 121541.6 | 151329.4 | 155846.3 | 162513.0 | 1.2451 | 0.9590 | 1.0927 |
| Pipeline-128 commands/s | 55131.7 | 49768.0 | 54194.4 | 49523.7 | 0.9027 | 1.0943 | 0.9939 |
| Healthy GET mean us | 3384.0 | 3069.3 | 2915.7 | 3134.9 | 0.9070 | 0.9301 | 0.9185 |
| Healthy GET P50 us | 2973.7 | 2752.5 | 2658.3 | 2803.7 | 0.9256 | 0.9481 | 0.9368 |
| Healthy GET P99 us | 8909.0 | 7595.6 | 6946.8 | 8272.5 | 0.8526 | 0.8397 | 0.8461 |
| Noisy commands | 1196288 | 1302400 | 1369088 | 1278208 | 1.0887 | 1.0711 | 1.0799 |
| Async allocation B/op | 1384.0 | 1366.9 | 1383.8 | 1387.0 | 0.9877 | 0.9977 | 0.9927 |
| Pipeline allocation B/op | 1170.2 | 1168.8 | 1168.8 | 1169.7 | 0.9988 | 0.9993 | 0.9990 |

健康连接平均/P99 延迟分别降低约 8.2%/15.4%，noisy 完成量增加约 8.0%。
Async 两组吞吐方向相反，且基线自身漂移明显，因此不把 +9.3% 点估计解释为确定收益；
Pipeline 两组方向也相反，几何比接近 1，作为整体持平记录。allocation 未观察到回归。
ABBA 和 JMH 样本级置信区间均无法排除宿主负载的非线性漂移。

## 功能回归

最终 `mvn test` 于 2026-09-15 通过：58 tests，0 failures，0 errors，3 skipped。
跳过项为未显式启用的外部 RedisCompatibilityTest；本地 socket/FIFO、部分写、取消、协议分片、
连接生命周期及共享 EventLoop 测试均执行通过。未新增运行时依赖或公开 API。

## 复现

```sh
./scripts/run-ab-benchmarks.sh full redis-gathering-write \
  benchmark-results/<new-run-id> b9de0a0 2214adb b9de0a0
```

八份原始 JSON（包括全部分位数、GC 和误差数据）见 `raw/`，附 SHA256SUMS；
完整日志保存在执行机忽略目录 `benchmark-results/ab-gathering-write-64-20260915/`。
环境指纹见 [environment.md](environment.md)。
