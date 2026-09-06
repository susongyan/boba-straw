# Benchmark environment

| item | value |
| --- | --- |
| Revision | `0cbf813c15556458f94744694bb3ca9735bc3af6` |
| UTC start | `2026-09-06T03:36:17Z` |
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
target=valkey
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

Artifact SHA-256：

```text
boba-straw-core.jar 7a57df7b8a8410d64ead07b2990f64975f1b5e6db226e2bde9b9c97050a26ef3
benchmarks.jar      3c914308962604e226c096f507afdbb6126a33f6935e9ebfcd3089613447af1c
```

原始环境采集中的用户目录和本机 socket 路径未提交。
