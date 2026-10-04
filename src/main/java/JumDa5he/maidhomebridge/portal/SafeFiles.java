package JumDa5he.maidhomebridge.portal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

public final class SafeFiles {
    private SafeFiles() {}

    public static String id(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("非法 ID: " + id);
        return id;
    }

    public static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 1024 || relative.contains("\\")
                || relative.contains(":") || relative.startsWith("/") || relative.indexOf('\0') >= 0)
            throw new IOException("非法相对路径");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?")
                    || part.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0))
                throw new IOException("非法路径段: " + part);
        }
        Path base = root.toAbsolutePath().normalize();
        Path result = base.resolve(relative).normalize();
        if (!result.startsWith(base)) throw new IOException("路径逃逸");
        for (Path p = result; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("拒绝符号链接: " + p);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !p.toRealPath().equals(p.toAbsolutePath().normalize()))
                throw new IOException("拒绝重定向路径: " + p);
        }
        return result;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void verify(byte[] bytes, String expected) throws IOException {
        if (expected == null || !expected.matches("[0-9a-fA-F]{64}") || !sha256(bytes).equalsIgnoreCase(expected))
            throw new IOException("SHA-256 校验失败");
    }

    public static void writeDurable(Path path, byte[] bytes) throws IOException {
        resolve(path.getParent(), path.getFileName().toString());
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.getParent(), ".bridge-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("备份目录不支持原子落盘，拒绝确认接收", e); }
        } finally { Files.deleteIfExists(temp); }
    }
}
