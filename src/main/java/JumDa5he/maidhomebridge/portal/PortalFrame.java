package JumDa5he.maidhomebridge.portal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.internal.Streams;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;

public record PortalFrame(JsonObject header, byte[] body) {
    public static final int MAX_HEADER = 256 * 1024;
    public static final int MAX_BODY = 256 * 1024 * 1024;

    public String op() throws IOException { return string(header, "op"); }

    public static String string(JsonObject json, String key) throws IOException {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString())
            throw new IOException("协议字段必须是字符串: " + key);
        return json.get(key).getAsString();
    }

    public static long integer(JsonObject json, String key, long fallback, long max) throws IOException {
        if (!json.has(key)) return fallback;
        try {
            if (!json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isNumber()) throw new ArithmeticException();
            long value = json.get(key).getAsBigDecimal().longValueExact();
            if (value < 0 || value > max) throw new ArithmeticException();
            return value;
        } catch (RuntimeException e) { throw new IOException("协议整数越界: " + key, e); }
    }

    public static PortalFrame read(InputStream source) throws IOException {
        DataInputStream in = new DataInputStream(source);
        long size = Integer.toUnsignedLong(in.readInt());
        if (size == 0 || size > MAX_HEADER) throw new IOException("Header 长度越界: " + size);
        byte[] bytes = new byte[(int) size];
        in.readFully(bytes);
        JsonObject json;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.startsWith("\uFEFF")) throw new IOException("Header 不允许 UTF-8 BOM");
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            json = Streams.parse(reader).getAsJsonObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Header 包含多余 JSON 数据");
        } catch (RuntimeException | CharacterCodingException e) { throw new IOException("无效 UTF-8/JSON Header", e); }
        String op = string(json, "op");
        if (op.isBlank()) throw new IOException("空 op");
        int bodySize = (int) integer(json, "body", 0, MAX_BODY);
        byte[] body = new byte[bodySize];
        in.readFully(body);
        return new PortalFrame(json, body);
    }

    public static void write(OutputStream target, JsonObject header, byte[] body) throws IOException {
        string(header, "op");
        byte[] bytes = header.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_HEADER) throw new IOException("Header 超限");
        if (body.length > MAX_BODY || integer(header, "body", 0, MAX_BODY) != body.length)
            throw new IOException("body 长度不匹配");
        DataOutputStream out = new DataOutputStream(target);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.write(body);
        out.flush();
    }
}
