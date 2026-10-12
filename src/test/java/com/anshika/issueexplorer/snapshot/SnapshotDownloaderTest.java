package com.anshika.issueexplorer.snapshot;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.MockGitHubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotDownloaderTest {
    private static final String URL = "https://github.com/owner/repo/issues/123";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String ENDPOINT = "/repos/owner/repo/zipball/" + SHA;
    @TempDir Path cache;
    private MockGitHubServer server;
    private GitHubClient client;
    private SnapshotDownloader downloader;
    private byte[] archive;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockGitHubServer();
        client = new GitHubClient(server.baseUri(), null, Duration.ofSeconds(3), Clock.systemUTC());
        downloader = new SnapshotDownloader(client);
        archive = zipBytes();
        server.respondBytes(ENDPOINT, archive, false);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    public static byte[] zipBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("repo/Example.java"));
            zip.write("class Example {}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private Path archivePath() {
        return cache.resolve("owner/repo/" + SHA + "/archive.zip");
    }

    private void assertNoPartialArchive() throws Exception {
        assertFalse(Files.exists(archivePath()));
        try (var files = Files.walk(cache)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".part")));
        }
    }

    @Test
    void downloadsToExpectedCacheWithoutExtraction() throws Exception {
        var result = downloader.download(URL, SHA, cache, 50_000_000);
        assertFalse(result.cached());
        assertEquals(archivePath(), result.archive());
        assertArrayEquals(archive, Files.readAllBytes(result.archive()));
        assertEquals(ENDPOINT, server.request.get().path());
        try (var files = Files.list(result.archive().getParent())) {
            assertEquals(java.util.List.of(result.archive()), files.toList());
        }
    }

    @Test
    void cachedArchiveSkipsNetwork() throws Exception {
        downloader.download(URL, SHA, cache, 50_000_000);
        server.close();
        var result = downloader.download(URL, SHA, cache, 50_000_000);
        assertTrue(result.cached());
        assertEquals(1, server.requestCount.get());
        assertArrayEquals(archive, Files.readAllBytes(result.archive()));
    }

    @Test
    void differentShaHasSeparateCacheEntry() throws Exception {
        downloader.download(URL, SHA, cache, 50_000_000);
        String other = "a".repeat(40);
        server.respondBytes("/repos/owner/repo/zipball/" + other, archive, false);
        var result = downloader.download(URL, other, cache, 50_000_000);
        assertFalse(result.cached());
        assertEquals(cache.resolve("owner/repo/" + other + "/archive.zip"), result.archive());
        assertTrue(Files.exists(archivePath()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedDeclaredOrChunkedBodyIsRejectedAndCleanedUp(boolean chunked) throws Exception {
        server.respondBytes(ENDPOINT, archive, chunked);
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, archive.length - 1));
        assertEquals(5, error.exitCode());
        assertTrue(error.getMessage().contains("maximum download size"));
        assertNoPartialArchive();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactLimitIsAccepted(boolean chunked) throws Exception {
        server.respondBytes(ENDPOINT, archive, chunked);
        assertFalse(downloader.download(URL, SHA, cache, archive.length).cached());
        assertArrayEquals(archive, Files.readAllBytes(archivePath()));
    }

    @Test
    void cachedFileMustRespectCurrentLimit() throws Exception {
        downloader.download(URL, SHA, cache, archive.length);
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, archive.length - 1));
        assertEquals(5, error.exitCode());
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void truncatedDownloadCannotBecomeValidCache() throws Exception {
        server.respondTruncated(ENDPOINT, archive);
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertEquals(1, error.exitCode());
        assertNoPartialArchive();
    }

    @Test
    void nonZipResponseCannotBecomeValidCache() throws Exception {
        server.respondBytes(ENDPOINT, "not a zip".getBytes(), false);
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertEquals("Downloaded response is not a complete ZIP archive.", error.getMessage());
        assertNoPartialArchive();
    }

    @Test
    void corruptCacheIsDownloadedAgain() throws Exception {
        Files.createDirectories(archivePath().getParent());
        Files.writeString(archivePath(), "partial zip");
        assertFalse(downloader.download(URL, SHA, cache, 50_000_000).cached());
        assertArrayEquals(archive, Files.readAllBytes(archivePath()));
    }

    @Test
    void incompletePartFileIsNeverAHit() throws Exception {
        Files.createDirectories(archivePath().getParent());
        Files.writeString(archivePath().resolveSibling("old.part"), "partial");
        assertFalse(downloader.download(URL, SHA, cache, 50_000_000).cached());
    }

    @Test
    void controlledRedirectDropsAuthentication() throws Exception {
        String token = UUID.randomUUID().toString();
        var authenticated = new SnapshotDownloader(new GitHubClient(server.baseUri(), token,
                Duration.ofSeconds(3), Clock.systemUTC()));
        server.respondAt(ENDPOINT, 302, "redirect", Map.of("Location", server.baseUri().resolve("archive").toString()));
        server.respondBytes("/archive", archive, false);
        authenticated.download(URL, SHA, cache, 50_000_000);
        assertEquals(2, server.requestCount.get());
        assertEquals("Bearer " + token, server.requests.get(0).authorization());
        assertNull(server.requests.get(1).authorization());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/archive", "http://codeload.github.com/archive",
            "https://codeload.github.com.evil.example/archive", "https://user@codeload.github.com/archive",
            "https://codeload.github.com:443/archive", ""})
    void unsafeArchiveRedirectIsRejected(String location) throws Exception {
        server.respondAt(ENDPOINT, 302, "redirect", Map.of("Location", location));
        assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertEquals(1, server.requestCount.get());
        assertNoPartialArchive();
    }

    @Test
    void redirectChainIsRejected() throws Exception {
        server.respondAt(ENDPOINT, 302, "redirect", Map.of("Location", server.baseUri().resolve("archive").toString()));
        server.respondAt("/archive", 302, "redirect", Map.of("Location", "/another"));
        assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertEquals(2, server.requestCount.get());
        assertNoPartialArchive();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"404,3", "429,4", "500,1"})
    void archiveHttpErrorsPreserveExitCodesAndCleanUp(int status, int exit) throws Exception {
        server.respondAt(ENDPOINT, status, "{}", Map.of("Retry-After", "120"));
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertEquals(exit, error.exitCode());
        assertNoPartialArchive();
    }

    @Test
    void networkFailureLeavesNoArchive() throws Exception {
        server.close();
        assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, cache, 50_000_000));
        assertNoPartialArchive();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void invalidLimitIsRejectedBeforeNetwork(long limit) {
        assertThrows(IllegalArgumentException.class, () -> downloader.download(URL, SHA, cache, limit));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void invalidShaIsRejectedBeforeNetwork() {
        assertThrows(IllegalArgumentException.class, () -> downloader.download(URL, "../escape", cache, 50_000_000));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void cacheWriteFailureHasClearError() throws Exception {
        Path notDirectory = cache.resolve("file");
        Files.writeString(notDirectory, "a file");
        var error = assertThrows(GitHubClient.GitHubException.class,
                () -> downloader.download(URL, SHA, notDirectory, 50_000_000));
        assertEquals(1, error.exitCode());
        assertTrue(error.getMessage().contains("cache folder"));
        assertEquals(0, server.requestCount.get());
    }
}
