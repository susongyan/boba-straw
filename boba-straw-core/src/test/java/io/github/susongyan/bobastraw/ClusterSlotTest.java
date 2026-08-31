package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ClusterSlotTest {
    @Test
    void usesHashTagForRelatedKeys() {
        assertEquals(ClusterSlot.of("{user}:name"), ClusterSlot.of("{user}:profile"));
        assertNotEquals(ClusterSlot.of("user:1"), ClusterSlot.of("user:2"));
    }
}
