package JumDa5he.maidhomebridge.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 可移植的 TLM AI 聊天快照；压缩内容不包含任何站点密钥。 */
public final class MaidAiPortable {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_UNCOMPRESSED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_MESSAGES = 1024;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MaidAiPortable() {}

    public static byte[] exportGzip(EntityMaid maid) throws IOException {
        MaidAIChatManager manager = maid.getAiChatManager();
        String language = StringUtils.defaultIfBlank(manager.getChatLanguage(), "en_us");
        String custom = StringUtils.defaultString(manager.customSetting);
        String resolved = "";
        String personaSource = "missing";
        if (StringUtils.isNotBlank(custom)) {
            resolved = PapiReplacer.replaceSetting(custom, maid, language);
            personaSource = "custom";
        } else {
            var setting = manager.getSetting();
            if (setting.isPresent()) {
                resolved = setting.get().getSetting(maid, language);
                personaSource = "model";
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", SCHEMA_VERSION);
        root.addProperty("maid_uuid", maid.getUUID().toString());
        root.addProperty("history_summary", StringUtils.defaultString(manager.getCompressedSummary()));
        root.addProperty("last_token_usage", manager.getLastChatTokenUsage());

        JsonObject profile = new JsonObject();
        profile.addProperty("maid_name", maid.getName().getString());
        profile.addProperty("owner_name", StringUtils.defaultString(manager.ownerName));
        profile.addProperty("model_id", maid.getModelId());
        profile.addProperty("chat_language", language);
        profile.addProperty("custom_setting", custom);
        profile.addProperty("resolved_system_prompt", StringUtils.defaultString(resolved));
        profile.addProperty("generated_persona", "");
        profile.addProperty("persona_source", personaSource);
        root.add("profile", profile);

        JsonObject llm = new JsonObject();
        llm.addProperty("site", manager.llmSite);
        llm.addProperty("model", manager.llmModel);
        root.add("llm_ref", llm);

        JsonArray messages = new JsonArray();
        Iterator<LLMMessage> iterator = manager.getHistory().getDeque().descendingIterator();
        int index = 0;
        while (iterator.hasNext() && index < MAX_MESSAGES) {
            LLMMessage message = iterator.next();
            JsonObject item = new JsonObject();
            String role = message.role().getId();
            String text = StringUtils.defaultString(message.message());
            item.addProperty("id", "mc-" + index + "-" + shortHash(role + "\n" + message.gameTime() + "\n" + text));
            item.addProperty("request_id", "");
            item.addProperty("role", role);
            item.addProperty("text", text);
            item.addProperty("raw_text", text);
            item.addProperty("tts_text", "");
            item.addProperty("game_time", message.gameTime());
            item.addProperty("origin", "mc");
            item.addProperty("status", "complete");
            item.addProperty("tool_call_id", StringUtils.defaultString(message.toolCallId()));
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                item.add("tool_calls", GSON.toJsonTree(message.toolCalls()));
            }
            messages.add(item);
            index++;
        }
        root.addProperty("base_message_count", messages.size());
        root.add("messages", messages);
        return gzip(GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    public static void validate(byte[] gzip) throws IOException {
        JsonObject root = read(gzip);
        int version = integer(root, "schema_version", -1);
        if (version != SCHEMA_VERSION) throw new IOException("不支持 AI 聊天数据版本: " + version);
        JsonElement messages = root.get("messages");
        if (messages == null || !messages.isJsonArray() || messages.getAsJsonArray().size() > MAX_MESSAGES)
            throw new IOException("AI 聊天消息数量无效");
        JsonElement base = root.get("base_message_count");
        if (base == null || !base.isJsonPrimitive() || !base.getAsJsonPrimitive().isNumber())
            throw new IOException("AI 聊天缺少 base_message_count");
        int baseCount = base.getAsInt();
        if (baseCount < 0 || baseCount > messages.getAsJsonArray().size())
            throw new IOException("AI 聊天 base_message_count 越界");
    }

    /** 只追加手机端消息；MC 原有消息已经在 maid_data.maid 里。 */
    public static int merge(EntityMaid maid, byte[] gzip) throws IOException {
        validate(gzip);
        JsonObject root = read(gzip);
        JsonArray incoming = root.getAsJsonArray("messages");
        MaidAIChatManager manager = maid.getAiChatManager();
        JsonObject profile = root.has("profile") && root.get("profile").isJsonObject()
                ? root.getAsJsonObject("profile") : new JsonObject();
        String generatedPersona = text(profile, "generated_persona");
        if (StringUtils.isNotBlank(generatedPersona)
                && StringUtils.isBlank(manager.customSetting)
                && manager.getSetting().isEmpty()) {
            manager.customSetting = generatedPersona;
        }

        Map<String, Integer> existing = new HashMap<>();
        for (LLMMessage message : manager.getHistory().getDeque()) {
            existing.merge(canonical(message.role().getId(), message.message()), 1, Integer::sum);
        }

        Map<String, Integer> seen = new HashMap<>();
        int added = 0;
        for (JsonElement element : incoming) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (!"phone".equals(text(item, "origin")) || !"complete".equals(text(item, "status"))) continue;
            String roleId = text(item, "role");
            if (!roleId.equals("user") && !roleId.equals("assistant")) continue;
            String body = text(item, "raw_text");
            if (body.isBlank()) body = text(item, "text");
            if (body.isBlank()) continue;

            String key = canonical(roleId, body);
            int occurrence = seen.merge(key, 1, Integer::sum);
            int already = existing.getOrDefault(key, 0);
            if (occurrence <= already) continue;

            long gameTime = 0L;
            JsonElement time = item.get("game_time");
            if (time != null && time.isJsonPrimitive() && time.getAsJsonPrimitive().isNumber()) {
                gameTime = time.getAsLong();
            }
            manager.getHistory().add(new LLMMessage(Role.byId(roleId), body, gameTime));
            existing.merge(key, 1, Integer::sum);
            added++;
        }
        return added;
    }

    public static byte[] gzip(byte[] data) throws IOException {
        if (data.length > MAX_UNCOMPRESSED_BYTES) throw new IOException("AI 聊天数据超过 2 MiB");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    public static byte[] gunzip(byte[] data) throws IOException {
        if (data.length == 0) throw new IOException("AI 聊天压缩包为空");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            int read;
            while ((read = gzip.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_UNCOMPRESSED_BYTES) throw new IOException("AI 聊天数据解压后超过 2 MiB");
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    private static JsonObject read(byte[] gzip) throws IOException {
        try {
            return JsonParser.parseString(new String(gunzip(gzip), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("AI 聊天 JSON 无效", e);
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
        return value.getAsInt();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        return value.getAsString();
    }

    private static String canonical(String role, String text) {
        return role + "\u0000" + StringUtils.defaultString(text);
    }

    private static String shortHash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(12);
            for (int i = 0; i < 6; i++) out.append(String.format("%02x", digest[i] & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
