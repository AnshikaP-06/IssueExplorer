package com.anshika.issueexplorer.evaluation;

import com.anshika.issueexplorer.github.GitHubIssueUrlParser;
import java.util.*;

/** File-level metrics; duplicate predictions count only once. Empty labels score zero. */
public final class Evaluation {
    public record Entry(String issueUrl, String commitSha, List<String> relevantFiles, String split) {
        public Entry {
            new GitHubIssueUrlParser().parse(issueUrl);
            if (commitSha == null || !commitSha.matches("[a-fA-F0-9]{40}"))
                throw new IllegalArgumentException("commitSha must be a full 40-character commit SHA.");
            if (!"dev".equals(split) && !"heldout".equals(split))
                throw new IllegalArgumentException("split must be dev or heldout.");
            if (relevantFiles == null) throw new IllegalArgumentException("relevantFiles must be an array.");
            for (String path : relevantFiles) {
                if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                        || path.contains(":") || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals("..")))
                    throw new IllegalArgumentException("relevantFiles must contain repository-relative file paths using forward slashes.");
            }
            relevantFiles = List.copyOf(new LinkedHashSet<>(relevantFiles));
        }
    }
    public record Metrics(double hitAt5, double recallAt5, double mrr) {}
    public static Metrics metrics(List<String> ranked, List<String> relevant) {
        var labels = new HashSet<>(relevant);
        var predictions = new ArrayList<>(new LinkedHashSet<>(ranked));
        int hits = 0;
        double reciprocal = 0;
        for (int i = 0; i < predictions.size(); i++) {
            if (labels.contains(predictions.get(i))) {
                if (i < 5) hits++;
                if (reciprocal == 0) reciprocal = 1.0 / (i + 1);
            }
        }
        return new Metrics(hits > 0 ? 1 : 0, labels.isEmpty() ? 0 : (double) hits / labels.size(), reciprocal);
    }
    public static Metrics average(List<Metrics> rows) {
        if (rows.isEmpty()) return new Metrics(0, 0, 0);
        return new Metrics(rows.stream().mapToDouble(Metrics::hitAt5).average().orElseThrow(),
                rows.stream().mapToDouble(Metrics::recallAt5).average().orElseThrow(),
                rows.stream().mapToDouble(Metrics::mrr).average().orElseThrow());
    }
}
