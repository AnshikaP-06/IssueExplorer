package com.anshika.issueexplorer.github;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubIssueUrlParserTest {
    private final GitHubIssueUrlParser parser = new GitHubIssueUrlParser();

    @ParameterizedTest
    @CsvSource({
            "https://github.com/anshika/issue-explorer/issues/1, anshika, issue-explorer, 1",
            "https://github.com/openjdk/jdk/issues/123, openjdk, jdk, 123",
            "https://github.com/apache/lucene/issues/456, apache, lucene, 456",
            "https://github.com/Owner/Repo/issues/7, Owner, Repo, 7",
            "https://github.com/team-name/repo_name/issues/8, team-name, repo_name, 8",
            "https://github.com/user/repo.name/issues/9, user, repo.name, 9",
            "https://github.com/a/b/issues/10, a, b, 10",
            "https://GITHUB.COM/user/repo/issues/11, user, repo, 11",
            "https://github.com/user/repo/issues/0012, user, repo, 12",
            "https://github.com/user/repo/issues/9223372036854775807, user, repo, 9223372036854775807"
    })
    void parsesValidIssueUrls(String url, String owner, String repo, long number) {
        assertEquals(new GitHubIssueUrlParser.IssueReference(owner, repo, number), parser.parse(url));
    }

    @ParameterizedTest
    @CsvSource(value = {
            "NULL|Issue URL must not be empty.",
            "''|Issue URL must not be empty.",
            "'   '|Issue URL must not be empty.",
            "https://github.com/o/r/issues/1 junk|Malformed issue URL; remove whitespace or extra junk.",
            "http://github.com/o/r/issues/1|Issue URL must use https://.",
            "github.com/o/r/issues/1|Issue URL must use https://.",
            "https://example.com/o/r/issues/1|Issue URL must use the github.com host.",
            "https://github.com.evil.example/o/r/issues/1|Issue URL must use the github.com host.",
            "https://www.github.com/o/r/issues/1|Issue URL must use the github.com host.",
            "https://github.com/o/r/pull/1|Pull request URLs are not supported; provide an issue URL.",
            "https://github.com/o/r/pulls/1|Pull request URLs are not supported; provide an issue URL.",
            "https://github.com/o/r/issues|Issue URL is missing an issue number.",
            "https://github.com/o/r/issues/|Issue URL is missing an issue number.",
            "https://github.com/o/r/issues/1/extra|Issue URL contains extra path segments or a trailing slash.",
            "https://github.com/o/r/issues/1/|Issue URL contains extra path segments or a trailing slash.",
            "https://github.com/o/r/issues/abc|Issue number must contain only decimal digits.",
            "https://github.com/o/r/issues/1junk|Issue number must contain only decimal digits.",
            "https://github.com/o/r/issues/-1|Issue number must contain only decimal digits.",
            "https://github.com/o/r/issues/0|Issue number must be greater than zero.",
            "https://github.com/o/r/issues/9223372036854775808|Issue number is too large (maximum 9223372036854775807).",
            "https://github.com/o/r/issues/1?x=y|Issue URL must not contain credentials, a port, query, or fragment.",
            "https://github.com/o/r/issues/1#comment|Issue URL must not contain credentials, a port, query, or fragment.",
            "https://user@github.com/o/r/issues/1|Issue URL must not contain credentials, a port, query, or fragment.",
            "https://github.com:443/o/r/issues/1|Issue URL must not contain credentials, a port, query, or fragment.",
            "https://github.com/o/r/tree/1|Expected the /issues/ path in the GitHub URL.",
            "https://github.com//r/issues/1|Expected a GitHub issue URL: https://github.com/owner/repo/issues/number.",
            "https://github.com/o//issues/1|Expected a GitHub issue URL: https://github.com/owner/repo/issues/number.",
            "https://github.com/o/../issues/1|Expected a GitHub issue URL: https://github.com/owner/repo/issues/number.",
            "https://github.com/o/r/issues/%31|Issue number must contain only decimal digits.",
            "https://github.com/o%2Fother/r/issues/1|Expected a GitHub issue URL: https://github.com/owner/repo/issues/number."
    }, delimiter = '|', nullValues = "NULL", ignoreLeadingAndTrailingWhitespace = false)
    void rejectsInvalidIssueUrlsWithSpecificErrors(String url, String message) {
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(url));
        assertEquals(message, error.getMessage());
    }
}
