package JumDa5he.maidhomebridge.client.resource;

import JumDa5he.maidhomebridge.portal.SafeFiles;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

final class ResourceSource {
    static final class MissingResourceException extends IOException {
        MissingResourceException(ResourceLocation id) {
            super("客户端缺少资源：" + id);
        }
    }

    static final int MAX_RESOURCE = 64 * 1024 * 1024;
    private final ResourceManager resources;
    private final List<Path> packs;

    ResourceSource(ResourceManager resources, Path packFolder) throws IOException {
        this.resources = resources;
        this.packs = packs(packFolder);
    }

    static List<Path> packs(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var stream = Files.list(folder)) {
            return stream.filter(p -> Files.isDirectory(p) || p.getFileName().toString().endsWith(".zip"))
                    .sorted().toList();
        }
    }

    byte[] read(ResourceLocation id) throws IOException {
        String relative = "assets/" + id.getNamespace() + "/" + id.getPath();
        byte[] chosen = null;
        for (Path pack : packs) {
            byte[] candidate = read(pack, relative);
            if (candidate == null) continue;
            if (chosen != null && !Arrays.equals(chosen, candidate))
                throw new IOException("多个 TLM 模型包提供不同内容的资源，无法确定实际来源：" + id);
            chosen = candidate;
        }
        if (chosen != null) return chosen;
        return readGameResource(id);
    }

    byte[] readGameResource(ResourceLocation id) throws IOException {
        var resource = resources.getResource(id);
        if (resource.isEmpty()) throw new MissingResourceException(id);
        try (InputStream input = resource.get().open()) { return bounded(input); }
    }

    static byte[] read(Path pack, String relative) throws IOException {
        SafeFiles.resolve(pack, relative);
        if (Files.isDirectory(pack)) {
            Path path = SafeFiles.resolve(pack, relative);
            if (!Files.isRegularFile(path)) return null;
            try (InputStream input = Files.newInputStream(path)) { return bounded(input); }
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry(relative);
            if (entry == null || entry.isDirectory()) return null;
            if (entry.getSize() > MAX_RESOURCE) throw new IOException("资源超过 64 MiB：" + relative);
            try (InputStream input = zip.getInputStream(entry)) { return bounded(input); }
        }
    }

    static byte[] bounded(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_RESOURCE + 1);
        if (bytes.length == 0 || bytes.length > MAX_RESOURCE) throw new IOException("资源为空或超过 64 MiB");
        return bytes;
    }
}
