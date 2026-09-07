# Benchmark result archives

每个正式 run 使用一个不可复写的子目录，并同时保存环境清单、原始 JMH JSON 与人工摘要。
提交结果前确认没有凭据、用户目录或其他敏感环境变量；不要只提交整理后的表格。

| run | scope | status |
| --- | --- | --- |
| [`20260905-ca078f4-vs-7a2fe41-redis-critical`](20260905-ca078f4-vs-7a2fe41-redis-critical/summary.md) | Redis 7.4.2 critical ABBA | completed |
| [`20260905-ca078f4-vs-7a2fe41-codec`](20260905-ca078f4-vs-7a2fe41-codec/summary.md) | RESP Codec ABBA | completed; initial encode observation superseded |
| [`20260906-7a2fe41-vs-da546da-codec-encode`](20260906-7a2fe41-vs-da546da-codec-encode/summary.md) | Exact-size RESP encoder ABBA | completed |
| [`20260906-0cbf813-valkey-full`](20260906-0cbf813-valkey-full/summary.md) | Valkey 8.1.3 full network baseline | completed |
| [`20260906-b9ceff7-valkey-binary-large`](20260906-b9ceff7-valkey-binary-large/summary.md) | Valkey 8.1.3 `byte[]` large-value baseline | completed |
| [`20260906-9b3f116-redis-full`](20260906-9b3f116-redis-full/summary.md) | Redis 7.4.2 full network baseline | completed |
| [`20260907-9dfa609-redis-observe`](20260907-9dfa609-redis-observe/summary.md) | Redis 7.4.2 transport and system observation | completed |
