package JumDa5he.maidhomebridge.portal;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class PackageUploader {
    public record Result(String id, String kind, String name, String recordKey) {}
    private final PortalClient portal;
    private final TransferStore store;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean commitStarted, commitFinished;
    private Runnable commitCheck = () -> {};
    public PackageUploader beforeCommit(Runnable check) { commitCheck = Objects.requireNonNull(check); return this; }
    public boolean resultUncertain() { return commitStarted && !commitFinished; }
    public PackageUploader(PortalClient portal, TransferStore store) { this.portal = portal; this.store = store; }
    public void cancel() { cancelled.set(true); }

    public Result upload(String kind, String desiredId, String name, Path folder, JsonObject identity,
                         Consumer<String> progress) throws IOException {
        if (!Set.of("maid", "house", "sound").contains(kind)) throw new IOException("未知上传类型");
        if (desiredId != null && !desiredId.isBlank()) SafeFiles.id(desiredId);
        List<Path> files;
        try (var paths = Files.walk(folder)) { files = paths.filter(p -> !Files.isDirectory(p)).sorted().toList(); }
        if (files.isEmpty() || files.size() > 16_384) throw new IOException("文件数量不合规");
        long total = 0;
        Map<Path, String> hashes = new LinkedHashMap<>();
        for (Path file : files) {
            SafeFiles.resolve(folder, folder.relativize(file).toString().replace('\\', '/'));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("拒绝链接或特殊文件");
            long size = Files.size(file);
            if (size > PortalFrame.MAX_BODY) throw new IOException("单文件超过 256 MiB: " + file.getFileName());
            total = Math.addExact(total, size);
            if (total > 4L * 1024 * 1024 * 1024) throw new IOException("单次上传超过 4 GiB");
            hashes.put(file, SafeFiles.sha256(Files.readAllBytes(file)));
        }
        String key = "upload-" + UUID.randomUUID();
        JsonObject record = identity.deepCopy();
        record.addProperty("kind", kind); record.addProperty("package", folder.toString());
        JsonObject fileHashes = new JsonObject();
        hashes.forEach((p, hash) -> fileHashes.addProperty(folder.relativize(p).toString().replace('\\', '/'), hash));
        record.add("sha256", fileHashes);
        store.journal(key, record, "PREPARED");
        boolean began = false;
        boolean committing = false;
        try {
            checkCancel();
            JsonObject begin = PortalClient.message("upload.begin");
            begin.addProperty("kind", kind); begin.addProperty("name", name);
            if (desiredId != null && !desiredId.isBlank()) begin.addProperty("id", desiredId);
            begin.addProperty("file_count", files.size()); begin.addProperty("total_bytes", total);
            JsonObject opened = portal.request(begin, new byte[0]);
            began = true;
            String id = SafeFiles.id(PortalFrame.string(opened, "id"));
            if (!kind.equals(PortalFrame.string(opened, "kind"))) throw new IOException("上传类型应答不符");
            record.addProperty("unity_id", id);
            record.addProperty("upload_id", PortalFrame.string(opened, "upload_id"));
            store.journal(key, record, "UPLOADING");
            long sent = 0;
            for (Path file : files) {
                checkCancel();
                String relative = folder.relativize(file).toString().replace('\\', '/');
                byte[] body = Files.readAllBytes(file);
                SafeFiles.verify(body, hashes.get(file));
                JsonObject header = PortalClient.message("upload.file");
                header.addProperty("path", relative); header.addProperty("body", body.length);
                header.addProperty("sha256", hashes.get(file));
                JsonObject ok = portal.request(header, body);
                if (!relative.equals(PortalFrame.string(ok, "path")) || PortalFrame.integer(ok, "bytes", -1, PortalFrame.MAX_BODY) != body.length)
                    throw new IOException("文件应答内容不匹配");
                sent += body.length;
                progress.accept("上传 " + kind + " " + sent + "/" + total + " 字节 (" + relative + ")");
            }
            checkCancel();
            commitCheck.run();
            store.journal(key, record, "COMMIT_RESULT_UNCERTAIN");
            committing = true;
            commitStarted = true;
            JsonObject committed = portal.request(PortalClient.message("upload.commit"), new byte[0]);
            if (!id.equals(PortalFrame.string(committed, "id")) || !kind.equals(PortalFrame.string(committed, "kind")))
                throw new IOException("提交应答身份不符；结果待确认");
            store.journal(key, record, "COMMITTED");
            commitFinished = true;
            return new Result(id, kind, committed.has("name") ? PortalFrame.string(committed, "name") : name, key);
        } catch (IOException | RuntimeException e) {
            record.addProperty("error",e.getMessage());
            if (began && !committing && portal.connected()) {
                try { portal.request(PortalClient.message("upload.abort"), new byte[0]); }
                catch (IOException abortError) { e.addSuppressed(abortError); }
            }
            if (!committing) store.journal(key, record, "ABORTED_OR_DISCONNECTED");
            else store.journal(key, record, "COMMIT_RESULT_UNCERTAIN");
            throw e;
        }
    }
    private void checkCancel() { if (cancelled.get()) throw new CancellationException("用户已取消上传"); }
}
