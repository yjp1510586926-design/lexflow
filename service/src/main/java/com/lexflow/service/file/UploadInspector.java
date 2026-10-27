package com.lexflow.service.file;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Component;

/** 校验服务端临时文件的类型、大小和摘要；提交的文件名仅作为展示文本。 */
@Component
public final class UploadInspector {

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of(
        "pdf",
        "application/pdf",
        "docx",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "txt",
        "text/plain",
        "jpg",
        "image/jpeg",
        "jpeg",
        "image/jpeg",
        "png",
        "image/png"
    );

    public record Inspected(String displayName, String contentType, long size, String sha256) {}

    public Inspected inspect(Path file, String submittedName, String submittedType)
        throws IOException {
        if (
            submittedName == null ||
            submittedName.isBlank() ||
            submittedName.length() > 255 ||
            submittedName.chars().anyMatch(codePoint -> codePoint < 32 || codePoint == 127)
        ) {
            throw invalid();
        }
        String displayName = submittedName.replace('\\', '/');
        displayName = displayName.substring(displayName.lastIndexOf('/') + 1);
        int extensionSeparator = displayName.lastIndexOf('.');
        String contentType =
            extensionSeparator < 0
                ? null
                : TYPES.get(displayName.substring(extensionSeparator + 1).toLowerCase(Locale.ROOT));
        if (contentType == null) {
            throw invalid();
        }
        String declaredContentType =
            submittedType == null
                ? ""
                : submittedType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        // 扩展名、声明的 MIME 类型和实际内容均须一致，不能仅凭浏览器声明放行。
        if (!declaredContentType.equals(contentType)) {
            throw invalid();
        }
        long sizeBytes = Files.size(file);
        if (sizeBytes < 1 || sizeBytes > MAX_BYTES) {
            throw invalid();
        }
        byte[] signatureBytes;
        try (var inputStream = Files.newInputStream(file)) {
            signatureBytes = inputStream.readNBytes(8);
        }
        switch (contentType) {
            case "application/pdf" -> {
                if (!starts(signatureBytes, new byte[] { 37, 80, 68, 70, 45 })) {
                    throw invalid();
                }
            }
            case "image/png" -> {
                if (
                    !starts(signatureBytes, new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 })
                ) {
                    throw invalid();
                }
            }
            case "image/jpeg" -> {
                if (!starts(signatureBytes, new byte[] { (byte) 255, (byte) 216, (byte) 255 })) {
                    throw invalid();
                }
            }
            case "text/plain" -> validateText(file);
            default -> validateDocx(file);
        }
        try {
            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            try (var inputStream = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    sha256Digest.update(buffer, 0, bytesRead);
                }
            }
            return new Inspected(
                displayName,
                contentType,
                sizeBytes,
                HexFormat.of().formatHex(sha256Digest.digest())
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean starts(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static void validateText(Path file) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[4096];
            int charactersRead;
            while ((charactersRead = reader.read(buffer)) != -1) {
                for (int index = 0; index < charactersRead; index++) {
                    if (
                        buffer[index] == 0 ||
                        buffer[index] < 9 ||
                        (buffer[index] > 13 && buffer[index] < 32)
                    ) {
                        throw invalid();
                    }
                }
            }
        } catch (CharacterCodingException e) {
            throw invalid();
        }
    }

    private static void validateDocx(Path file) throws IOException {
        // DOCX 按 ZIP 实际解压读取并累计字节，限制膨胀量、重复条目和不安全路径，不解压落盘。
        boolean hasContentTypes = false,
            hasDocument = false;
        int entryCount = 0;
        long expandedBytes = 0;
        var entryNames = new HashSet<String>();
        try (var zipStream = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zipStream.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (
                    ++entryCount > 10000 ||
                    !entryNames.add(entryName) ||
                    entryName.startsWith("/") ||
                    entryName.contains("\\") ||
                    Arrays.asList(entryName.split("/")).contains("..")
                ) {
                    throw invalid();
                }
                if (entryName.equals("word/vbaProject.bin")) {
                    throw invalid();
                }
                long entrySizeBytes = 0;
                int bytesRead;
                while ((bytesRead = zipStream.read(buffer)) != -1) {
                    expandedBytes += bytesRead;
                    entrySizeBytes += bytesRead;
                    if (expandedBytes > 100L * 1024 * 1024) {
                        throw invalid();
                    }
                }
                if (entryName.equals("[Content_Types].xml") && entrySizeBytes > 0) {
                    hasContentTypes = true;
                }
                if (entryName.equals("word/document.xml") && entrySizeBytes > 0) {
                    hasDocument = true;
                }
            }
        } catch (ZipException e) {
            throw invalid();
        }
        if (!hasContentTypes || !hasDocument) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("文件类型、内容或大小不符合要求");
    }
}
