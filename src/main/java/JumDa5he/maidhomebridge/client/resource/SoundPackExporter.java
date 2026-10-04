package JumDa5he.maidhomebridge.client.resource;

import JumDa5he.maidhomebridge.portal.SafeFiles;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.CustomSoundLoader;
import com.github.tartaricacid.touhoulittlemaid.client.sound.data.SoundCache;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

public final class SoundPackExporter {
    private static final Pattern SOUND_PATH = Pattern.compile("maid/(ai|mode|environment|other)/[a-z_]+[0-9]*\\.ogg");
    private static final long MAX_TOTAL = 1024L * 1024 * 1024;
    private SoundPackExporter() {}

    public record Snapshot(String soundId, ResourceManager resources, Path packFolder, SoundCache expectedCache) {}

    public static Snapshot capture(String soundId) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("音效包快照必须在客户端主线程读取");
        if (soundId == null || !soundId.matches("[a-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("音效包 ID 无法用于 Portal：" + soundId);
        if (CustomSoundLoader.getSoundCache(soundId) == null)
            throw new IllegalArgumentException("客户端没有加载此 TLM 音效包：" + soundId);
        return new Snapshot(soundId, Minecraft.getInstance().getResourceManager(), CustomPackLoader.PACK_FOLDER,
                CustomSoundLoader.getSoundCache(soundId));
    }

    public static void validate(Snapshot snapshot) {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("音效复核必须在客户端主线程执行");
        if (CustomSoundLoader.getSoundCache(snapshot.soundId()) != snapshot.expectedCache())
            throw new IllegalStateException("导出期间 TLM 音效包发生重载，请重新导出");
    }

    public static Path export(Snapshot snapshot, Path destination, Consumer<String> progress) throws IOException {
        String root = "assets/" + snapshot.soundId() + "/";
        List<Path> candidates = new ArrayList<>();
        for (Path pack : ResourceSource.packs(snapshot.packFolder())) {
            if (ResourceSource.read(pack, root + "maid_sound.json") != null) candidates.add(pack);
        }
        if (candidates.size() > 1)
            throw new IOException("多个 TLM 包提供相同音效包 ID，无法确定实际来源：" + snapshot.soundId());
        if (candidates.isEmpty()) throw new IOException("音效包缓存存在，但未找到其原始 OGG 包：" + snapshot.soundId());
        Path pack = candidates.getFirst();
        String prefix = root + "sounds/";
        List<String> files;
        if (Files.isDirectory(pack)) {
            Path soundRoot = SafeFiles.resolve(pack, prefix + "maid");
            if (!Files.isDirectory(soundRoot)) throw new IOException("音效包缺少 sounds/maid 目录");
            try (var paths = Files.walk(soundRoot)) {
                files = paths.filter(Files::isRegularFile).map(p -> pack.relativize(p).toString().replace('\\', '/'))
                        .filter(p -> p.endsWith(".ogg")).sorted().toList();
            }
        } else {
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                files = zip.stream().filter(e -> !e.isDirectory()).map(e -> e.getName())
                        .filter(p -> p.startsWith(prefix + "maid/") && p.endsWith(".ogg")).sorted().toList();
            }
        }
        if (files.isEmpty()) throw new IOException("音效包中没有 OGG 文件");
        if (files.size() > 20_000) throw new IOException("音效包文件超过 20000 个");
        long total = 0;
        int copied = 0;
        for (String file : files) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("音效导出已取消");
            String relative = file.substring(prefix.length());
            if (!SOUND_PATH.matcher(relative).matches())
                throw new IOException("音效文件不符合 Portal 分类/事件命名约定：" + relative);
            byte[] bytes = ResourceSource.read(pack, file);
            if (bytes == null || bytes.length < 4 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S')
                throw new IOException("音效不是有效 OGG 容器：" + relative);
            total += bytes.length;
            if (total > MAX_TOTAL) throw new IOException("音效包超过 1 GiB");
            SafeFiles.writeDurable(SafeFiles.resolve(destination, relative), bytes);
            progress.accept("导出音效 " + (++copied) + "/" + files.size() + "：" + relative);
        }
        return destination;
    }
}
