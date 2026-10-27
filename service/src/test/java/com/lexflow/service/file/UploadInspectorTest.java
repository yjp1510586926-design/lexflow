package com.lexflow.service.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadInspectorTest {

    @TempDir
    Path temp;

    private final UploadInspector inspector = new UploadInspector();

    @Test
    void textHasStableDigestAndSafeDisplayName() throws Exception {
        Path file = Files.writeString(temp.resolve("upload"), "hello");
        var result = inspector.inspect(file, "C:\\fakepath\\note.txt", "text/plain;charset=UTF-8");
        assertEquals("note.txt", result.displayName());
        assertEquals(5, result.size());
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            result.sha256()
        );
    }

    @Test
    void forgedPdfAndMismatchedMimeAreRejected() throws Exception {
        Path file = Files.writeString(temp.resolve("upload"), "not a PDF");
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(file, "x.pdf", "application/pdf")
        );
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(file, "x.txt", "image/png")
        );
    }

    @Test
    void binaryTextAndEmptyFileAreRejected() throws Exception {
        Path binary = Files.write(temp.resolve("binary"), new byte[] { 0, 1, 2 });
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(binary, "x.txt", "text/plain")
        );
        Path empty = Files.createFile(temp.resolve("empty"));
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(empty, "x.txt", "text/plain")
        );
    }

    @Test
    void maximumSizeIsCheckedBeforeReading() throws Exception {
        Path file = temp.resolve("large");
        try (var out = new RandomAccessFile(file.toFile(), "rw")) {
            out.setLength(UploadInspector.MAX_BYTES + 1);
        }
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(file, "x.pdf", "application/pdf")
        );
    }

    @Test
    void plainZipIsNotDocx() throws Exception {
        Path file = temp.resolve("zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(
                file,
                "x.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        );
    }

    @Test
    void pathTraversalArchiveIsRejected() throws Exception {
        Path file = temp.resolve("zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("../outside"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () ->
            inspector.inspect(
                file,
                "x.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        );
    }
}
