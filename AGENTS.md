# Boba Straw engineering rules

- Keep the core Java 8 compatible and JDK-only at runtime. Do not add Netty, Reactor, RxJava, Kotlin Coroutine, Spring, or WebFlux to `boba-straw-core`.
- Preserve command execution order on a physical connection. A decoder change must include fragmented-input tests.
- Do not add automatic retries. Any ambiguous network failure must remain observable to callers.
- Blocking commands, transactions, and Pub/Sub require dedicated connection handling.
- Public API changes require tests and compatibility review. Run `mvn test` before handoff.

## Code Review Rules

- Flag any response that can be matched to the wrong pending request.
- Flag commands that may cross Cluster slots without an explicit, documented policy.
- Flag resource ownership that can leak a socket, selector, subscription, or thread.
- Flag direct use of Java APIs introduced after Java 8 in runtime modules.
