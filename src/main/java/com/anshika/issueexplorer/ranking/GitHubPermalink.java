package com.anshika.issueexplorer.ranking;

import com.anshika.issueexplorer.github.GitHubIssueUrlParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class GitHubPermalink {
    public String build(String repository, String sha, String path, int startLine, int endLine) {
        new GitHubIssueUrlParser().parse("https://github.com/" + repository + "/issues/1");
        if (sha == null || !sha.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("Permalink requires a full 40-character commit SHA.");
        }
        validatePath(path);
        if (startLine <= 0 || endLine < startLine) {
            throw new IllegalArgumentException("Permalink line range must be positive and ordered.");
        }
        String encoded = Arrays.stream(path.split("/")).map(segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")).collect(Collectors.joining("/"));
        return "https://github.com/" + repository + "/blob/" + sha.toLowerCase(java.util.Locale.ROOT)
                + "/" + encoded + "#L" + startLine + "-L" + endLine;
    }

    public static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            throw new IllegalArgumentException("Source path must be relative to the repository.");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.chars().anyMatch(character -> Character.isISOControl(character))) {
                throw new IllegalArgumentException("Source path must not contain traversal or empty segments.");
            }
        }
    }
}
