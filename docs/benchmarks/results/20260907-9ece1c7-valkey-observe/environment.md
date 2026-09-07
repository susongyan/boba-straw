# Benchmark environment

| item | value |
| --- | --- |
| Revision | `9ece1c70b3e3d80efb111239bc86820bb81ec496` |
| UTC start | `2026-09-07T17:33:44Z` |
| Host | macOS 15.4.1, x86_64 |
| Colima | macOS Virtualization.framework, x86_64, Docker runtime, virtiofs |
| Docker client/server | 29.7.2 / 29.5.2 |
| Java | Oracle JDK 21.0.7 LTS, HotSpot 64-Bit Server VM |
| Maven | 3.9.6 |
| JMH | 1.37 |
| Git worktree | clean before run |
| Host load preflight | 11.70 / 8 logical CPUs = 1.462 per CPU; limit 1.50, passed |

## Target

| item | value |
| --- | --- |
| Server | Valkey 8.1.3, standalone |
| Image | `valkey/valkey:8.1.3@sha256:fea8b3e67b15729d4bb70589eb03367bab9ad1ee89c876f54327fc7c6e618571` |
| Container limit | 2 CPU, 2 GiB |
| Persistence | RDB/AOF disabled |
| Endpoint | loopback port 17380 mapped to container 6379 |

## JMH and sampling options

```text
profile=full
target=valkey-observe
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
system sampler=actual JMH fork ps + docker stats + container eth0 counters
```

Artifact SHA-256：

```text
boba-straw-core.jar eaed1fa4c83f3cffe62cb0a2e43080b7524a2ec82d6ac2e89257481dda5c0167
benchmarks.jar      0fbf0eced3dd588390263be47714692fcd9e662f0939a695b22f1b13e4290dda
```

原始环境采集中的用户目录和本机 socket 路径未提交。
