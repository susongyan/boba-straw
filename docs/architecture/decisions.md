# Architecture decisions

## Runtime baseline

`boba-straw-core` targets Java 8 and has no third-party runtime dependency. Java NIO is the only transport foundation. Java 21 virtual threads may call the blocking facade but are not part of the public baseline.

## Protocol

The decoder has one RESP value model. RESP2 is a subset; RESP3 Push, Attribute, Blob Error, Verbatim String and Big Number values are parsed. Attribute values are unwrapped only after they have been kept separate from Push messages, so they cannot shift normal request-response FIFO matching.

## Failure semantics

The client does not automatically retry commands. A timeout or disconnect after a write may mean Redis executed the command; callers must not treat it as a safe negative acknowledgement.

## Current delivery boundary

The initial implementation is standalone only. Public topology promises must not be documented as supported until Sentinel and Cluster routing are implemented and tested.
