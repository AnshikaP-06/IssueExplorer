package com.anshika.issueexplorer.snapshot;

import com.anshika.issueexplorer.github.GitHubClient.GitHubException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotExtractorTest {
    @TempDir Path directory;
    private final SnapshotExtractor extractor = new SnapshotExtractor();

    private Path archive(Map<String, String> contents) throws Exception {
        Path archive = directory.resolve("archive.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var item : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(item.getKey()));
                zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return archive;
    }

    private SnapshotExtractor.ExtractionResult extract(Path archive, int files, long bytes) throws Exception {
        return extractor.extract(archive, directory.resolve("src"), files, bytes);
    }

    private void assertNoTemporaryDirectories() throws Exception {
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".extract-")
                    || path.getFileName().toString().startsWith(".previous-")));
        }
    }

    @Test
    void keepsOnlyJavaAndReportsReasons() throws Exception {
        var contents = new LinkedHashMap<String, String>();
        contents.put("repo/", "");
        contents.put("repo/src/main/java/Foo.java", "class Foo {}");
        contents.put("repo/src/test/java/FooTest.java", "class FooTest {}");
        contents.put("repo/README.md", "readme");
        contents.put("repo/data.txt", "data");
        contents.put("repo/target/Generated.java", "ignored");
        contents.put("repo/build/Foo.java", "ignored");
        contents.put("repo/.git/Foo.java", "ignored");
        contents.put("repo/node_modules/pkg/Foo.java", "ignored");
        contents.put("repo/generated-sources/Foo.java", "ignored");
        var result = extract(archive(contents), 5000, 200_000_000);
        assertEquals(2, result.kept());
        assertEquals(7, result.skipped());
        assertEquals(Map.of("symlinks", 0, "excluded directories", 5, "non-Java files", 2), result.skippedByReason());
        assertEquals(28, result.extractedBytes());
        assertEquals("class Foo {}", Files.readString(directory.resolve("src/src/main/java/Foo.java")));
        assertTrue(Files.exists(directory.resolve("src/src/test/java/FooTest.java")));
        assertFalse(Files.exists(directory.resolve("src/target")));
        assertFalse(Files.exists(directory.resolve("src/README.md")));
        assertNoTemporaryDirectories();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Escape.java", "repo/../../Escape.java", "repo/../Escape.java",
            "/Escape.java", "C:/Escape.java", "repo\\..\\Escape.java", "repo/A.java:stream",
            "repo/CON.java", "repo/a./Escape.java", "repo//Escape.java", "repo/./Escape.java",
            "../ignored.txt", "repo/target/../../Escape.java"})
    void rejectsUnsafePathsEvenWhenEntryWouldBeFiltered(String name) throws Exception {
        Path archive = archive(Map.of(name, "malicious"));
        var error = assertThrows(GitHubException.class, () -> extract(archive, 5000, 200_000_000));
        assertEquals(2, error.exitCode());
        assertTrue(error.getMessage().contains("zip-slip"));
        assertFalse(Files.exists(directory.resolve("src")));
        assertFalse(Files.exists(directory.resolve("Escape.java")));
        assertNoTemporaryDirectories();
    }

    @Test
    void skipsUnixSymlinkAndItsChildrenWithoutFollowingIt() throws Exception {
        var contents = new LinkedHashMap<String, String>();
        contents.put("repo/Link.java", "../../outside");
        contents.put("repo/linked/", "../../outside");
        contents.put("repo/linked/Child.java", "class Child {}");
        contents.put("repo/Good.java", "class Good {}");
        Path archive = archive(contents);
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer metadata = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes.length - 46; i++) {
            if (metadata.getInt(i) == 0x02014b50) {
                int nameLength = Short.toUnsignedInt(metadata.getShort(i + 28));
                String name = new String(bytes, i + 46, nameLength, StandardCharsets.UTF_8);
                if (name.equals("repo/Link.java") || name.equals("repo/linked/")) {
                    metadata.putShort(i + 4, (short) ((3 << 8) | 20));
                    metadata.putInt(i + 38, 0120777 << 16);
                }
            }
        }
        Files.write(archive, bytes);
        var result = extract(archive, 5000, 200_000_000);
        assertEquals(1, result.kept());
        assertEquals(3, result.skippedByReason().get("symlinks"));
        assertFalse(Files.exists(directory.resolve("src/Link.java")));
        assertFalse(Files.exists(directory.resolve("src/linked")));
        assertTrue(Files.exists(directory.resolve("src/Good.java")));
    }

    @Test
    void enforcesJavaFileCountAndCleansUp() throws Exception {
        Path archive = archive(Map.of("repo/A.java", "a", "repo/B.java", "b"));
        var error = assertThrows(GitHubException.class, () -> extract(archive, 1, 100));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("file count of 1"));
        assertFalse(Files.exists(directory.resolve("src")));
        assertNoTemporaryDirectories();
    }

    @Test
    void exactFileAndSizeLimitsAreAccepted() throws Exception {
        var result = extract(archive(Map.of("repo/A.java", "abc", "repo/B.java", "de")), 2, 5);
        assertEquals(2, result.kept());
        assertEquals(5, result.extractedBytes());
    }

    @Test
    void enforcesTotalSizeAcrossFiles() throws Exception {
        Path archive = archive(Map.of("repo/A.java", "abc", "repo/B.java", "de"));
        var error = assertThrows(GitHubException.class, () -> extract(archive, 2, 4));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("extracted size of 4 bytes"));
        assertFalse(Files.exists(directory.resolve("src")));
        assertNoTemporaryDirectories();
    }

    @Test
    void streamedBytesCannotBypassLimitUsingFalseDeclaredSize() throws Exception {
        Path archive = archive(Map.of("repo/A.java", "x".repeat(10000)));
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer metadata = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes.length - 46; i++) {
            if (metadata.getInt(i) == 0x02014b50) {
                metadata.putInt(i + 24, 1);
                break;
            }
        }
        Files.write(archive, bytes);
        var error = assertThrows(GitHubException.class, () -> extract(archive, 2, 100));
        assertEquals(5, error.exitCode());
        assertFalse(Files.exists(directory.resolve("src")));
        assertNoTemporaryDirectories();
    }

    @Test
    void failedExtractionPreservesPreviouslyPublishedSource() throws Exception {
        extract(archive(Map.of("repo/A.java", "old")), 2, 100);
        Path archive = archive(Map.of("repo/B.java", "large content"));
        assertThrows(GitHubException.class, () -> extract(archive, 2, 1));
        assertEquals("old", Files.readString(directory.resolve("src/A.java")));
        assertFalse(Files.exists(directory.resolve("src/B.java")));
        assertNoTemporaryDirectories();
    }

    @Test
    void repeatedExtractionReplacesStaleFiles() throws Exception {
        extract(archive(Map.of("repo/A.java", "old")), 2, 100);
        extract(archive(Map.of("repo/B.java", "new")), 2, 100);
        assertFalse(Files.exists(directory.resolve("src/A.java")));
        assertEquals("new", Files.readString(directory.resolve("src/B.java")));
        assertNoTemporaryDirectories();
    }

    @Test
    void zeroJavaFilesIsSuccessfulAndReported() throws Exception {
        var result = extract(archive(Map.of("repo/README.md", "text")), 1, 1);
        assertEquals(0, result.kept());
        assertEquals(1, result.skipped());
        assertEquals(0, result.extractedBytes());
    }

    @Test
    void multipleRootsArePreserved() throws Exception {
        var result = extract(archive(Map.of("a/A.java", "a", "b/B.java", "b")), 2, 2);
        assertEquals(2, result.kept());
        assertTrue(Files.exists(directory.resolve("src/a/A.java")));
        assertTrue(Files.exists(directory.resolve("src/b/B.java")));
    }

    @Test
    void topLevelExcludedDirectoryIsNotStrippedAsRepositoryRoot() throws Exception {
        var result = extract(archive(Map.of("target/A.java", "ignored")), 1, 1);
        assertEquals(0, result.kept());
        assertEquals(1, result.skippedByReason().get("excluded directories"));
    }

    @Test
    void invalidZipFailsWithClearError() throws Exception {
        Path archive = directory.resolve("bad.zip");
        Files.writeString(archive, "not a zip");
        var error = assertThrows(GitHubException.class, () -> extract(archive, 2, 100));
        assertEquals(1, error.exitCode());
        assertTrue(error.getMessage().contains("Could not safely extract"));
    }

    @Test
    void checksumMismatchCannotPublishSource() throws Exception {
        Path archive = archive(Map.of("repo/A.java", "a"));
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer metadata = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < bytes.length - 46; i++) {
            if (metadata.getInt(i) == 0x02014b50) {
                metadata.putInt(i + 16, 0);
                break;
            }
        }
        Files.write(archive, bytes);
        assertThrows(GitHubException.class, () -> extract(archive, 2, 100));
        assertFalse(Files.exists(directory.resolve("src")));
        assertNoTemporaryDirectories();
    }

    @Test
    void zip64MetadataFailsClosed() throws Exception {
        Path archive = archive(Map.of("repo/A.java", "a"));
        byte[] bytes = Files.readAllBytes(archive);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(bytes.length - 12, (short) 65535);
        Files.write(archive, bytes);
        var error = assertThrows(GitHubException.class, () -> extract(archive, 2, 100));
        assertTrue(error.getMessage().contains("ZIP64"));
        assertFalse(Files.exists(directory.resolve("src")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsInvalidFileLimits(int count) throws Exception {
        Path archive = archive(Map.of("repo/A.java", "a"));
        assertThrows(IllegalArgumentException.class, () -> extract(archive, count, 100));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidSizeLimits(long bytes) throws Exception {
        Path archive = archive(Map.of("repo/A.java", "a"));
        assertThrows(IllegalArgumentException.class, () -> extract(archive, 2, bytes));
    }
}

