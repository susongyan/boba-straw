---
name: boba-straw-development
description: Implement or change Boba Straw Redis client code, especially RESP decoding, NIO transport, commands, and topology behavior.
---

# Boba Straw development

Read `AGENTS.md` and `docs/architecture/decisions.md` before changing protocol or transport code.

- Keep core code compatible with Java 8 and JDK-only at runtime.
- Model RESP3 additions in the shared value model; do not add a separate parser.
- Preserve FIFO reply matching. Test byte-by-byte and multi-reply input when changing decoding.
- Do not hide ambiguous command execution behind retries.
- State the supported topology honestly in public documentation and tests.

Run `mvn test` after changes.
