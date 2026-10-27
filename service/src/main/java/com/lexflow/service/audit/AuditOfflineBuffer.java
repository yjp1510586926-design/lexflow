package com.lexflow.service.audit;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 私有本地审计缓冲；待重放事件不因容量满而淘汰，未通过完整性校验的文件不自动删除。 */
@Service
public class AuditOfflineBuffer {

    private static final int MAX_EVENT_BYTES = 8192;
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "schemaVersion",
        "event",
        "payloadSha256",
        "hmac"
    );
    private final Path configuredDirectory;
    private final int maxRecords;
    private final long maxBytes;
    private final AuditOperationalMetrics metrics;
    private Path directory;
    private byte[] key;
    private String cursor = "";

    public enum Failure {
        UNAVAILABLE,
        FULL,
        INTEGRITY,
        BUSY,
    }

    public static final class BufferFailure extends RuntimeException {

        private final Failure kind;

        BufferFailure(Failure kind) {
            super("Audit buffer " + kind.name());
            this.kind = kind;
        }

        public Failure kind() {
            return kind;
        }
    }

    public record Entry(Path path, RequestAuditEvent event, String fileHash) {}

    public record Snapshot(
        boolean available,
        long records,
        long bytes,
        long maxRecords,
        long maxBytes,
        long oldestAgeSeconds
    ) {}

    public AuditOfflineBuffer(
        @Value(
            "${lexflow.audit.buffer-directory:${user.dir}/.lexflow-audit-buffer}"
        ) String directory,
        @Value("${lexflow.audit.max-records:10000}") int maxRecords,
        @Value("${lexflow.audit.max-bytes:20971520}") long maxBytes,
        AuditOperationalMetrics metrics
    ) {
        if (
            maxRecords < 1 ||
            maxRecords > 100000 ||
            maxBytes < MAX_EVENT_BYTES ||
            maxBytes > 1073741824L
        ) {
            throw new IllegalArgumentException("Invalid audit buffer limits");
        }
        this.configuredDirectory = Path.of(directory).toAbsolutePath().normalize();
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
        this.metrics = metrics;
    }

    public synchronized void append(RequestAuditEvent event) {
        try (var ignored = lock()) {
            byte[] bytes = envelope(event);
            Path destination = directory.resolve(event.eventId() + ".audit");
            if (Files.exists(destination, NOFOLLOW_LINKS)) {
                if (!read(destination).event().payloadHash().equals(event.payloadHash())) {
                    throw failure(Failure.INTEGRITY);
                }
                metrics.bufferAvailable(true);
                return;
            }
            Snapshot snapshot = scan();
            if (snapshot.records() >= maxRecords || snapshot.bytes() + bytes.length > maxBytes) {
                throw failure(Failure.FULL);
            }
            Path temporary = directory.resolve(UUID.randomUUID() + ".tmp");
            boolean ownsTemporary = false;
            try {
                // 先同步临时文件，再原子改名并同步目录；持久化完成后才返回缓冲成功。
                try (var channel = createPrivate(temporary)) {
                    ownsTemporary = true;
                    writeFully(channel, bytes);
                    channel.force(true);
                }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                ownsTemporary = false;
                forceDirectory();
                metrics.bufferAvailable(true);
            } finally {
                if (ownsTemporary) {
                    Files.deleteIfExists(temporary);
                }
            }
        } catch (BufferFailure failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    /** 轮转游标让损坏记录留待核对，同时避免队首坏文件长期阻塞其他有效事件。 */
    public synchronized List<Entry> batch(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Invalid audit replay batch");
        }
        try (var ignored = lock()) {
            List<Path> paths = files();
            paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
            var ordered = new ArrayList<Path>();
            for (var path : paths) {
                if (path.getFileName().toString().compareTo(cursor) > 0) {
                    ordered.add(path);
                }
            }
            for (var path : paths) {
                if (path.getFileName().toString().compareTo(cursor) <= 0) {
                    ordered.add(path);
                }
            }
            var result = new ArrayList<Entry>();
            for (var path : ordered.subList(0, Math.min(limit, ordered.size()))) {
                cursor = path.getFileName().toString();
                try {
                    result.add(read(path));
                } catch (Exception invalid) {
                    failure(Failure.INTEGRITY);
                }
            }
            metrics.bufferAvailable(true);
            return List.copyOf(result);
        } catch (BufferFailure failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    /** 仅在独立数据库事务成功提交后确认，并核对文件未被替换才删除缓冲记录。 */
    public synchronized void acknowledge(Entry entry) {
        try (var ignored = lock()) {
            Path expected = directory.resolve(entry.event().eventId() + ".audit");
            if (!expected.equals(entry.path())) {
                throw failure(Failure.INTEGRITY);
            }
            if (!Files.exists(expected, NOFOLLOW_LINKS)) {
                return;
            }
            if (!read(expected).fileHash().equals(entry.fileHash())) {
                throw failure(Failure.INTEGRITY);
            }
            Files.delete(expected);
            forceDirectory();
            metrics.bufferAvailable(true);
        } catch (BufferFailure failure) {
            throw failure;
        } catch (Exception unavailable) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    public synchronized Snapshot snapshot() {
        try (var ignored = lock()) {
            var result = scan();
            metrics.bufferAvailable(true);
            return result;
        } catch (Exception unavailable) {
            metrics.bufferAvailable(false);
            return new Snapshot(false, 0, 0, maxRecords, maxBytes, 0);
        }
    }

    private void initialize() throws IOException {
        if (directory == null) {
            Path parent = configuredDirectory.getParent().toRealPath();
            Path root = parent.resolve(configuredDirectory.getFileName());
            try {
                Files.createDirectory(
                    root,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------")
                    )
                );
            } catch (FileAlreadyExistsException existing) {
                /* 已有目录也必须通过后续私有权限校验。 */
            }
            if (
                !Files.isDirectory(root, NOFOLLOW_LINKS) ||
                !Files.getPosixFilePermissions(root, NOFOLLOW_LINKS).equals(
                    PosixFilePermissions.fromString("rwx------")
                )
            ) {
                throw failure(Failure.UNAVAILABLE);
            }
            directory = root.toRealPath();
        }
        if (
            !Files.isDirectory(directory, NOFOLLOW_LINKS) ||
            !Files.getPosixFilePermissions(directory, NOFOLLOW_LINKS).equals(
                PosixFilePermissions.fromString("rwx------")
            )
        ) {
            throw failure(Failure.UNAVAILABLE);
        }
    }

    /** synchronized 保护同一实例，文件锁协调共享目录的进程；锁忙时立即抛出 BUSY，避免请求线程等待文件锁。 */
    private Locked lock() throws IOException {
        initialize();
        Path path = directory.resolve(".lock");
        try (var created = createPrivate(path)) {
            created.force(true);
        } catch (FileAlreadyExistsException exists) {
            /* 复用同一锁文件，跨进程互斥。 */
        }
        privateFile(path);
        var channel = FileChannel.open(path, Set.of(READ, WRITE, NOFOLLOW_LINKS));
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException busy) {
                throw failure(Failure.BUSY);
            }
            if (lock == null) {
                throw failure(Failure.BUSY);
            }
            var result = new Locked(channel, lock);
            try {
                loadKey();
                return result;
            } catch (Exception invalid) {
                result.close();
                throw invalid;
            }
        } catch (Exception failed) {
            channel.close();
            throw failed;
        }
    }

    private void loadKey() throws IOException {
        Path path = directory.resolve(".key");
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            // 已有事件或已加载密钥时缺失 .key 视为损坏，不能生成新密钥掩盖历史签名失效。
            if (!files().isEmpty() || key != null) {
                throw failure(Failure.INTEGRITY);
            }
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            try (var created = createPrivate(path)) {
                writeFully(created, generated);
                created.force(true);
            }
            forceDirectory();
        }
        byte[] persisted = readPrivate(path, 32);
        if (persisted.length != 32 || (key != null && !MessageDigest.isEqual(persisted, key))) {
            throw failure(Failure.INTEGRITY);
        }
        key = persisted;
    }

    private byte[] envelope(RequestAuditEvent event) throws Exception {
        var json = RequestAuditEvent.JSON.createObjectNode();
        json.put("schemaVersion", 1);
        json.set("event", event.json());
        json.put("payloadSha256", event.payloadHash());
        json.put("hmac", signature(event.bytes()));
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EVENT_BYTES) {
            throw failure(Failure.INTEGRITY);
        }
        return bytes;
    }

    private Entry read(Path path) throws Exception {
        String name = path.getFileName().toString();
        if (!name.matches("[0-9a-f-]{36}\\.audit")) {
            throw failure(Failure.INTEGRITY);
        }
        byte[] bytes = readPrivate(path, MAX_EVENT_BYTES);
        JsonNode envelope = RequestAuditEvent.JSON.readTree(bytes);
        RequestAuditEvent.requireFields(envelope, ENVELOPE_FIELDS);
        if (
            !envelope.get("schemaVersion").isIntegralNumber() ||
            !envelope.get("schemaVersion").canConvertToInt() ||
            envelope.get("schemaVersion").intValue() != 1
        ) {
            throw failure(Failure.INTEGRITY);
        }
        // 摘要用于数据库去重，HMAC 用于确认缓冲事件完整性；两者与文件名中的事件身份须同时匹配。
        var event = RequestAuditEvent.parse(envelope.get("event"));
        if (
            !name.equals(event.eventId() + ".audit") ||
            !event.payloadHash().equals(RequestAuditEvent.string(envelope, "payloadSha256")) ||
            !MessageDigest.isEqual(
                signature(event.bytes()).getBytes(StandardCharsets.US_ASCII),
                RequestAuditEvent.string(envelope, "hmac").getBytes(StandardCharsets.US_ASCII)
            )
        ) {
            throw failure(Failure.INTEGRITY);
        }
        return new Entry(path, event, RequestAuditEvent.hash(bytes));
    }

    private String signature(byte[] bytes) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(bytes));
    }

    private List<Path> files() throws IOException {
        var files = new ArrayList<Path>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path path : entries) {
                String name = path.getFileName().toString();
                if (name.equals(".key") || name.equals(".lock")) {
                    continue;
                }
                files.add(path);
                if (files.size() > maxRecords) {
                    throw failure(Failure.FULL);
                }
            }
        }
        return files;
    }

    private Snapshot scan() throws IOException {
        long bytes = 0,
            oldest = System.currentTimeMillis();
        var files = files();
        for (var path : files) {
            privateFile(path);
            bytes = Math.addExact(bytes, Files.size(path));
            oldest = Math.min(oldest, Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis());
        }
        return new Snapshot(
            true,
            files.size(),
            bytes,
            maxRecords,
            maxBytes,
            Math.max(0, (System.currentTimeMillis() - oldest) / 1000)
        );
    }

    private void privateFile(Path path) throws IOException {
        // 拒绝符号链接、硬链接和非私有文件，避免读取或删除目录外内容。
        if (
            !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
            !Files.getPosixFilePermissions(path, NOFOLLOW_LINKS).equals(
                PosixFilePermissions.fromString("rw-------")
            ) ||
            !Files.getOwner(path, NOFOLLOW_LINKS).equals(
                Files.getOwner(directory, NOFOLLOW_LINKS)
            ) ||
            ((Number) Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS)).intValue() != 1
        ) {
            throw failure(Failure.INTEGRITY);
        }
    }

    private byte[] readPrivate(Path path, int limit) throws IOException {
        privateFile(path);
        try (var channel = FileChannel.open(path, Set.of(READ, NOFOLLOW_LINKS))) {
            long length = channel.size();
            if (length < 1 || length > limit) {
                throw failure(Failure.INTEGRITY);
            }
            ByteBuffer bytes = ByteBuffer.allocate((int) length);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) {
                    throw failure(Failure.INTEGRITY);
                }
            }
            if (channel.size() != length) {
                throw failure(Failure.INTEGRITY);
            }
            privateFile(path);
            return bytes.array();
        }
    }

    private FileChannel createPrivate(Path path) throws IOException {
        return FileChannel.open(
            path,
            Set.of(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        );
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private void forceDirectory() throws IOException {
        try (var channel = FileChannel.open(directory, READ)) {
            channel.force(true);
        }
    }

    private BufferFailure failure(Failure kind) {
        if (kind == Failure.INTEGRITY) {
            metrics.increment(AuditOperationalMetrics.Signal.INTEGRITY_FAILURE);
        }
        if (kind == Failure.FULL) {
            metrics.increment(AuditOperationalMetrics.Signal.BUFFER_FULL);
        }
        metrics.bufferAvailable(false);
        return new BufferFailure(kind);
    }

    private record Locked(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
