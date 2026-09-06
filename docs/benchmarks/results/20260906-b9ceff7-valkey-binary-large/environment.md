# Benchmark environment

| item | value |
| --- | --- |
| Revision | `b9ceff714400aa91359f39f69904945628225cff` |
| UTC start | `2026-09-06T05:16:12Z` |
| Host | macOS 15.4.1, x86_64 |
| Colima | macOS Virtualization.framework, x86_64, Docker runtime, virtiofs |
| Docker client/server | 29.7.2 / 29.5.2 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |
| Git worktree | clean before run |

## Target

| item | value |
| --- | --- |
| Server | Valkey 8.1.3, standalone; Redis compatibility version 7.2.4 |
| Image | `valkey/valkey:8.1.3@sha256:fea8b3e67b15729d4bb70589eb03367bab9ad1ee89c876f54327fc7c6e618571` |
| Container limit | 2 CPU, 2 GiB |
| Persistence | RDB/AOF disabled |
| Endpoint | loopback port 17380 mapped to container 6379 |

## JMH options

```text
profile=full
target=valkey-binary-large
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

Artifact SHA-256：

```text
boba-straw-core.jar 5f8607aed1a3b10a6fdc4125f8dcc14090c0ee9bd3b1c053c4629b471e43a3ba
benchmarks.jar      7eb24f4b36e2609430f0d21abaf39fcb544160cb62948c579365df0ce6834bb0
```

原始环境采集中的用户目录和本机 socket 路径未提交。
