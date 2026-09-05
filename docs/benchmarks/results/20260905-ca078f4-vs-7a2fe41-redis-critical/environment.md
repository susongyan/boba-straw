# Benchmark environment

## Revisions

| role | revision |
| --- | --- |
| Baseline Core | `ca078f4a3f7c42bc496292649522f17ca669db24` |
| Candidate Core | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |
| Shared harness | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |

Harness 在 baseline API 上编译，运行时分别只加载显式的 baseline/candidate Core JAR。执行前工作区
干净，四轮使用独立 JVM/JMH fork，顺序为 A1 baseline、B1 candidate、B2 candidate、A2 baseline。

Artifact SHA-256：

```text
baseline-core.jar     1e8327bf2172c434b66ced8d7d9aa524d181cd59d5f71444959283d7664ac08b
candidate-core.jar    3e46aaa9cd713c8d64d57b069fd481c527dbdc896160701f554c3e5308dfe04b
benchmarks-harness.jar 4de98c0ef99f59b7c2e7f9218ec7ea25d6f49eb29d1eadd82f3c5b81fbf88057
```

## Runtime

| item | value |
| --- | --- |
| UTC start | `2026-09-05T15:45:56Z` |
| Host | macOS 15.4.1, x86_64 |
| Colima | macOS Virtualization.framework, x86_64, Docker runtime, virtiofs |
| Docker client/server | 29.7.2 / 29.5.2 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |

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
target=redis-critical
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

原始环境采集包含本机 socket 路径和用户目录，未提交到仓库；本文件只保留复现和解释结果所需的
非敏感信息。
