package dev.opendrop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Wire {
    static final int DISCOVERY_PORT = 45454;
    static final int TRANSFER_PORT = 45455;
    static final String GROUP = "239.255.77.77";
    static final int CHUNK = 65536;
    static final int MAX_HEADER = 262144;
    static final int MAX_FILES = 500;
    static final long MAX_BYTES = 1099511627776L;

    static final class FileInfo {
        final String name;
        final long size;
        FileInfo(String name, long size) { this.name = name; this.size = size; }
    }

    static boolean validName(String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.getBytes(StandardCharsets.UTF_8).length > 240) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || c == '/' || c == '\\') return false;
        }
        return true;
    }

    static JSONObject object(Object... pairs) throws JSONException {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static JSONObject read(DataInputStream input) throws IOException, JSONException {
        int size = input.readInt();
        if (size < 1 || size > MAX_HEADER) throw new IOException("Invalid message length");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    static void write(DataOutputStream output, JSONObject object) throws IOException {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HEADER) throw new IOException("Message is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
        output.flush();
    }

    static void requireOk(JSONObject reply) throws IOException {
        if (!Boolean.TRUE.equals(reply.opt("ok")))
            throw new IOException(reply.optString("error", "Transfer declined"));
    }

    static List<FileInfo> parseOffer(JSONObject offer) throws IOException, JSONException {
        JSONArray array = offer.optJSONArray("files");
        if (!"opendrop".equals(offer.optString("protocol")) || offer.optInt("version") != 1 ||
                !"offer".equals(offer.optString("type")) || !(offer.opt("sender") instanceof String) ||
                offer.getString("sender").isEmpty() || offer.getString("sender").length() > 80 ||
                array == null || array.length() < 1 || array.length() > MAX_FILES)
            throw new IOException("Invalid transfer offer");
        List<FileInfo> result = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject file = array.getJSONObject(i);
            Object number = file.opt("size");
            double size = number instanceof Number ? ((Number) number).doubleValue() : -1;
            if (!(file.opt("name") instanceof String) || !validName(file.getString("name")) ||
                    !Double.isFinite(size) || size < 0 || size > MAX_BYTES || size != Math.floor(size) ||
                    total > MAX_BYTES - (long) size)
                throw new IOException("Invalid filename or file size");
            total += (long) size;
            result.add(new FileInfo(file.getString("name"), (long) size));
        }
        return result;
    }
}
