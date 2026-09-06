# Benchmark environment

| item | value |
| --- | --- |
| Revision | `9b3f1164f348efaacec1975c7505b49be4e62673` |
| UTC start | `2026-09-06T11:22:46Z` |
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
| Server | Redis 7.4.2, standalone |
| Image | `redis:7.4.2@sha256:fbdbaea47b9ae4ecc2082ecdb4e1cea81e32176ffb1dcf643d422ad07427e5d9` |
| Container limit | 2 CPU, 2 GiB |
| Persistence | RDB/AOF disabled |
| Endpoint | loopback port 17379 mapped to container 6379 |

## JMH options

```text
profile=full
target=redis
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
```

Artifact SHA-256：

```text
boba-straw-core.jar 4e6464c3719d737a42300e8e90e3dbc80e3009846e5b920988519d94df688bf1
benchmarks.jar      198384546de8b2d9e3bfb4a1b1da5cac958abbe00ad19a1855e66f0ac873c479
```

原始环境采集中的用户目录和本机 socket 路径未提交。
