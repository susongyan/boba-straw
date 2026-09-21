---
name: boba-straw-review
description: Review Boba Straw changes for protocol correctness, connection lifecycle safety, Java 8 compatibility, and Redis command semantics.
---

# Boba Straw review

For Redis command changes, also use the
[command acceptance checklist](../boba-straw-command-development/references/acceptance-checklist.md).
Review-only requests do not authorize code changes.

Review changed code for these high-risk failures:

- A Push or Attribute reply consuming a normal pending command.
- Partial reads, partial writes, or disconnects leaving futures unresolved.
- Socket, selector, executor, or subscription leaks.
- Automatic retry of a command with uncertain execution state.
- Java 9+ API use in runtime modules.
- Publicly claiming Sentinel, Cluster, TLS, or Pub/Sub support without executable coverage.

Report findings with the affected file, behavior, impact, and a concrete safe fix.
