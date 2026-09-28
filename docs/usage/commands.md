# 普通命令、批量与分页

适用 `0.1.0-SNAPSHOT` 的 C5 实现，使用前固定来源提交；以下示例仅使用公开 API、Java 8。
传入的 Client 由应用启动时创建并长期复用；这些方法不负责关闭它。
`join()` 示例用于允许阻塞的调用线程，不应放到异步回调线程等待另一条异步命令。

## 普通命令不需要 typed()

`client.sync().get(key)` 返回 String，`client.async().get(key)` 返回 CompletionStage<String>，
`client.binary().get(keyBytes)` 返回 CompletionStage<byte[]>。GET 缺失值为 null，空值不是 null，
失败仍是异常，不能将异常转换成“缓存不存在”。

binary 的 Key/value 不经过 UTF-8 转码。Hash 字段与值也是 byte[]，hgetall 返回 Entry 列表；
Set 返回字节列表，不用 Java 数组的引用相等冒充内容相等。分值、计数仍是 Double/Long 等类型。
调用返回后可复用输入数组，但调用过程中不要由其他线程并发修改参数。

## 可编译调用示例

将下方保存为 CommandExamples.java，在含核心依赖的 Java 8+ 项目中编译。
它不是自动运行的测试；运行写入方法前必须提供独立测试 Key，结束后只清理这些 Key。

```java
import io.github.susongyan.bobastraw.*;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class CommandExamples {
    public static String get(BobaStrawClient client, String key) {
        return client.sync().get(key);
    }

    public static CompletionStage<String> getAsync(BobaStrawClient client, String key) {
        return client.async().get(key);
    }

    public static CompletionStage<byte[]> getBytes(BobaStrawClient client, byte[] key) {
        return client.binary().get(key);
    }

    public static Map.Entry<String, Long> readBatch(BobaStrawClient client, String key) {
        BobaStrawPipeline batch = client.pipeline();
        BobaStrawCommandHandle<String> value = batch.typed().get(key);
        BobaStrawCommandHandle<Long> ttl = batch.typed().ttl(key);
        BobaStrawBatchResult result = batch.executeTyped().toCompletableFuture().join();
        // get(handle) may throw for that command; this method propagates the failure.
        return new AbstractMap.SimpleImmutableEntry<String, Long>(result.get(value), result.get(ttl));
    }

    public static long watchedIncrement(BobaStrawClient client, String key) {
        try (BobaStrawTransaction tx = client.transaction()) {
            tx.watch(key).toCompletableFuture().join();
            BobaStrawCommandHandle<Long> count = tx.typed().incr(key);
            BobaStrawBatchResult result = tx.execTyped().toCompletableFuture().join();
            if (result.isAborted()) {
                // Explicit conflict; let the application decide what to do, without automatic replay.
                throw new IllegalStateException("WATCH conflict: transaction was not executed");
            }
            return result.get(count);
        }
    }

    public static void visitSet(BobaStrawClient client, String key, int maxPages,
                               Consumer<String> visitor) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive");
        }
        String cursor = "0";
        for (int pageNumber = 0; pageNumber < maxPages; pageNumber++) {
            ScanPage<String> page = client.scan()
                .sscan(key, cursor, ScanArgs.none().count(100))
                .toCompletableFuture().join();
            for (String member : page.values()) {
                visitor.accept(member);
            }
            if (page.isFinished()) {
                return;
            }
            cursor = page.cursor();
        }
        throw new IllegalStateException("Application page budget exhausted; scan is incomplete");
    }
}
```

## Pipeline / 事务的句柄与失败

typed() 的调用只在本地入队，句柄属于原批次；executeTyped()/execTyped() 才提交。
不要将句柄当 Future，不跨批次读取，也不尝试单条取消。取消从提交方法最初返回的 Stage 发起，
只能结束客户端等待，不能证明 Redis 撤销了操作。

Pipeline 不是事务：读取值与 TTL 也不保证来自同一快照。批量 Stage 成功不代表每条命令成功；
`result.get(handle)` 遇到该条服务端错误会抛 BobaStrawServerException，其他位置仍可读取。
示例 readBatch 选择将错误交给调用方；如业务需要收集全部结果，应分别处理各句柄，不能把错误填成 null。
网络失败、超时等属于整批失败，部分写入可能已执行，不要整批盲目重试。

事务应先检查 isAborted()，再读取句柄。WATCH 冲突与成功的空事务可以明确区分；
EXEC 内单条错误不回滚其他命令。事务没有 WATCH 时无须为了使用 typed() 而增加 WATCH。
旧 Raw `exec()` 保留冲突返回空列表的兼容行为；新代码优先用 execTyped() 表达结果。
生命周期与专用连接规则见[生命周期](lifecycle.md)。

批量目录目前仅 Standalone String 的 16 个高频方法，不与全部 async() 方法一一对应；
不支持 binary 批量或 Cluster/Sentinel 批量。不要绕过限制手工发送 MULTI/EXEC。

## Scan 的边界

每次 scan/hscan/sscan/zscan 只请求一页；示例循环由应用显式驱动，并设置自己的页数预算。
预算耗尽表示未完成，不是假装扫描结束。游标原样传回，不转换成有符号 long。
空页不是结束，COUNT 是提示不是固定页大小；成员可能重复，并发修改时不提供一致性快照。
visitor 应能容忍重复，长扫描还应设置应用整体时间/取消预算。

Standalone/Sentinel 支持四种 String 异步扫描；Cluster 仅支持 HSCAN/SSCAN/ZSCAN，
不提供无节点绑定的全库 SCAN。拓扑切换不保证游标连续性，没有 binary Scan、自动 iterator 或 stream。

## 未封装命令

优先直接调用已有普通方法。Raw 只用于已核实语义的未封装普通命令，不是连接隔离后门。
Cluster 多 Key 必须同 Slot；未知命令需声明全部 Key。Raw 不应发送阻塞、订阅、认证或连接状态命令。
更多限制见[能力表](supported-features.md)，失败分类见[失败与重试](failures-and-retries.md)。

## 示例核验记录

2026-09-28，核心基线 `706e616`：本页 CommandExamples 与快速开始 QuickStart 均经 JDK 8u202
编译；在 Redis 7.4.2 上验证普通/二进制读取、Pipeline、事务成功路径和 Scan（RESP2/AUTO），
使用随机 Key 并定点清理。该示例检查不包含 WATCH 竞争、断连或全部服务端版本。
文档共 26 个本地链接检查通过。根 Maven 回归 147 项，0 failures/errors、22 项 opt-in 集成测试
未启用；报告 `$TMPDIR/boba-straw-compatibility-7OYGxm`。临时示例核验目录
`/private/tmp/boba-straw-guide-check-6zPIKG`，不是对外发布的示例模块。
