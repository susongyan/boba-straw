# Benchmark environment

## Revisions

| role | revision |
| --- | --- |
| Baseline Core | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |
| Candidate Core | `da546da3f0fdb0b84323148b3c7e9d1539b32e95` |
| Shared harness | `7a2fe4173cbba4e6319785ec9ea516080a1137e2` |

执行顺序为 A1 baseline、B1 candidate、B2 candidate、A2 baseline。每轮使用独立 JVM/JMH fork；
harness 在 baseline API 上编译且不包含 Boba Straw Core class。

Artifact SHA-256：

```text
baseline-core.jar      b95c66ded8d1e6b63630fc1c95a42df17adade8f2215c547deae26294ae595eb
candidate-core.jar     c3cd076ff07772f528de7e8a28d76257334a753aa86d317f37e81ef3219a2ef7
benchmarks-harness.jar 7ea8e1994caf231e9b6f2bf733afb778525d30b9c491050ba99749991ec68b35
```

## Runtime

| item | value |
| --- | --- |
| UTC start | `2026-09-06T02:34:10Z` |
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

Codec workload 不访问 Redis/Valkey。原始环境采集中的用户目录和本机 socket 路径未提交。
