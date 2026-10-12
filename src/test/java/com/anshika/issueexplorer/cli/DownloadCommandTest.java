package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.MockGitHubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DownloadCommandTest {
    private static final String URL = "https://github.com/o/r/issues/123";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path cache;
    private MockGitHubServer server;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private byte[] archive;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockGitHubServer();
        server.respondAt("/repos/o/r", 200, "{\"private\":false}", Map.of());
        server.respondAt("/repos/o/r/commits?per_page=1", 200, "[{\"sha\":\"" + SHA + "\"}]", Map.of());
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("repo/Test.java"));
            zip.write("class Test {}".getBytes());
            zip.closeEntry();
        }
        archive = bytes.toByteArray();
        server.respondBytes("/repos/o/r/zipball/" + SHA, archive, false);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private CommandLine command() {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == DownloadCommand.class) {
                    return type.cast(new DownloadCommand(() -> new GitHubClient(server.baseUri(), null,
                            Duration.ofSeconds(3), Clock.systemUTC())));
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        return new CommandLine(new IssueExplorerCommand(), factory)
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    private int execute(String... additional) {
        var args = new java.util.ArrayList<>(java.util.List.of("download", URL, "--cache-dir", cache.toString()));
        args.addAll(java.util.List.of(additional));
        return command().execute(args.toArray(String[]::new));
    }

    @Test
    void downloadsAndReportsShaAndCacheLocation() throws Exception {
        assertEquals(0, execute());
        Path expected = cache.resolve("o/r/" + SHA + "/archive.zip");
        assertArrayEquals(archive, Files.readAllBytes(expected));
        assertEquals("Commit SHA: " + SHA + System.lineSeparator()
                + "Downloaded archive: " + expected + System.lineSeparator()
                + "Source directory: " + expected.resolveSibling("src") + System.lineSeparator()
                + "Java files kept: 1" + System.lineSeparator()
                + "Files skipped: 0" + System.lineSeparator()
                + "  symlinks: 0" + System.lineSeparator()
                + "  excluded directories: 0" + System.lineSeparator()
                + "  non-Java files: 0" + System.lineSeparator()
                + "Extracted Java bytes: 13" + System.lineSeparator(), output.toString());
        assertEquals("", errors.toString());
    }

    @Test
    void repeatedCommandReportsCacheHitAndSkipsArchiveRequest() {
        assertEquals(0, execute());
        output.getBuffer().setLength(0);
        assertEquals(0, execute());
        assertTrue(output.toString().contains("Already cached; skipped download:"));
        assertEquals(1, server.requests.stream().filter(request -> request.path().contains("/zipball/")).count());
    }

    @Test
    void specifiedRefIsUsed() {
        server.respondAt("/repos/o/r/commits/feature%2Ffix", 200, "{\"sha\":\"" + SHA + "\"}", Map.of());
        assertEquals(0, execute("--ref", "feature/fix"));
        assertTrue(server.requests.stream().anyMatch(request -> request.path().equals("/repos/o/r/commits/feature%2Ffix")));
    }

    @Test
    void configuredSmallLimitProducesSingleErrorAndExitFive() throws Exception {
        assertEquals(5, execute("--max-download-bytes", "10"));
        assertEquals("", output.toString());
        assertEquals("Error: Archive exceeds the maximum download size of 10 bytes." + System.lineSeparator(), errors.toString());
        assertFalse(Files.exists(cache.resolve("o/r/" + SHA + "/archive.zip")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "not-a-number"})
    void invalidLimitProducesUsageErrorWithoutRequests(String value) {
        assertEquals(2, execute("--max-download-bytes", value));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void missingUrlProducesUsageError() {
        assertEquals(2, command().execute("download"));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void invalidUrlMakesNoRequests() {
        assertEquals(2, command().execute("download", "https://other.example/o/r/issues/1"));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void pullRequestNeverDownloadsArchive() {
        server.respondAt("/repos/o/r/issues/123", 200, "{\"pull_request\":{}}", Map.of());
        assertEquals(2, execute());
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void privateRepositoryIsRejectedEvenWithIssueAccess() {
        server.respondAt("/repos/o/r", 200, "{\"private\":true}", Map.of());
        assertEquals(3, execute());
        assertTrue(errors.toString().contains("Only public repositories"));
        assertEquals(2, server.requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"private\":\"false\"}"})
    void invalidPublicVisibilityResponseFailsClosed(String response) {
        server.respondAt("/repos/o/r", 200, response, Map.of());
        assertEquals(1, execute());
        assertTrue(errors.toString().contains("invalid repository response"));
    }
    @Test
    void configuredExtractionSizeLimitReturnsFiveAndDoesNotPublishSources() {
        assertEquals(5, execute("--max-extracted-bytes", "1"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("maximum total extracted size"));
        assertFalse(Files.exists(cache.resolve("o/r/" + SHA + "/src")));
        assertTrue(Files.exists(cache.resolve("o/r/" + SHA + "/archive.zip")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--max-files", "--max-extracted-bytes"})
    void invalidExtractionLimitsMakeNoNetworkRequests(String option) {
        assertEquals(2, execute(option, "0"));
        assertEquals(0, server.requestCount.get());
    }
    @Test
    void maliciousArchiveIsRejectedByDownloadCommand() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../Escape.java"));
            zip.write("malicious".getBytes());
            zip.closeEntry();
        }
        server.respondBytes("/repos/o/r/zipball/" + SHA, bytes.toByteArray(), false);
        assertEquals(2, execute());
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("zip-slip"));
        assertFalse(Files.exists(cache.resolve("o/r/" + SHA + "/src")));
        assertFalse(Files.exists(cache.resolve("o/r/Escape.java")));
    }

    @Test
    void reportsSkippedFileReasons() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String path : java.util.List.of("repo/Keep.java", "repo/README.md", "repo/target/Skip.java")) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write("x".getBytes());
                zip.closeEntry();
            }
        }
        server.respondBytes("/repos/o/r/zipball/" + SHA, bytes.toByteArray(), false);
        assertEquals(0, execute());
        assertTrue(output.toString().contains("Java files kept: 1"));
        assertTrue(output.toString().contains("Files skipped: 2"));
        assertTrue(output.toString().contains("excluded directories: 1"));
        assertTrue(output.toString().contains("non-Java files: 1"));
    }
}

