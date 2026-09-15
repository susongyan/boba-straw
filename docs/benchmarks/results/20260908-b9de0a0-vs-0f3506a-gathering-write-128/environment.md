# Benchmark environment

## Revisions

| role | revision |
| --- | --- |
| Baseline Core (32 frames) | `b9de0a0b96abb055b2fa4d14783ffd216024321a` |
| Candidate Core (128 frames) | `0f3506afabca286ec5805ecf40a7d5e96bf04ad4` |
| Shared harness | `b9de0a0b96abb055b2fa4d14783ffd216024321a` |

Artifact SHA-256：

```text
baseline-core.jar      d03ebce56d9612e02d4c9d622608ec5c17ffd19882d5241451626708d7decc6b
candidate-core.jar     b09e54cb9eec5d907f2049589c6a448be94921d46e4856fa47eab413958ed158
benchmarks-harness.jar ce551f88628ad56dd0a3db6bf8dd7b7eaa4ed7ca2eb473f740ef29dd1b208898
```

## Runtime

| item | value |
| --- | --- |
| UTC start | `2026-09-08T01:17:21Z` |
| Host | macOS 15.4.1, x86_64 |
| Colima | macOS Virtualization.framework, x86_64, Docker runtime, virtiofs |
| Docker client/server | 29.7.2 / 29.5.2 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |
| Host load preflight | 7.03 / 8 logical CPUs = 0.879 per CPU; limit 1.50, passed |

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
target=redis-gathering-write
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
run order=A1 baseline, B1 candidate, B2 candidate, A2 baseline
```

原始环境采集包含本机 socket 路径和用户目录，未提交到仓库；本文件只保留复现和解释结果所需的
非敏感信息。
