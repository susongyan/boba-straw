# Benchmark environment

| item | value |
| --- | --- |
| Revision | `9dfa609d72b9fb006710c0aeec03ccd184b5ff12` |
| UTC start | `2026-09-07T01:42:46Z` |
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

## JMH and sampling options

```text
profile=full
target=redis-observe
-wi 5 -w 2s -i 8 -r 2s -f 3 -t 1 -prof gc
system sampler=actual JMH fork ps + docker stats + container eth0 counters
```

Artifact SHA-256：

```text
boba-straw-core.jar 91f74c63b960135eccffa6bd86dbc847293c22f3ba45f6a614909aa69909a22e
benchmarks.jar      9485b4f6f04d7bf019642bb2020dbf582df1c3cb1f26bdbd7a9f6164b2eb2f04
```

原始环境采集中的用户目录和本机 socket 路径未提交。
