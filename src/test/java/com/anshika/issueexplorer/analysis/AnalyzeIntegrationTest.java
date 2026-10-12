package com.anshika.issueexplorer.analysis;

import com.anshika.issueexplorer.cli.AnalyzeCommand;
import com.anshika.issueexplorer.cli.IssueExplorerCommand;
import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.MockGitHubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeIntegrationTest {
    private static final String URL = "https://github.com/owner/repo/issues/123";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path cache;
    private MockGitHubServer server;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private byte[] archive;
    private String token;

    private byte[] resource(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, "Missing resource: " + name);
            return input.readAllBytes();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockGitHubServer();
        archive = resource("fixture-archive.zip");
        server.respondAt("/repos/owner/repo/issues/123", 200, mapper.writeValueAsString(Map.of(
                "title", "BookingService cancellation deadline", "body", new String(resource("fixture-issues/explicit-class.txt"), StandardCharsets.UTF_8))), Map.of());
        server.respondAt("/repos/owner/repo", 200, "{\"private\":false}", Map.of());
        server.respondAt("/repos/owner/repo/commits?per_page=1", 200, "[{\"sha\":\"" + SHA + "\"}]", Map.of());
        server.respondAt("/repos/owner/repo/commits/feature%2Ffix", 200, "{\"sha\":\"" + SHA + "\"}", Map.of());
        server.respondBytes("/repos/owner/repo/zipball/" + SHA, archive, false);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private CommandLine command() {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == AnalyzeCommand.class) {
                    return type.cast(new AnalyzeCommand(() -> new GitHubClient(server.baseUri(), token,
                            Duration.ofSeconds(3), Clock.systemUTC())));
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        return new CommandLine(new IssueExplorerCommand(), factory)
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    private int run(String... additional) {
        var args = new java.util.ArrayList<>(List.of("analyze", URL, "--cache-dir", cache.toString()));
        args.addAll(List.of(additional));
        return command().execute(args.toArray(String[]::new));
    }

    @Test
    void fullPipelineUsesPrebuiltZipAndProducesEvidenceCoverageAndProgress() throws Exception {
        token = UUID.randomUUID().toString();
        assertEquals(0, run("--json"));
        var report = mapper.readTree(output.toString());
        assertEquals(URL, report.get("issueUrl").asString());
        assertEquals(SHA, report.get("commitSha").asString());
        assertEquals("owner/repo", report.get("repository").asString());
        assertFalse(report.get("archiveCached").asBoolean());
        assertFalse(report.get("indexCached").asBoolean());
        var coverage = report.get("coverage");
        assertEquals(15, coverage.get("filesIndexed").asInt());
        assertEquals(4, coverage.get("filesSkipped").asInt());
        assertEquals(1, coverage.get("filesFailed").asInt());
        assertEquals(33, coverage.get("methodsIndexed").asInt());
        assertEquals(2, coverage.get("skippedByReason").get("excluded directories").asInt());
        var first = report.get("results").get(0);
        assertEquals("src/main/java/com/example/fixture/booking/BookingService.java", first.get("path").asString());
        assertEquals("cancelBooking", first.get("method").asString());
        assertFalse(first.get("matchedTerms").isEmpty());
        assertTrue(first.get("permalink").asString().startsWith("https://github.com/owner/repo/blob/" + SHA + "/"));
        var source = Files.readAllLines(cache.resolve("owner/repo/" + SHA + "/src/" + first.get("path").asString()));
        for (var line : first.get("snippet").get("lines")) {
            assertEquals(source.get(line.get("number").asInt() - 1), line.get("text").asString());
        }
        for (int stage = 1; stage <= 9; stage++) {
            assertTrue(errors.toString().contains("[" + stage + "/9]"));
        }
        assertTrue(errors.toString().contains("Coverage: files indexed=15, skipped=4, failed=1"));
        assertFalse(output.toString().contains(token));
        assertFalse(errors.toString().contains(token));
        assertEquals(4, server.requestCount.get());
        assertTrue(server.requests.stream().allMatch(request -> ("Bearer " + token).equals(request.authorization())));
    }

    @Test
    void secondRunReusesArchiveAndIndexWithIdenticalResults() throws Exception {
        assertEquals(0, run("--json"));
        var first = mapper.readTree(output.toString());
        output.getBuffer().setLength(0);
        errors.getBuffer().setLength(0);
        assertEquals(0, run("--json"));
        var second = mapper.readTree(output.toString());
        assertTrue(second.get("archiveCached").asBoolean());
        assertTrue(second.get("indexCached").asBoolean());
        assertEquals(first.get("results"), second.get("results"));
        assertEquals(first.get("coverage"), second.get("coverage"));
        assertTrue(errors.toString().contains("Archive cache hit"));
        assertTrue(errors.toString().contains("Index cache hit"));
        assertEquals(1, server.requests.stream().filter(request -> request.path().contains("/zipball/")).count());
    }

    @Test
    void customRefAndTopAreApplied() throws Exception {
        assertEquals(0, run("--ref", "feature/fix", "--top", "2", "--json"));
        assertEquals(2, mapper.readTree(output.toString()).get("results").size());
        assertTrue(server.requests.stream().anyMatch(request -> request.path().endsWith("/commits/feature%2Ffix")));
    }

    @Test
    void humanOutputIncludesEvidenceAndCoverage() {
        assertEquals(0, run("--top", "1"));
        assertTrue(output.toString().contains("Issue: BookingService cancellation deadline"));
        assertTrue(output.toString().contains("Method: cancelBooking"));
        assertTrue(output.toString().contains("Matched terms:"));
        assertTrue(output.toString().contains("Snippet (lines"));
        assertTrue(output.toString().contains("https://github.com/owner/repo/blob/" + SHA));
        assertTrue(output.toString().contains("Coverage: files indexed=15, skipped=4, failed=1"));
    }

    @Test
    void invalidUrlMakesNoRequests() {
        assertEquals(2, command().execute("analyze", "https://example.com/owner/repo/issues/123", "--cache-dir", cache.toString()));
        assertEquals(0, server.requestCount.get());
        assertEquals("", output.toString());
    }

    @ParameterizedTest
    @CsvSource({"--top,0", "--top,-1", "--max-files,0", "--max-extracted-bytes,0", "--max-download-bytes,-1"})
    void invalidLimitsAreUsageErrorsBeforeRequests(String flag, String value) {
        assertEquals(2, run(flag, value));
        assertEquals(0, server.requestCount.get());
        assertEquals("", output.toString());
    }

    @Test
    void missingUrlIsAUsageError() {
        assertEquals(2, command().execute("analyze"));
    }

    @ParameterizedTest
    @CsvSource({"404,3,not found", "429,4,rate limit", "500,1,HTTP 500"})
    void issueHttpFailuresHaveCorrectCodesAndNoPartialOutput(int status, int code, String expected) {
        server.respondAt("/repos/owner/repo/issues/123", status, "{}", Map.of("Retry-After", "120"));
        assertEquals(code, run("--json"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains(expected));
        assertEquals(1, errors.toString().lines().filter(line -> line.startsWith("Error:")).count());
        assertFalse(errors.toString().contains("Exception"));
    }

    @Test
    void privateRepositoryIsRejected() {
        server.respondAt("/repos/owner/repo", 200, "{\"private\":true}", Map.of());
        assertEquals(3, run());
        assertTrue(errors.toString().contains("Only public repositories"));
        assertEquals(2, server.requestCount.get());
    }

    @Test
    void pullRequestResponseIsRejected() {
        server.respondAt("/repos/owner/repo/issues/123", 200, "{\"pull_request\":{}}", Map.of());
        assertEquals(2, run());
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void unknownRefAndEmptyRepositoryAreReported() {
        server.respondAt("/repos/owner/repo/commits/feature%2Ffix", 404, "{}", Map.of());
        assertEquals(3, run("--ref", "feature/fix"));
        assertTrue(errors.toString().contains("Unknown ref"));
        server.respondAt("/repos/owner/repo/commits?per_page=1", 409, "{}", Map.of());
        assertEquals(3, run());
        assertTrue(errors.toString().contains("Repository is empty"));
    }

    @ParameterizedTest
    @CsvSource({"--max-download-bytes,1", "--max-files,1", "--max-extracted-bytes,1"})
    void sizeAndCountLimitsReturnFiveWithoutPartialResults(String flag, String value) {
        assertEquals(5, run(flag, value, "--json"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("exceeds"));
        assertFalse(Files.exists(cache.resolve("owner/repo/" + SHA + "/src")));
    }

    @Test
    void networkFailureHasClearErrorAndNoStackTrace() {
        server.close();
        assertEquals(1, run("--json"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("check your network"));
        assertFalse(errors.toString().contains("Exception"));
    }

    @Test
    void noMatchHasEmptyJsonResultsWithCoverage() throws Exception {
        server.respondAt("/repos/owner/repo/issues/123", 200, mapper.writeValueAsString(Map.of(
                "title", "Offline GPS dot", "body", new String(resource("fixture-issues/no-match.txt"), StandardCharsets.UTF_8))), Map.of());
        assertEquals(0, run("--json"));
        var report = mapper.readTree(output.toString());
        assertTrue(report.get("results").isEmpty());
        assertEquals(15, report.get("coverage").get("filesIndexed").asInt());
    }

    @Test
    void maliciousZipSlipFailsBeforeAnySourceIsPublished() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../Escape.java"));
            zip.write("malicious".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        server.respondBytes("/repos/owner/repo/zipball/" + SHA, bytes.toByteArray(), false);
        assertEquals(2, run());
        assertTrue(errors.toString().contains("zip-slip"));
        assertEquals("", output.toString());
        assertFalse(Files.exists(cache.resolve("owner/repo/" + SHA + "/src")));
    }

    @Test
    void cacheFilesystemFailureReturnsOne() throws Exception {
        Path file = cache.resolve("not-a-directory");
        Files.writeString(file, "file");
        assertEquals(1, command().execute("analyze", URL, "--cache-dir", file.toString()));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("cache"));
    }

    @Test
    void sourceFilesWithNoMethodsAreCountedAsSkipped() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("repo/Empty.java"));
            zip.write("class Empty {}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        server.respondBytes("/repos/owner/repo/zipball/" + SHA, bytes.toByteArray(), false);
        assertEquals(0, run("--json"));
        var coverage = mapper.readTree(output.toString()).get("coverage");
        assertEquals(0, coverage.get("filesIndexed").asInt());
        assertEquals(1, coverage.get("filesSkipped").asInt());
        assertEquals(1, coverage.get("skippedByReason").get("Java files without methods").asInt());
    }
    @Test
    void parallelAndSequentialPipelinesHaveIdenticalEvidenceAndCoverage() throws Exception {
        assertEquals(0, run("--threads", "1", "--json"));
        var sequential = mapper.readTree(output.toString());
        output.getBuffer().setLength(0);
        errors.getBuffer().setLength(0);
        assertEquals(0, run("--threads", "4", "--json"));
        var parallel = mapper.readTree(output.toString());
        assertEquals(sequential.get("results"), parallel.get("results"));
        assertEquals(sequential.get("coverage"), parallel.get("coverage"));
        assertTrue(parallel.get("indexCached").asBoolean());
        var timings = parallel.get("timingsMs");
        assertEquals(10, timings.size());
        for (var timing : timings) {
            assertTrue(timing.asDouble() >= 0);
        }
        assertTrue(errors.toString().contains("Timing parse:"));
        assertTrue(errors.toString().contains("Timing total:"));
        assertFalse(output.toString().contains("Timing parse:"));
    }

    @Test
    void configSettingsApplyAndExplicitFlagsOverrideThem() throws Exception {
        Path config = cache.resolve("test.properties");
        Files.writeString(config, "max.download.bytes=1\nmax.java.files=1\nmax.extracted.bytes=1\nparser.threads=1\ntop.results=1\n");
        assertEquals(5, run("--config", config.toString()));
        output.getBuffer().setLength(0);
        errors.getBuffer().setLength(0);
        assertEquals(0, run("--config", config.toString(), "--max-download-bytes", "50000000",
                "--max-files", "5000", "--max-extracted-bytes", "200000000", "--threads", "4", "--top", "2", "--json"));
        assertEquals(2, mapper.readTree(output.toString()).get("results").size());
    }

    @Test
    void configuredTopCountAppliesWithoutATopFlag() throws Exception {
        Path config = cache.resolve("top.properties");
        Files.writeString(config, "top.results=1\nparser.threads=2\n");
        assertEquals(0, run("--config", config.toString(), "--json"));
        assertEquals(1, mapper.readTree(output.toString()).get("results").size());
    }

    @Test
    void invalidThreadFlagFailsBeforeNetwork() {
        assertEquals(2, run("--threads", "0"));
        assertEquals(0, server.requestCount.get());
        assertEquals("", output.toString());
    }
}

