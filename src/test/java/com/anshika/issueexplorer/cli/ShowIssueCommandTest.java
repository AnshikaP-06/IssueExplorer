package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.MockGitHubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ShowIssueCommandTest {
    private MockGitHubServer server;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();
    private String token;
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @BeforeEach
    void startServer() throws Exception {
        server = new MockGitHubServer();
        server.respondAt("/repos/o/r/commits?per_page=1", 200, "[{\"sha\":\"" + SHA + "\"}]", Map.of());
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private CommandLine command() {
        // Use the real root registration, replacing only construction of the client in tests.
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == ShowIssueCommand.class) {
                    return type.cast(new ShowIssueCommand(() -> new GitHubClient(server.baseUri(), token,
                            Duration.ofSeconds(2), Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC))));
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        return new CommandLine(new IssueExplorerCommand(), factory)
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    @Test
    void printsTitleAndBodyWithoutToken() {
        token = UUID.randomUUID().toString();
        server.respond(200, "{\"title\":\"Booking issue\",\"body\":\"First line\\nSecond line\"}", Map.of());
        assertEquals(0, command().execute("show-issue", "https://github.com/o/r/issues/123"));
        assertEquals(String.join(System.lineSeparator(), "Title: Booking issue", "Body:",
                "First line\nSecond line", "Commit SHA: " + SHA, ""), output.toString());
        assertEquals("", errors.toString());
        assertFalse(output.toString().contains(token));
        assertEquals("Bearer " + token, server.request.get().authorization());
    }

    @ParameterizedTest
    @CsvSource({"404, 3, not found", "429, 4, Retry at or after", "500, 1, HTTP 500"})
    void httpErrorsPrintOneLineWithExpectedExitCode(int status, int expectedExit, String message) {
        token = UUID.randomUUID().toString();
        server.respond(status, "{\"message\":\"" + token + "\"}", Map.of("Retry-After", "120"));
        assertEquals(expectedExit, command().execute("show-issue", "https://github.com/o/r/issues/123"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains(message));
        assertTrue(errors.toString().startsWith("Error: "));
        assertEquals(1, errors.toString().lines().count());
        assertFalse(errors.toString().contains(token));
    }

    @Test
    void pullRequestResponseIsRejected() {
        server.respond(200, "{\"pull_request\":{}}", Map.of());
        assertEquals(2, command().execute("show-issue", "https://github.com/o/r/issues/1"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("pull request"));
    }

    @Test
    void invalidUrlIsRejectedBeforeNetworkAccess() {
        assertEquals(2, command().execute("show-issue", "https://other.example/o/r/issues/1"));
        assertEquals(0, server.requestCount.get());
        assertTrue(errors.toString().contains("github.com"));
    }

    @Test
    void missingUrlIsAUsageError() {
        assertEquals(2, command().execute("show-issue"));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void networkFailurePrintsOneErrorLine() {
        var command = command();
        server.close();
        assertEquals(1, command.execute("show-issue", "https://github.com/o/r/issues/1"));
        assertTrue(errors.toString().contains("check your network"));
        assertEquals(1, errors.toString().lines().count());
        assertEquals("", output.toString());
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"main", "v1.0.0", "feature/fix", "0123456"})
    void suppliedRefIsResolvedAndPrinted(String ref) {
        String encoded = java.net.URLEncoder.encode(ref, java.nio.charset.StandardCharsets.UTF_8);
        server.respondAt("/repos/o/r/commits/" + encoded, 200, "{\"sha\":\"" + SHA + "\"}", Map.of());
        assertEquals(0, command().execute("show-issue", "https://github.com/o/r/issues/123", "--ref", ref));
        assertTrue(output.toString().endsWith("Commit SHA: " + SHA + System.lineSeparator()));
        assertEquals(2, server.requestCount.get());
        assertEquals("/repos/o/r/commits/" + encoded, server.request.get().path());
    }

    @ParameterizedTest
    @CsvSource({"404, 3, Unknown ref", "409, 3, Repository is empty", "429, 4, Retry at or after"})
    void resolutionErrorsProduceNoPartialOutput(int status, int expectedExit, String expectedError) {
        server.respondAt("/repos/o/r/commits/missing", status, "{}", Map.of("Retry-After", "120"));
        assertEquals(expectedExit, command().execute("show-issue", "https://github.com/o/r/issues/123", "--ref", "missing"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains(expectedError));
        assertEquals(1, errors.toString().lines().count());
    }

    @Test
    void emptyDefaultBranchIsReported() {
        server.respondAt("/repos/o/r/commits?per_page=1", 200, "[]", Map.of());
        assertEquals(3, command().execute("show-issue", "https://github.com/o/r/issues/123"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("Repository is empty"));
    }

    @Test
    void refFlagRequiresAValue() {
        assertEquals(2, command().execute("show-issue", "https://github.com/o/r/issues/123", "--ref"));
        assertEquals(0, server.requestCount.get());
    }
}

