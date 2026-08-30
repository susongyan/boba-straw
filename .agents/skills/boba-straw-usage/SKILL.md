---
name: boba-straw-usage
description: Help application teams integrate Boba Straw correctly without adding reactive libraries or hiding Redis failure semantics.
---

# Boba Straw usage

Use the synchronous API for ordinary blocking application code and the `CompletionStage` API for asynchronous composition. Do not require Reactor, RxJava, or WebFlux.

- Share a `BobaClient` for the application lifecycle and close it during shutdown.
- Treat timeouts and connection failures after a write as potentially executed operations.
- Use explicit key hash tags for multi-key Cluster operations once Cluster support is enabled.
- Do not assume a capability is available until its published version documents it as supported.
