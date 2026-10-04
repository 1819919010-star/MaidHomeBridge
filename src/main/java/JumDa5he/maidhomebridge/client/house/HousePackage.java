package JumDa5he.maidhomebridge.client.house;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

final class HousePackage {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private HousePackage() {}

    static Path collect(Path exported, Path destination, String model, JsonObject metadata,
                        JsonObject report, BooleanSupplier cancelled) throws IOException {
        Path root = exported.toRealPath();
        Path gltf = root.resolve(model + ".gltf");
        if (!Files.isRegularFile(gltf) || !Files.isRegularFile(root.resolve("report.json")))
            throw new IOException("MineToMesh 完成目录缺少 glTF 或 report.json");
        JsonObject document = JsonParser.parseString(Files.readString(gltf, StandardCharsets.UTF_8)).getAsJsonObject();
        validateReferences(document, root);
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target)) throw new IOException("房屋暂存目标已存在，拒绝覆盖：" + target);
        Files.createDirectories(target.getParent());
        Path stage = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());
        Files.createDirectory(stage);
        // A failed stage is retained as a diagnostic backup, never uploaded as a completed house.
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (cancelled.getAsBoolean()) throw new IOException("房屋打包已取消；原始 MineToMesh 导出保留");
                if (Files.isSymbolicLink(path)) throw new IOException("房屋目录包含符号链接");
                Path relative = root.relativize(path), output = stage.resolve(relative);
                if (Files.isDirectory(path)) Files.createDirectories(output);
                else if (Files.isRegularFile(path)) {
                    if (Files.size(path) > 256L * 1024 * 1024) throw new IOException("房屋单文件超过协议 256 MiB 上限：" + relative);
                    Files.copy(path, output);
                } else throw new IOException("房屋目录包含非常规文件：" + relative);
            }
        }
        Files.writeString(stage.resolve("house.json"), JSON.toJson(metadata), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(stage.resolve("bridge-house-report.json"), JSON.toJson(report), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        validateReferences(document, stage);
        if (cancelled.getAsBoolean()) throw new IOException("房屋打包已取消");
        try { Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(stage, target); }
        return target;
    }

    static void validateReferences(JsonElement element, Path root) throws IOException {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) validateReferences(child, root);
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().equals("uri") && entry.getValue().isJsonPrimitive()) {
                    String value = entry.getValue().getAsString();
                    if (value.startsWith("data:")) continue;
                    String decoded;
                    try {
                        URI uri = URI.create(value);
                        if (uri.isAbsolute() || uri.getAuthority() != null || uri.getQuery() != null || uri.getFragment() != null)
                            throw new IllegalArgumentException("not a relative file");
                        decoded = uri.getPath();
                    } catch (IllegalArgumentException e) { throw new IOException("glTF 包含非法引用：" + value, e); }
                    if (decoded == null || decoded.isBlank() || decoded.startsWith("/") || decoded.indexOf('\\') >= 0
                            || decoded.indexOf(':') >= 0 || List.of(decoded.split("/", -1)).contains(".."))
                        throw new IOException("glTF 引用越界：" + value);
                    Path base = root.toAbsolutePath().normalize(), file = base.resolve(decoded).normalize();
                    if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || !file.toRealPath().startsWith(base.toRealPath()))
                        throw new IOException("glTF 引用文件缺失或越界：" + value);
                } else validateReferences(entry.getValue(), root);
            }
        }
    }
}
