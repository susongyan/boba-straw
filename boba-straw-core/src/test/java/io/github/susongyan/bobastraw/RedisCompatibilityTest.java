package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs against the local Redis/Valkey matrix started by scripts/redis-test-up.sh.
 * It is opt-in so normal unit-test runs do not require Docker.
 */
@EnabledIfSystemProperty(named = "boba.straw.runCompatibility", matches = "true")
class RedisCompatibilityTest {
    private static final String[] ENDPOINTS = {
        "127.0.0.1:16379",
        "127.0.0.1:16380",
        "127.0.0.1:16381",
        "127.0.0.1:16382"
    };

    @Test
    void autoNegotiatesAndExecutesBasicCommandsOnEveryServer() {
        for (String endpoint : ENDPOINTS) {
            assertBasicCommands(endpoint, ProtocolVersion.AUTO);
        }
    }

    @Test
    void explicitResp2ExecutesBasicCommandsOnEveryServer() {
        for (String endpoint : ENDPOINTS) {
            assertBasicCommands(endpoint, ProtocolVersion.RESP2);
        }
    }

    @Test
    void pipelineTransactionAndLuaWorkOnEveryServer() {
        for (String endpoint : ENDPOINTS) {
            String[] hostAndPort = endpoint.split(":");
            try (BobaStrawClient client = BobaStrawClient.builder()
                .endpoint(hostAndPort[0], Integer.parseInt(hostAndPort[1]))
                .protocol(ProtocolVersion.AUTO)
                .commandTimeout(Duration.ofSeconds(2))
                .build()) {
                String key = "boba-straw:state:" + endpoint;
                List<io.github.susongyan.bobastraw.protocol.RespValue> pipeline =
                    client.pipeline()
                        .command("SET", key, "one")
                        .command("GET", key)
                        .execute().toCompletableFuture().join();
                assertEquals(2, pipeline.size());
                assertEquals("one", pipeline.get(1).asString());

                List<io.github.susongyan.bobastraw.protocol.RespValue> transaction =
                    client.transaction()
                        .command("SET", key, "two")
                        .command("GET", key)
                        .exec().toCompletableFuture().join();
                assertEquals(2, transaction.size());
                assertEquals("two", transaction.get(1).asString());

                assertEquals(Long.valueOf(3), client.sync().eval(
                    "return tonumber(ARGV[1]) + tonumber(ARGV[2])",
                    new String[0], "1", "2"
                ).asLong());
                client.sync().del(key);
            }
        }
    }

    private void assertBasicCommands(String endpoint, ProtocolVersion protocol) {
        String[] hostAndPort = endpoint.split(":");
        String key = "boba-straw:compat:" + protocol.name().toLowerCase();

        try (BobaStrawClient client = BobaStrawClient.builder()
            .endpoint(hostAndPort[0], Integer.parseInt(hostAndPort[1]))
            .protocol(protocol)
            .commandTimeout(Duration.ofSeconds(2))
            .build()) {
            assertEquals("PONG", client.sync().ping(), endpoint + " " + protocol);
            assertEquals("OK", client.sync().set(key, "boba"), endpoint + " " + protocol);
            assertEquals("boba", client.sync().get(key), endpoint + " " + protocol);
            assertEquals(Long.valueOf(1), client.sync().del(key), endpoint + " " + protocol);
        }
    }
}
