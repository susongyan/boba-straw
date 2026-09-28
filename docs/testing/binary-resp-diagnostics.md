# Binary RESP 间歇错误诊断

2026-09-28，源码基线 `ee7e81a`。跟踪 Java 8 全量矩阵中一次
BinaryStringCommandsTest.preservesMissingEmptyDuplicateAndNonUtf8ResultsInBothProtocols
收到 `Unsupported RESP marker: H` 的错误。本记录不是“已修复”的结论。

## 原始证据

`$TMPDIR/boba-straw-compatibility-SfT9Hb/run-1/maven.log` 与
`run-1/boba-straw-core/surefire-reports` 保存原失败：141 tests 中 1 error。
同轮 Java 21 通过；Java 8 单独运行 BinaryStringCommandsTest 的 5 项通过。
这些结果不足以把错误归因于 Java 8、网络代理、测试服务端或协议实现。

该固定 MGET 场景的预期回复不含 ASCII H。现有日志只有解码异常，没有原始收包和
本地端点记录，因此不能判断异常字节实际从何处产生。尚无证据支持修改生产 decoder。

## 本轮补充

- RespCodecTest 增加两种 Null 格式下所有三段分片边界（含空片段）、两份非 UTF-8
  payload、空字符串以及后续整数响应的组合验证；每次 feed 后覆写输入数组，检查 buffer ownership。
- BinaryStringCommandsTest 增加 `boba.straw.binaryDiagnosticRepetitions`，默认 1，范围 1–1000。
  每轮两种 Null 回复分别使用新连接；失败立即退出，不重试失败命令，不改变原超时与断言。
- socket 场景失败时附加测试监听地址、已接受连接及 peer、场景完成/异常状态。
  只改测试，不记录业务数据、不加入运行时日志、生产线程或自动重试。

定向复跑（在隔离源码副本内）：

```sh
mvn -pl boba-straw-core -Dtest=BinaryStringCommandsTest,RespCodecTest \
    -Dboba.straw.binaryDiagnosticRepetitions=200 test
```

Oracle JDK 8u202：21 tests 全通过，其中原 socket 场景运行 200 轮、400 条独立连接；
没有重现 H。证据 `/private/tmp/boba-straw-binary-diagnostics-HXpwB5`。
这不是吞吐压测或跨平台长稳测试，不能用重复通过替代根因修复。

随后执行一次隔离的根目录全模块 full 矩阵：Oracle JDK 8u202 和 21.0.7 各
142 tests、0 failures/errors/skipped，四个 Standalone 服务端及 Cluster/Sentinel 回归全部通过。
证据 `$TMPDIR/boba-straw-compatibility-2lkDyg/run-1`、`run-2`；core/src 与验证源码快照一致。
本轮只新增测试，生产代码未改；这恢复了当前版本的全量通过记录，但不关闭原始间歇错误。
未验证其他 JDK/OS 或长稳，未运行独立 Agent。临时日志不是永久发布归档。

## 保留的待办

再次发生时先检查新增端点/服务端状态并保留异常与原始测试报告。
如需收包，只针对该合成测试端口做有限采集，不能采集其他应用/生产 Redis 流量。
获得非法字节来自 wire 或 parser 的证据后，再分别定位网络路径或构造确定性协议回归。
没有复现证据前不放宽 decoder 校验、修改响应内容或将异常转成成功。
