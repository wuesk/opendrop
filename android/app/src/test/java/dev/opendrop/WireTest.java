package dev.opendrop;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class WireTest {
    private JSONObject offer(JSONArray files) throws Exception {
        return Wire.object("protocol", "opendrop", "version", 1, "type", "offer",
                "sender", "Test computer", "files", files);
    }

    @Test public void framesUseBigEndianUtf8() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Wire.write(new DataOutputStream(bytes), Wire.object("name", "żółw 🐢.txt"));
        byte[] raw = bytes.toByteArray();
        assertEquals(0, raw[0]);
        assertEquals(raw.length - 4, new DataInputStream(new ByteArrayInputStream(raw)).readInt());
        assertEquals("żółw 🐢.txt", Wire.read(new DataInputStream(new ByteArrayInputStream(raw))).getString("name"));
    }

    @Test public void parsesMultipleAndEmptyFiles() throws Exception {
        JSONArray files = new JSONArray().put(Wire.object("name", "photo.jpg", "size", 42))
                .put(Wire.object("name", "empty", "size", 0));
        assertEquals(2, Wire.parseOffer(offer(files)).size());
        assertEquals(0, Wire.parseOffer(offer(files)).get(1).size);
    }

    @Test public void rejectsUnsafeNames() {
        for (String name : new String[]{"", ".", "..", "../escape", "a/b", "a\\b", "bad\nname", "x".repeat(241)})
            assertFalse(name, Wire.validName(name));
        assertTrue(Wire.validName("żółw 🐢.txt"));
    }

    @Test public void rejectsInvalidFileSizesAndCounts() throws Exception {
        for (Object size : new Object[]{-1, 0.5, "2", Wire.MAX_BYTES + 1}) {
            JSONObject invalid = offer(new JSONArray().put(Wire.object("name", "x", "size", size)));
            assertThrows(IOException.class, () -> Wire.parseOffer(invalid));
        }
        assertThrows(IOException.class, () -> Wire.parseOffer(offer(new JSONArray())));
        JSONArray many = new JSONArray();
        for (int i = 0; i <= Wire.MAX_FILES; i++) many.put(Wire.object("name", "x", "size", 0));
        assertThrows(IOException.class, () -> Wire.parseOffer(offer(many)));
        JSONArray overflow = new JSONArray().put(Wire.object("name", "x", "size", Wire.MAX_BYTES))
                .put(Wire.object("name", "y", "size", 1));
        assertThrows(IOException.class, () -> Wire.parseOffer(offer(overflow)));
    }

    @Test public void rejectsOversizedAndTruncatedHeaders() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new DataOutputStream(bytes).writeInt(Wire.MAX_HEADER + 1);
        assertThrows(IOException.class, () -> Wire.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertThrows(IOException.class, () -> Wire.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0}))));
    }

    @Test public void rejectionMustNotBeTreatedAsSuccess() throws Exception {
        Wire.requireOk(Wire.object("ok", true));
        assertThrows(IOException.class, () -> Wire.requireOk(Wire.object("ok", false, "error", "Transfer declined")));
        assertThrows(IOException.class, () -> Wire.requireOk(Wire.object("ok", "true")));
    }
}
