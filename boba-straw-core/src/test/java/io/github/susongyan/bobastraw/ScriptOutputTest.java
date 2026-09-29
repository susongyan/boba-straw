package io.github.susongyan.bobastraw;

import io.github.susongyan.bobastraw.protocol.RespValue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScriptOutputTest {
    @Test
    void scalarsPreserveNullEmptyAndStrictTypes() {
        assertNull(ScriptOutput.integer().decode(RespValue.Null.INSTANCE));
        assertNull(ScriptOutput.bytes().decode(new RespValue.BlobString(null)));
        assertNull(ScriptOutput.string().decode(RespValue.Null.INSTANCE));
        assertEquals(Long.valueOf(7), ScriptOutput.integer().decode(new RespValue.Number(7)));
        assertEquals("", ScriptOutput.string().decode(new RespValue.BlobString(new byte[0])));
        assertArrayEquals(new byte[0], ScriptOutput.bytes().decode(new RespValue.BlobString(new byte[0])));
        assertEquals("OK", ScriptOutput.string().decode(new RespValue.SimpleString("OK")));
        assertThrows(IllegalStateException.class,
            () -> ScriptOutput.integer().decode(new RespValue.DoubleValue(1)));
        assertThrows(IllegalStateException.class,
            () -> ScriptOutput.integer().decode(new RespValue.SimpleString("1")));
        assertThrows(IllegalStateException.class,
            () -> ScriptOutput.string().decode(new RespValue.BigNumber("123")));
        assertThrows(IllegalStateException.class,
            () -> ScriptOutput.bytes().decode(new RespValue.BooleanValue(false)));
    }

    @Test
    void binaryResultsDoNotExposeReplyArrays() {
        byte[] data = {(byte) 0xff, 0};
        RespValue value = new RespValue.BlobString(data);
        byte[] first = ScriptOutput.bytes().decode(value);
        first[0] = 1;
        assertArrayEquals(new byte[] {(byte) 0xff, 0}, data);
        assertArrayEquals(data, ScriptOutput.bytes().decode(value));
        byte[] verbatim = ScriptOutput.bytes().decode(new RespValue.VerbatimString("txt", data));
        assertNotSame(data, verbatim);
        assertArrayEquals(data, verbatim);
    }

    @Test
    void nestedListsAreTypedOrderedAndStructurallyComparable() {
        ScriptOutput<List<List<Long>>> output = ScriptOutput.list(ScriptOutput.list(ScriptOutput.integer()));
        RespValue inner = new RespValue.Array(Arrays.asList(new RespValue.Number(1), RespValue.Null.INSTANCE));
        List<List<Long>> result = output.decode(new RespValue.Array(Arrays.asList(inner, RespValue.Null.INSTANCE)));
        assertEquals(Arrays.asList(Arrays.asList(1L, null), null), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(null));
        assertThrows(UnsupportedOperationException.class, () -> result.get(0).add(3L));
        assertEquals(output, ScriptOutput.list(ScriptOutput.list(ScriptOutput.integer())));
        assertEquals(output.hashCode(), ScriptOutput.list(ScriptOutput.list(ScriptOutput.integer())).hashCode());
        assertNotEquals(output, ScriptOutput.list(ScriptOutput.list(ScriptOutput.string())));
        assertNull(output.decode(RespValue.Null.INSTANCE));
        assertTrue(output.decode(new RespValue.Array(Collections.emptyList())).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ScriptOutput.list(null));
        assertThrows(IllegalStateException.class,
            () -> output.decode(new RespValue.SetValue(Collections.emptyList())));
    }

    @Test
    void errorsAreNotConvertedToSuccessAndRawKeepsMixedStructure() {
        RespValue error = new RespValue.Error("ERR script failed");
        RespValue mixed = new RespValue.Array(Arrays.asList(new RespValue.Number(1), error));
        assertSame(mixed, ScriptOutput.raw().decode(mixed));
        assertThrows(BobaStrawServerException.class, () -> ScriptOutput.raw().decode(error));
        assertThrows(BobaStrawServerException.class, () -> ScriptOutput.list(ScriptOutput.integer()).decode(mixed));
        assertThrows(BobaStrawServerException.class,
            () -> ScriptOutput.raw().decode(new RespValue.BlobError(new byte[] {'E', 'R', 'R'})));
        RespValue.Attribute attribute = new RespValue.Attribute(new RespValue.MapValue(Collections.emptyMap()),
            new RespValue.Number(4));
        assertEquals(Long.valueOf(4), ScriptOutput.integer().decode(attribute));
    }
}
