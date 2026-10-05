# Binary RESP 间歇错误诊断

2026-09-28，源码基线 `ee7e81a`。跟踪 Java 8 全量矩阵中一次
BinaryStringCommandsTest.preservesMissingEmptyDuplicateAndNonUtf8ResultsInBothProtocols
收到 `Unsupported RESP marker: H` 的错误。下面早期记录保留当时结论；
2026-10-06 已复现并修复一条确定的测试端口冲突路径，见文末，不将所有历史失败笼统归为同因。

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

## 第二轮：合成测试字节取证

2026-09-28，推送基线 `2757079` 后，仅增强测试：

- `BinaryFailureEvidence` 保留提交前的物理连接引用，避免后台重连后读取到另一连接。
  记录合成服务端尝试写出的最多 256 字节和成功完成 write 的字节数。
- 解码器已进入协议失败状态时，记录其读写游标、最多 64 字节的输入窗口，以及
  socket 读缓冲最后一片的长度和最多 64 字节前缀；非终止状态不读取这些可变缓冲。
  这不是抓包：write 成功不等于对端已收到，最后一次 read 也不代表全部 TCP 数据。
- 故意发送 `H\r\n` 的独立用例验证证据采集和“可能已执行”的失败传播。
  TCP 可分片，解码器可能只收到 H 就失败，因此不能要求失败窗口一定包含后续 CRLF。
  此用例验证取证工具，**不是历史偶发错误的复现或根因证明**。
- 原场景保留旧测试名以便关联历史报告，注明它只测两种 Null 编码，并非两次协议协商。
  新增独立 RESP2 / AUTO（HELLO 3）协商用例，回复与协商协议匹配的 Null。
- 不增加生产日志、不采集业务字节、不改变解码器、超时或重试策略。

JDK 8u202 定向测试 25 项通过；原 socket 场景运行 1,000 轮、2,000 条独立连接，
没有复现历史 H。证据 `/private/tmp/boba-straw-h-evidence-7PAYZ1`。
这些结果仍不能关闭历史根因待办。

根目录全模块 `mvn clean test` full 矩阵：JDK 8u202 / 21.0.7 各 149 tests，
0 failures/errors/skipped，包含 Standalone 兼容、Cluster 和 Sentinel。
证据 `$TMPDIR/boba-straw-compatibility-O6qBlE`，保留原始源码快照与报告。
矩阵后仅将注入 H 用例的窗口断言从完整 `480d0a` 调整为前缀 `48`，避免 TCP 分片
造成取证测试自身误报；最终断言以 JDK 8 的 1,000 轮定向测试、JDK 21 的默认轮数
定向测试再次通过验证，两组各 25 tests、0 failures/errors/skipped。

## 保留的待办

### 2026-09-29 新增回归线索

Lua L1（`8c1e707` + 工作树）根目录 full 验证中，JDK 8u202 再次出现 H，
但位于 `SentinelLifecycleTest.periodicDiscoveryChangesPrimaryWithoutBusinessTraffic` 第 148 行的 bootstrap。
这仍是本机 Java Peer 模拟测试，不是 Colima Redis；失败链为 Sentinel bootstrap → discovery →
可能已执行 → RespCodec.decodeStep 的 Unsupported RESP marker: H。
证据 `$TMPDIR/boba-straw-compatibility-pJst4q/run-1/maven.log` 和该目录的 surefire-reports，
163 tests / 1 error；最终 core/src 与该轮源码快照一致。

原 BinaryFailureEvidence 仅安装于 BinaryStringCommandsTest，因此本次 Sentinel 异常
没有收发字节快照。不能据此断言原 Binary 测试正文生成有误，也不能把两个测试出现同一标记
直接认定为同一根因；下一步需将有限取证扩展到 Sentinel 模拟发现链路。
Lua L1 不改 RespCodec、NioConnection 或 Sentinel 发现内核，此事实不是排除其他客户端缺陷的证明。

同批首轮 JDK 8 在事务超时测试等待 3 秒仍未完成（1 failure），JDK 21 全 163 tests 通过；
首轮证据 `$TMPDIR/boba-straw-compatibility-yZ8dOn`。事务超时未定位，不能直接归因于主机负载，
也不能将第二轮事务测试通过作为修复。两轮新增 Lua 专项及真实拓扑 Lua 用例均通过。

再次发生时先检查新增端点、服务端状态和有限字节快照，并保留异常与原始测试报告。
如需收包，只针对该合成测试端口做有限采集，不能采集其他应用/生产 Redis 流量。
获得非法字节来自 wire 或 parser 的证据后，再分别定位网络路径或构造确定性协议回归。
没有复现证据前不放宽 decoder 校验、修改响应内容或将异常转成成功。

## 2026-10-06：定位测试端口被 HTTP 监听器接收

C6 最终回归中，ScriptRegistryTest.binaryInputsAreSnapshotsAndNullSuccessIsCached 等待 EVAL
超时。增加合成夹具诊断后复现：Future 的失败链为“可能已执行 → Unsupported RESP marker: H”，
但模拟服务端 accepted=0、peerBytes=0、readFailure=null。当前共享连接已经重连到 READY，
因此仅看 metrics 的当前连接字节数不能解释前一条失败连接。

现场对应端点：Java ServerSocket 绑定 `0.0.0.0:58919`，客户端访问 `127.0.0.1:58919`。
只读进程检查显示该 loopback 端点已有 VS Code `Code Helper (Plugin)` 监听；HEAD 返回 HTTP。
未关闭、修改或重启该进程。随后在这个已知端口做一次有界、无副作用 RESP PING 复现：

```text
wildcard=0.0.0.0:58919, reuse=true
replyFirstLine=HTTP/1.1 400 Bad Request
wildcardAccepted=false
exclusiveBindingRejectsCollision=true
```

这解释了一个具体的 H 来源：本机 wildcard 监听与更具体的 loopback 监听共存，请求实际进入 HTTP
服务，客户端正确拒绝响应开头的 H。**H 不是 Redis 错误码，也不需要放宽 RESP decoder。**
证据为 `/private/tmp/boba-c6-script-diagnostic-sOh4DC` 的异常链及端点报告，
确定性复现代码在 `/private/tmp/boba-wildcard-proof-D5nBJV/WildcardProof.java`，执行输出如上。
前一轮 `/private/tmp/boba-c6-script-diagnostic-3vZB88` 只记录到未 accept/未收到 EVAL；
随后增加有限字节快照的 `/private/tmp/boba-c6-script-wire-Kk7ZiP` 1000 轮未复现，不伪造该轮收包证据。

修复仅作用于测试：新增 LoopbackTestServer，创建未绑定 ServerSocket，先关闭 SO_REUSEADDR，
再显式绑定 `127.0.0.1:0`；全部 21 处随机模拟监听/端口预留统一使用它。
客户端与服务器访问同一具体地址，避免通配监听把端口冲突隐藏到协议层；实际不可用端口仍失败，
不重试业务、不跳过测试、不加大超时、不修改生产网络和解析代码。
新增独占绑定断言；原 Lua 用例可用 `boba.straw.scriptSnapshotRepetitions=1000` 有界重复，
默认一轮，首个失败立即结束。失败诊断只包含合成数据和本地测试连接，不用于业务实例。

验证结果随 [C6 最终记录](../implementation/core-completion-plan.md)登记。
历史 Binary/Sentinel 报告没有同样完整的端点证据，不能逐次追认都是 VS Code 冲突；
其他超时、事务等待异常也不能由此自动关闭。若再次出现，继续保留原断言及物理连接字节取证。
