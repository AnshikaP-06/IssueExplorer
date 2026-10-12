package com.anshika.issueexplorer.github;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

public final class GitHubClient {
    public record Issue(String title, String body) { }

    public static final class GitHubException extends Exception {
        private final int exitCode;

        public GitHubException(String message, int exitCode) {
            super(message);
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    private final HttpClient httpClient;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final URI apiBase;
    private final String token;
    private final Duration timeout;
    private final Clock clock;

    public GitHubClient(String token) {
        this(URI.create("https://api.github.com/"), token, Duration.ofSeconds(30), Clock.systemUTC());
    }

    // An injectable endpoint and clock allow deterministic tests against a local server.
    public GitHubClient(URI apiBase, String token, Duration timeout, Clock clock) {
        this.apiBase = apiBase;
        this.token = token == null ? "" : token.strip();
        this.timeout = timeout;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Issue fetchIssue(String url) throws GitHubException {
        var reference = new GitHubIssueUrlParser().parse(url);
        JsonNode json = getJson("repos/" + reference.owner() + "/" + reference.repo()
                + "/issues/" + reference.issueNumber(),
                "Issue not found or not publicly accessible (HTTP 404).",
                "GitHub returned an invalid issue response.", false);
        if (!json.isObject()) {
            throw new GitHubException("GitHub returned an invalid issue response.", 1);
        }
        if (json.has("pull_request")) {
            throw new GitHubException("This URL refers to a pull request; provide an issue URL.", 2);
        }
        JsonNode title = json.get("title");
        JsonNode body = json.get("body");
        if (title == null || !title.isString() || body == null || (!body.isNull() && !body.isString())) {
            throw new GitHubException("GitHub returned an invalid issue response.", 1);
        }
        return new Issue(title.asString(), body.isNull() ? "" : body.asString());
    }

    public String resolveRef(String issueUrl, String ref) throws GitHubException {
        var reference = new GitHubIssueUrlParser().parse(issueUrl);
        if (ref != null && (ref.isBlank() || ref.equals(".") || ref.equals("..")
                || ref.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character)))) {
            throw new IllegalArgumentException("Ref must be a non-empty branch, tag, or commit without whitespace or control characters.");
        }
        String path = "repos/" + reference.owner() + "/" + reference.repo() + "/commits";
        boolean defaultBranch = ref == null;
        // The commits list defaults to the repository's default branch; do not assume main/master.
        path += defaultBranch ? "?per_page=1"
                : "/" + URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20");
        JsonNode json = getJson(path,
                defaultBranch ? "Repository not found or not publicly accessible (HTTP 404)."
                        : "Unknown ref or repository not publicly accessible (HTTP 404).",
                "GitHub returned an invalid commit response.", true);
        if (defaultBranch) {
            if (!json.isArray()) {
                throw new GitHubException("GitHub returned an invalid commit response.", 1);
            }
            if (json.isEmpty()) {
                throw new GitHubException("Repository is empty; no commit can be resolved.", 3);
            }
            json = json.get(0);
        }
        JsonNode sha = json.isObject() ? json.get("sha") : null;
        if (sha == null || !sha.isString() || !sha.asString().matches("[a-fA-F0-9]{40}")) {
            throw new GitHubException("GitHub returned an invalid full commit SHA.", 1);
        }
        return sha.asString().toLowerCase(Locale.ROOT);
    }

    private JsonNode getJson(String path, String notFoundMessage, String invalidResponseMessage,
                             boolean resolving) throws GitHubException {
        if (!token.chars().allMatch(character -> character >= 33 && character <= 126)) {
            throw new GitHubException("GITHUB_TOKEN contains invalid characters.", 1);
        }
        URI endpoint = apiBase.resolve(path);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "issue-explorer")
                .GET();
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }

        final HttpResponse<String> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException exception) {
            throw new GitHubException("GitHub request timed out; retry when the connection is available.", 1);
        } catch (IOException exception) {
            throw new GitHubException("Could not connect to GitHub or read its response; check your network and retry.", 1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubException("GitHub request was interrupted.", 1);
        }

        int status = response.statusCode();
        if (status == 404) {
            throw new GitHubException(notFoundMessage, 3);
        }
        if (status == 429 || (status == 403 && isRateLimited(response.headers(), response.body()))) {
            throw new GitHubException("GitHub rate limit exceeded. " + retryMessage(response.headers()), 4);
        }
        if (status == 401) {
            throw new GitHubException("GitHub authentication failed; check GITHUB_TOKEN (HTTP 401).", 1);
        }
        if (status >= 300 && status < 400) {
            throw new GitHubException("GitHub returned a redirect; redirects are not followed.", 1);
        }
        if (resolving && status == 409) {
            throw new GitHubException("Repository is empty; no commit can be resolved (HTTP 409).", 3);
        }
        if (resolving && status == 422) {
            throw new GitHubException("GitHub could not resolve the ref (HTTP 422); check the branch, tag, or commit.", 2);
        }
        if (status != 200) {
            throw new GitHubException("GitHub request failed (HTTP " + status + ").", 1);
        }

        final JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (JacksonException exception) {
            throw new GitHubException(invalidResponseMessage, 1);
        }
        if (json == null || json.isNull()) {
            throw new GitHubException(invalidResponseMessage, 1);
        }
        return json;
    }
    public void requirePublicRepository(String issueUrl) throws GitHubException {
        var reference = new GitHubIssueUrlParser().parse(issueUrl);
        var json = getJson("repos/" + reference.owner() + "/" + reference.repo(),
                "Repository not found or not publicly accessible (HTTP 404).",
                "GitHub returned an invalid repository response.", false);
        var privateFlag = json.isObject() ? json.get("private") : null;
        if (privateFlag == null || !privateFlag.isBoolean()) {
            throw new GitHubException("GitHub returned an invalid repository response.", 1);
        }
        if (privateFlag.asBoolean()) {
            throw new GitHubException("Only public repositories are supported.", 3);
        }
    }

    public HttpResponse<InputStream> openArchive(String issueUrl, String sha) throws GitHubException {
        var reference = new GitHubIssueUrlParser().parse(issueUrl);
        if (sha == null || !sha.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("Archive download requires a full 40-character commit SHA.");
        }
        if (!token.chars().allMatch(character -> character >= 33 && character <= 126)) {
            throw new GitHubException("GITHUB_TOKEN contains invalid characters.", 1);
        }
        URI endpoint = apiBase.resolve("repos/" + reference.owner() + "/" + reference.repo()
                + "/zipball/" + sha.toLowerCase(Locale.ROOT));
        var response = sendArchiveRequest(endpoint, true);
        if (response.statusCode() == 302) {
            String location = response.headers().firstValue("Location").orElse("");
            try (var ignored = response.body()) {
                final URI target;
                try {
                    target = endpoint.resolve(location);
                } catch (IllegalArgumentException exception) {
                    throw new GitHubException("GitHub returned an invalid archive redirect.", 1);
                }
                // A same-origin loopback redirect is allowed only for local mock-server tests.
                boolean local = "127.0.0.1".equals(apiBase.getHost())
                        && target.getHost() != null && target.getHost().equals(apiBase.getHost())
                        && target.getPort() == apiBase.getPort() && target.getScheme().equals(apiBase.getScheme());
                boolean codeload = "https".equals(target.getScheme())
                        && "codeload.github.com".equals(target.getHost()) && target.getPort() == -1;
                if (location.isEmpty() || (!local && !codeload) || target.getRawUserInfo() != null
                        || target.getRawFragment() != null) {
                    throw new GitHubException("Archive redirect rejected; expected GitHub's trusted archive host.", 1);
                }
                response = sendArchiveRequest(target, false);
            } catch (IOException exception) {
                throw new GitHubException("Could not read GitHub's archive redirect response.", 1);
            }
        }
        if (response.statusCode() == 200) {
            return response;
        }
        try (var body = response.body()) {
            String errorBody = new String(body.readNBytes(8192), StandardCharsets.UTF_8);
            int status = response.statusCode();
            if (status == 404) {
                throw new GitHubException("Repository archive not found or not publicly accessible (HTTP 404).", 3);
            }
            if (status == 429 || (status == 403 && isRateLimited(response.headers(), errorBody))) {
                throw new GitHubException("GitHub rate limit exceeded. " + retryMessage(response.headers()), 4);
            }
            if (status == 401) {
                throw new GitHubException("GitHub authentication failed; check GITHUB_TOKEN (HTTP 401).", 1);
            }
            throw new GitHubException("Repository archive request failed (HTTP " + status + ").", 1);
        } catch (IOException exception) {
            throw new GitHubException("Could not read GitHub's archive response; check your network and retry.", 1);
        }
    }

    private HttpResponse<InputStream> sendArchiveRequest(URI endpoint, boolean authenticate) throws GitHubException {
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "issue-explorer").GET();
        if (authenticate && !token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new GitHubException("Archive request timed out; retry when the connection is available.", 1);
        } catch (IOException exception) {
            throw new GitHubException("Could not download the archive; check your network and retry.", 1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitHubException("Archive request was interrupted.", 1);
        }
    }
    private boolean isRateLimited(HttpHeaders headers, String body) {
        if (headers.firstValue("x-ratelimit-remaining").orElse("").equals("0")
                || headers.firstValue("retry-after").isPresent()) {
            return true;
        }
        try {
            var json = mapper.readTree(body);
            var message = json == null ? null : json.get("message");
            return message != null && message.isString()
                    && message.asString().toLowerCase(Locale.ROOT).contains("rate limit");
        } catch (JacksonException exception) {
            return false;
        }
    }

    private String retryMessage(HttpHeaders headers) {
        Instant now = clock.instant();
        Instant retryAt = parseRetryAfter(headers.firstValue("retry-after").orElse("").strip());
        if (headers.firstValue("x-ratelimit-remaining").orElse("").equals("0")) {
            try {
                long reset = Long.parseLong(headers.firstValue("x-ratelimit-reset").orElse("").strip());
                if (reset >= 0) {
                    Instant primaryReset = Instant.ofEpochSecond(reset);
                    // Honor both limits if the primary and secondary windows overlap.
                    if (retryAt == null || primaryReset.isAfter(retryAt)) {
                        retryAt = primaryReset;
                    }
                }
            } catch (NumberFormatException | java.time.DateTimeException exception) {
                // Missing or malformed headers use the other valid header or a labeled fallback.
            }
        }
        boolean fallback = retryAt == null;
        if (fallback) {
            retryAt = now.plusSeconds(60);
        } else if (!retryAt.isAfter(now)) {
            retryAt = now.plusSeconds(1);
        }
        var wait = java.time.Duration.between(now, retryAt);
        long seconds = wait.getSeconds() + (wait.getNano() == 0 ? 0 : 1);
        return (fallback ? "Retry no earlier than " : "Retry at or after ") + retryAt
                + " (UTC; wait " + seconds + (seconds == 1 ? " second" : " seconds")
                + (fallback ? "; retry time not provided by GitHub" : "") + ").";
    }
    private Instant parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value);
            return seconds >= 0 ? clock.instant().plusSeconds(seconds) : null;
        } catch (NumberFormatException | java.time.DateTimeException | ArithmeticException exception) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }
}






