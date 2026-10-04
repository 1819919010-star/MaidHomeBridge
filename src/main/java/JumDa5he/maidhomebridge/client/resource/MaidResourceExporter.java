package JumDa5he.maidhomebridge.client.resource;

import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.portal.SafeFiles;
import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockModel;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.resource.GeckoModelLoader;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public final class MaidResourceExporter {
    private MaidResourceExporter() {}

    public record Snapshot(String modelId, String name, String ownerUuid, String ownerName,
                           String soundId, double soundFrequency, float scale, boolean simpleBedrock, boolean gecko,
                           ResourceLocation model, ResourceLocation texture, List<ResourceLocation> animations,
                           ResourceManager resources, Path packFolder, List<String> warnings,
                           MaidModelInfo expectedInfo, String customName) {}

    public static Snapshot capture(EntityMaid maid, BridgeNetwork.ExportResult data) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("模型快照必须在客户端主线程读取");
        if (maid.isYsmModel()) throw new IllegalArgumentException("不支持 YSM 模型");
        if (!maid.getUUID().equals(data.maidUuid()) || !maid.getModelId().equals(data.modelId()))
            throw new IllegalArgumentException("导出期间女仆模型已改变，请重新导出");
        var models = CustomPackLoader.MAID_MODELS;
        MaidModelInfo info = models.getInfo(maid.getModelId()).orElse(null);
        Object actualModel = models.getModel(maid.getModelId()).orElse(null);
        String customName = maid.hasCustomName() ? maid.getCustomName().getString() : "";
        if (maid.hasCustomName()) {
            String name = maid.getCustomName().getString();
            if (name.startsWith("=>")) throw new IllegalArgumentException("玩家皮肤动态模型无法导出为协议规定的基岩模型");
            var special = models.getEasterEggEncryptTagModel(DigestUtils.sha1Hex(name));
            if (special.isEmpty()) special = models.getEasterEggNormalTagModel(name);
            if (special.isPresent()) {
                info = special.get().getInfo();
                actualModel = special.get().getModel();
            }
        }
        if (info == null || info.getModel() == null || info.getTexture() == null
                || maid.getModelId().equals("touhou_little_maid:easter_egg_model"))
            throw new IllegalArgumentException("当前模型没有可导出的基岩几何与 PNG 资源：" + maid.getModelId());
        String soundId = data.soundId();
        if (!soundId.matches("[a-z0-9_-]{1,128}")) throw new IllegalArgumentException("音效包 ID 无法用于 Portal：" + soundId);
        double frequency = maid.getConfigManager().getSoundFreq();
        if (!Double.isFinite(frequency)) throw new IllegalArgumentException("女仆语音频率无效");
        return new Snapshot(data.modelId(), data.name(), data.ownerUuid() == null ? "" : data.ownerUuid().toString(),
                data.ownerName(), soundId, Math.clamp(frequency, 0, 1), info.getRenderEntityScale(),
                actualModel instanceof SimpleBedrockModel<?>, info.isGeckoModel(),
                info.getModel(), info.getTexture(), info.getAnimation() == null ? List.of() : List.copyOf(info.getAnimation()),
                Minecraft.getInstance().getResourceManager(), CustomPackLoader.PACK_FOLDER,
                info.isGeckoModel() || actualModel instanceof SimpleBedrockModel<?> ? List.of()
                        : List.of("普通 BedrockModel 的 JS/硬编码动画无法通过 Portal v1 传输；已导出真实几何与贴图，simple_bedrock_model=false，未生成 anim。"),
                info, customName);
    }

    public static void validate(Snapshot snapshot) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("模型复核必须在客户端主线程执行");
        var models = CustomPackLoader.MAID_MODELS;
        MaidModelInfo current = models.getInfo(snapshot.modelId()).orElse(null);
        if (!snapshot.customName().isEmpty()) {
            var special = models.getEasterEggEncryptTagModel(DigestUtils.sha1Hex(snapshot.customName()));
            if (special.isEmpty()) special = models.getEasterEggNormalTagModel(snapshot.customName());
            if (special.isPresent()) current = special.get().getInfo();
        }
        if (current != snapshot.expectedInfo())
            throw new IllegalStateException("导出期间 TLM 模型包发生重载，请重新导出");
    }

    public static Path export(Snapshot snapshot, byte[] archive, Path destination) throws IOException {
        ResourceSource source = new ResourceSource(snapshot.resources(), snapshot.packFolder());
        byte[] geometry = source.read(snapshot.model());
        JsonObject model = json(geometry, "模型");
        boolean modern = model.has("minecraft:geometry") && model.get("minecraft:geometry").isJsonArray()
                && !model.getAsJsonArray("minecraft:geometry").isEmpty()
                && model.getAsJsonArray("minecraft:geometry").asList().stream().allMatch(e -> e.isJsonObject()
                && e.getAsJsonObject().has("bones") && e.getAsJsonObject().get("bones").isJsonArray());
        boolean legacy = model.entrySet().stream().anyMatch(e -> e.getKey().startsWith("geometry.")
                && e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("bones")
                && e.getValue().getAsJsonObject().get("bones").isJsonArray());
        if (!modern && !legacy)
            throw new IOException("当前几何不是受支持的基岩模型 JSON：" + snapshot.model());
        byte[] texture = source.read(snapshot.texture());
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (texture.length < signature.length || !java.util.Arrays.equals(signature, java.util.Arrays.copyOf(texture, 8)))
            throw new IOException("模型贴图不是 PNG：" + snapshot.texture());
        SafeFiles.writeDurable(SafeFiles.resolve(destination, "model.json"), geometry);
        SafeFiles.writeDurable(SafeFiles.resolve(destination, "texture.png"), texture);
        SafeFiles.writeDurable(SafeFiles.resolve(destination, "maid_data.maid"), archive);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("model", "model.json");
        metadata.addProperty("texture", "texture.png");
        if (snapshot.gecko()) {
            JsonObject merged = json(source.readGameResource(GeckoModelLoader.DEFAULT_MAID_ANIMATION), "默认动画");
            if (!merged.has("animations") || !merged.get("animations").isJsonObject())
                throw new IOException("默认基岩动画缺少 animations");
            JsonObject animations = merged.getAsJsonObject("animations");
            for (ResourceLocation animation : snapshot.animations()) {
                if (animation.equals(GeckoModelLoader.DEFAULT_MAID_ANIMATION)) break;
                if (!animation.getPath().endsWith(".json")) throw new IOException("不支持非 JSON 动画：" + animation);
                JsonObject custom = json(source.read(animation), "动画");
                if (!custom.has("animations") || !custom.get("animations").isJsonObject())
                    throw new IOException("动画缺少 animations：" + animation);
                custom.getAsJsonObject("animations").entrySet().forEach(e -> animations.add(e.getKey(), e.getValue()));
            }
            writeJson(destination, "animation.json", merged);
            metadata.addProperty("anim", "animation.json");
        }
        metadata.addProperty("name", snapshot.name());
        metadata.addProperty("owner_name", snapshot.ownerName());
        metadata.addProperty("owner_uuid", snapshot.ownerUuid());
        metadata.addProperty("scale", snapshot.scale());
        metadata.addProperty("simple_bedrock_model", snapshot.simpleBedrock());
        metadata.addProperty("sound", snapshot.soundId());
        metadata.addProperty("sound_freq", snapshot.soundFrequency());
        writeJson(destination, "maid.json", metadata);
        return destination;
    }

    private static JsonObject json(byte[] bytes, String description) throws IOException {
        try { return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException(description + " JSON 无效", e); }
    }

    private static void writeJson(Path directory, String name, JsonObject json) throws IOException {
        SafeFiles.writeDurable(SafeFiles.resolve(directory, name),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json).getBytes(StandardCharsets.UTF_8));
    }
}
