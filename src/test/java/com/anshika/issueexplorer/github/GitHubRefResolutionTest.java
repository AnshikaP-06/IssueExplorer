package com.anshika.issueexplorer.github;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitHubRefResolutionTest {
    private static final String URL = "https://github.com/owner/repo/issues/123";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private MockGitHubServer server;
    private GitHubClient client;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockGitHubServer();
        client = new GitHubClient(server.baseUri(), null, Duration.ofSeconds(3),
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @ParameterizedTest
    @CsvSource({
            "main, main",
            "develop, develop",
            "v1.2.3, v1.2.3",
            "feature/fix, feature%2Ffix",
            "heads/feature/fix, heads%2Ffeature%2Ffix",
            "tags/v1.2.3, tags%2Fv1.2.3",
            "0123456, 0123456",
            "0123456789abcdef0123456789abcdef01234567, 0123456789abcdef0123456789abcdef01234567",
            "release+fix, release%2Bfix",
            "feature#query?x, feature%23query%3Fx",
            "feature%2Ffix, feature%252Ffix"
    })
    void resolvesBranchTagOrCommitAsOneEncodedPathSegment(String ref, String encoded) throws Exception {
        server.respond(200, "{\"sha\":\"" + SHA + "\"}", Map.of());
        assertEquals(SHA, client.resolveRef(URL, ref));
        assertEquals("/repos/owner/repo/commits/" + encoded, server.request.get().path());
        assertEquals("GET", server.request.get().method());
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void omittedRefUsesDefaultBranchWithOneRequest() throws Exception {
        server.respond(200, "[{\"sha\":\"" + SHA + "\"}]", Map.of());
        assertEquals(SHA, client.resolveRef(URL, null));
        assertEquals("/repos/owner/repo/commits?per_page=1", server.request.get().path());
        assertEquals(1, server.requestCount.get());
    }

    @Test
    void normalizesFullShaToLowercase() throws Exception {
        server.respond(200, "{\"sha\":\"" + SHA.toUpperCase(java.util.Locale.ROOT) + "\"}", Map.of());
        assertEquals(SHA, client.resolveRef(URL, "main"));
    }

    @Test
    void refRequestUsesOptionalAuthentication() throws Exception {
        String token = UUID.randomUUID().toString();
        var authenticated = new GitHubClient(server.baseUri(), token, Duration.ofSeconds(3), Clock.systemUTC());
        server.respond(200, "{\"sha\":\"" + SHA + "\"}", Map.of());
        authenticated.resolveRef(URL, "main");
        assertEquals("Bearer " + token, server.request.get().authorization());
        assertEquals("application/vnd.github+json", server.request.get().accept());
        assertEquals("2022-11-28", server.request.get().apiVersion());
    }

    @Test
    void unknownRefIsClearNotFoundError() {
        server.respond(404, "{}", Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, "missing"));
        assertEquals(3, error.exitCode());
        assertEquals("Unknown ref or repository not publicly accessible (HTTP 404).", error.getMessage());
    }

    @Test
    void missingRepositoryForDefaultBranchIsReported() {
        server.respond(404, "{}", Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, null));
        assertEquals(3, error.exitCode());
        assertTrue(error.getMessage().contains("Repository not found"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @ValueSource(strings = {"main"})
    void emptyRepositoryConflictIsReported(String ref) {
        server.respond(409, "{\"message\":\"Git Repository is empty.\"}", Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, ref));
        assertEquals(3, error.exitCode());
        assertEquals("Repository is empty; no commit can be resolved (HTTP 409).", error.getMessage());
    }

    @Test
    void emptyDefaultCommitListIsReported() {
        server.respond(200, "[]", Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, null));
        assertEquals(3, error.exitCode());
        assertEquals("Repository is empty; no commit can be resolved.", error.getMessage());
    }

    @Test
    void unresolvableRefValidationErrorIsReported() {
        server.respond(422, "{}", Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, "missing"));
        assertEquals(2, error.exitCode());
        assertTrue(error.getMessage().contains("could not resolve the ref"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "branch name", "branch\nname"})
    void invalidRefNeverMakesARequest(String ref) {
        var error = assertThrows(IllegalArgumentException.class, () -> client.resolveRef(URL, ref));
        assertTrue(error.getMessage().startsWith("Ref must be"));
        assertEquals(0, server.requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "[]", "null", "{\"sha\":null}", "{\"sha\":12}",
            "{\"sha\":\"abc123\"}", "{\"sha\":\"zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz\"}"})
    void rejectsInvalidCommitResponse(String body) {
        server.respond(200, body, Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, "main"));
        assertEquals(1, error.exitCode());
        assertTrue(error.getMessage().startsWith("GitHub returned an invalid"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[null]", "[{}]", "[\"not a commit\"]"})
    void rejectsInvalidDefaultBranchResponse(String body) {
        server.respond(200, body, Map.of());
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, null));
        assertEquals(1, error.exitCode());
    }

    @Test
    void rateLimitHandlingIsReusedForCommitRequests() {
        server.respond(403, "{}", Map.of("X-RateLimit-Remaining", "0", "X-RateLimit-Reset", "1791633900"));
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, "main"));
        assertEquals(4, error.exitCode());
        assertTrue(error.getMessage().contains("2026-10-10T12:05:00Z"));
    }

    @Test
    void networkFailureIsReusedForCommitRequests() {
        server.close();
        var error = assertThrows(GitHubClient.GitHubException.class, () -> client.resolveRef(URL, "main"));
        assertEquals(1, error.exitCode());
        assertTrue(error.getMessage().contains("check your network"));
    }

    @Test
    void invalidIssueUrlDoesNotMakeARequest() {
        assertThrows(IllegalArgumentException.class,
                () -> client.resolveRef("https://other.example/owner/repo/issues/1", "main"));
        assertEquals(0, server.requestCount.get());
    }
}
