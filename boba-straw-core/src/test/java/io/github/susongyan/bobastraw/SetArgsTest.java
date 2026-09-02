package io.github.susongyan.bobastraw;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SetArgsTest {
    @Test
    void buildsConditionalExpiryArguments() {
        assertArrayEquals(
            new String[] { "NX", "EX", "30" },
            SetArgs.nx().ex(30).arguments()
        );
    }

    @Test
    void rejectsTtlCombinationsRedisDoesNotAllow() {
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                SetArgs.none().ex(10).keepTtl();
            }
        });
    }
}
