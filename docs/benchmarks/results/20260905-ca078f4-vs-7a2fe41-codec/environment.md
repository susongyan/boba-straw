# Benchmark environment

## Revisions

| role | revision |
| --- | --- |
| Baseline Core | `ca078f4a3f7c42bc496292649522f17ca669db24` |
| Candidate Core | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |
| Shared harness | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |

执行前工作区干净，顺序为 A1 baseline、B1 candidate、B2 candidate、A2 baseline。每轮使用独立
JVM/JMH fork，harness 不包含 Boba Straw Core class。

Artifact SHA-256：

```text
baseline-core.jar      b938b7757fd0d0c9017a0a4cb5190f926029933d19d8490e4fd8922bb442878e
candidate-core.jar     20d759c3eaa2d419c38e07a01400a7c12bcc5dbf952528e8b452e81a2df6b822
benchmarks-harness.jar 63ab89c37db2cf2d993f95561e3561290cc3ced4f7146dc123e57d111fc8ab0b
```

## Runtime

| item | value |
| --- | --- |
| UTC start | `2026-09-05T16:47:05Z` |
| Host | macOS 15.4.1, x86_64 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |

## JMH options

```text
profile=full
target=codec
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

Codec workload 不访问 Redis/Valkey，也不依赖 Docker 性能。原始环境采集中的用户目录和本机 socket
路径未提交。
