package com.anshika.issueexplorer.github;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitHubClientTest {
    private static final String URL = "https://github.com/owner/repo/issues/123";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC);
    private MockGitHubServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockGitHubServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private GitHubClient client(String token) {
        return new GitHubClient(server.baseUri(), token, Duration.ofSeconds(3), CLOCK);
    }

    @Test
    void fetchesTitleAndMultilineUnicodeBodyWithCorrectRequest() throws Exception {
        server.respond(200, "{\"title\":\"Booking café\",\"body\":\"First line\\nSecond line\",\"ignored\":1}", Map.of());
        assertEquals(new GitHubClient.Issue("Booking café", "First line\nSecond line"), client(null).fetchIssue(URL));
        assertEquals("GET", server.request.get().method());
        assertEquals("/repos/owner/repo/issues/123", server.request.get().path());
        assertEquals("application/vnd.github+json", server.request.get().accept());
        assertEquals("2022-11-28", server.request.get().apiVersion());
        assertEquals(1, server.requestCount.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void absentOrBlankTokenOmitsAuthorization(String token) throws Exception {
        client(token).fetchIssue(URL);
        assertNull(server.request.get().authorization());
    }

    @Test
    void optionalTokenIsSentAsBearer() throws Exception {
        String token = UUID.randomUUID().toString();
        client(token).fetchIssue(URL);
        assertEquals("Bearer " + token, server.request.get().authorization());
    }

    @Test
    void nullBodyIsAnEmptyString() throws Exception {
        server.respond(200, "{\"title\":\"Title\",\"body\":null}", Map.of());
        assertEquals(new GitHubClient.Issue("Title", ""), client(null).fetchIssue(URL));
    }

    @Test
    void notFoundHasSpecificMessageAndExitCode() {
        server.respond(404, "{}", Map.of());
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(3, failure.exitCode());
        assertEquals("Issue not found or not publicly accessible (HTTP 404).", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 429})
    void primaryRateLimitUsesResetHeader(int status) {
        server.respond(status, "{}", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1791633900"));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(4, failure.exitCode());
        assertEquals("GitHub rate limit exceeded. Retry at or after 2026-10-10T12:05:00Z (UTC; wait 300 seconds).", failure.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "120, 2026-10-10T12:02:00Z",
            "'Sat, 10 Oct 2026 12:03:00 GMT', 2026-10-10T12:03:00Z"
    })
    void secondaryRateLimitUsesRetryAfterWhenPrimaryBudgetRemains(String retryAfter, String expected) {
        server.respond(403, "{}", Map.of("Retry-After", retryAfter,
                "X-RateLimit-Remaining", "1", "X-RateLimit-Reset", "1791633900"));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(4, failure.exitCode());
        long seconds = java.time.Duration.between(CLOCK.instant(), java.time.Instant.parse(expected)).getSeconds();
        assertEquals("GitHub rate limit exceeded. Retry at or after " + expected + " (UTC; wait " + seconds + " seconds).", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "9223372036854775807", "-1"})
    void malformedResetDoesNotCrash(String reset) {
        server.respond(403, "{}", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", reset));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(4, failure.exitCode());
        assertTrue(failure.getMessage().contains("2026-10-10T12:01:00Z"));
        assertTrue(failure.getMessage().contains("retry time not provided"));
    }

    @Test
    void secondaryLimitWithoutHeadersUsesFallback() {
        server.respond(403, "{\"message\":\"You have exceeded a secondary rate limit.\"}", Map.of());
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(4, failure.exitCode());
        assertTrue(failure.getMessage().contains("2026-10-10T12:01:00Z"));
    }

    @Test
    void malformedRetryAfterFallsBackToReset() {
        server.respond(429, "{}", Map.of("Retry-After", "invalid", "X-RateLimit-Remaining", "0",
                "X-RateLimit-Reset", "1791633900"));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertTrue(failure.getMessage().contains("2026-10-10T12:05:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null"})
    void rejectsPullRequestReturnedByIssuesEndpoint(String pullRequest) {
        server.respond(200, "{\"title\":\"PR\",\"body\":\"Details\",\"pull_request\":" + pullRequest + "}", Map.of());
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(2, failure.exitCode());
        assertTrue(failure.getMessage().contains("pull request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{}", "{\"title\":3,\"body\":null}",
            "{\"title\":\"Title\",\"body\":{}}"})
    void invalidResponseHasSafeError(String body) {
        server.respond(200, body, Map.of());
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertEquals(1, failure.exitCode());
        assertEquals("GitHub returned an invalid issue response.", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 500})
    void unexpectedStatusDoesNotEchoResponseOrToken(int status) {
        String token = UUID.randomUUID().toString();
        server.respond(status, "{\"message\":\"" + token + "\"}", Map.of());
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(token).fetchIssue(URL));
        assertEquals(1, failure.exitCode());
        assertTrue(failure.getMessage().contains("HTTP " + status));
        assertFalse(failure.getMessage().contains(token));
    }

    @Test
    void redirectsAreNeverFollowed() {
        server.respond(302, "{}", Map.of("Location", server.baseUri().resolve("redirect-target").toString()));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertTrue(failure.getMessage().contains("redirect"));
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void connectionFailureHasClearError() {
        GitHubClient client = client(null);
        server.close();
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client.fetchIssue(URL));
        assertEquals(1, failure.exitCode());
        assertTrue(failure.getMessage().contains("check your network"));
    }

    @Test
    void requestTimeoutHasClearError() {
        server.delay(1500);
        var client = new GitHubClient(server.baseUri(), null, Duration.ofMillis(300), CLOCK);
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client.fetchIssue(URL));
        assertEquals(1, failure.exitCode());
        assertTrue(failure.getMessage().contains("timed out"));
    }

    @Test
    void interruptionRestoresInterruptFlag() {
        GitHubClient client = client(null);
        try {
            Thread.currentThread().interrupt();
            var failure = assertThrows(GitHubClient.GitHubException.class, () -> client.fetchIssue(URL));
            assertEquals("GitHub request was interrupted.", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidUrlNeverMakesARequest() {
        assertThrows(IllegalArgumentException.class,
                () -> client(null).fetchIssue("https://example.com/o/r/issues/1"));
        assertEquals(0, server.requestCount.get());
    }

    @Test
    void invalidTokenHasSafeError() {
        var failure = assertThrows(GitHubClient.GitHubException.class,
                () -> client("invalid\r\nvalue").fetchIssue(URL));
        assertEquals("GITHUB_TOKEN contains invalid characters.", failure.getMessage());
        assertEquals(0, server.requestCount.get());
    }
    @Test
    void overlappingRateLimitsUseTheLaterSafeRetryTime() {
        server.respond(403, "{}", Map.of("Retry-After", "30", "X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1791633900"));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertTrue(failure.getMessage().contains("2026-10-10T12:05:00Z"));
        assertTrue(failure.getMessage().contains("wait 300 seconds"));
    }

    @Test
    void pastResetTimeDoesNotTellUserToRetryInThePast() {
        server.respond(403, "{}", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1"));
        var failure = assertThrows(GitHubClient.GitHubException.class, () -> client(null).fetchIssue(URL));
        assertTrue(failure.getMessage().contains("2026-10-10T12:00:01Z"));
        assertTrue(failure.getMessage().contains("wait 1 second"));
    }
}


