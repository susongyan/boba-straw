# Benchmark environment

## Revisions

| role | revision |
| --- | --- |
| Baseline Core | `c4d9898c450679422dc89b614db966b255f8faba` |
| Candidate Core | `9dfa609d72b9fb006710c0aeec03ccd184b5ff12` |
| Shared harness | `c4d9898c450679422dc89b614db966b255f8faba` |

Harness 在 baseline API 上编译，运行时分别只加载显式的 baseline/candidate Core JAR。四轮使用
独立 JVM/JMH fork，顺序为 A1 baseline、B1 candidate、B2 candidate、A2 baseline。

Artifact SHA-256：

```text
baseline-core.jar      0d7790b37b2bf21976153b9699bcccee205e86233a9bed7ebcec2133ed42b58f
candidate-core.jar     59b7fa2e1694903f796bf6be8e16970aa5022df1ff6f7739540a213a92ad94db
benchmarks-harness.jar e44662b29b19966829256108d299a7e17fd4adc11ff581d4ce9b19f94aee6eae
```

## Runtime

| item | value |
| --- | --- |
| UTC start | `2026-09-07T17:56:22Z` |
| Host | macOS 15.4.1, x86_64 |
| Colima | macOS Virtualization.framework, x86_64, Docker runtime, virtiofs |
| Docker client/server | 29.7.2 / 29.5.2 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |
| Host load preflight | 4.79 / 8 logical CPUs = 0.599 per CPU; limit 1.50, passed |

## Redis target

| item | value |
| --- | --- |
| Server | Redis 7.4.2, standalone |
| Image | `redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9` |
| Container limit | 2 CPU, 2 GiB |
| Persistence | RDB/AOF disabled |
| Endpoint | loopback port 17379 mapped to container 6379 |

## JMH options

```text
profile=full
target=redis-transport-overhead
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

原始环境采集包含本机 socket 路径和用户目录，未提交到仓库；本文件只保留复现和解释结果所需的
非敏感信息。
