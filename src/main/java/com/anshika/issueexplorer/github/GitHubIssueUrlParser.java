package com.anshika.issueexplorer.github;

import java.net.URI;
import java.net.URISyntaxException;

public final class GitHubIssueUrlParser {
    public record IssueReference(String owner, String repo, long issueNumber) { }

    public IssueReference parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Issue URL must not be empty.");
        }

        final URI uri;
        try {
            uri = new URI(input);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Malformed issue URL; remove whitespace or extra junk.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Issue URL must use https://.");
        }
        if (!"github.com".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("Issue URL must use the github.com host.");
        }
        if (uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Issue URL must not contain credentials, a port, query, or fragment.");
        }

        String[] parts = uri.getRawPath().split("/", -1);
        if (parts.length < 4 || !parts[0].isEmpty()
                || !parts[1].matches("[A-Za-z0-9-]+")
                || !parts[2].matches("[A-Za-z0-9_.-]+")
                || parts[2].equals(".") || parts[2].equals("..")) {
            throw new IllegalArgumentException("Expected a GitHub issue URL: https://github.com/owner/repo/issues/number.");
        }
        if (parts[3].equals("pull") || parts[3].equals("pulls")) {
            throw new IllegalArgumentException("Pull request URLs are not supported; provide an issue URL.");
        }
        if (!parts[3].equals("issues")) {
            throw new IllegalArgumentException("Expected the /issues/ path in the GitHub URL.");
        }
        if (parts.length == 4 || (parts.length == 5 && parts[4].isEmpty())) {
            throw new IllegalArgumentException("Issue URL is missing an issue number.");
        }
        if (parts.length != 5) {
            throw new IllegalArgumentException("Issue URL contains extra path segments or a trailing slash.");
        }
        if (!parts[4].matches("[0-9]+")) {
            throw new IllegalArgumentException("Issue number must contain only decimal digits.");
        }

        final long issueNumber;
        try {
            issueNumber = Long.parseLong(parts[4]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Issue number is too large (maximum 9223372036854775807).");
        }
        if (issueNumber <= 0) {
            throw new IllegalArgumentException("Issue number must be greater than zero.");
        }
        return new IssueReference(parts[1], parts[2], issueNumber);
    }
}
