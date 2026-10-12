package com.anshika.issueexplorer.ranking;

import java.util.Comparator;
import java.util.List;

public final class FileScoreAggregator {
    public record MethodScore(String path, String method, String signature, int startLine, int endLine,
                              float score, List<String> matchedTerms) {
        public MethodScore {
            matchedTerms = List.copyOf(matchedTerms);
        }

        public MethodScore(String path, String method, String signature, int startLine, int endLine, float score) {
            this(path, method, signature, startLine, endLine, score, List.of());
        }
    }
    public record FileScore(String path, float score, List<MethodScore> methods) {
        public FileScore {
            methods = List.copyOf(methods);
        }
    }

    public List<FileScore> aggregate(List<MethodScore> methods, float bonus) {
        if (!Float.isFinite(bonus) || bonus < 0 || bonus > 1) {
            throw new IllegalArgumentException("Method bonus must be between 0 and 1.");
        }
        var groups = new java.util.TreeMap<String, java.util.ArrayList<MethodScore>>();
        for (var method : methods) {
            groups.computeIfAbsent(method.path(), ignored -> new java.util.ArrayList<>()).add(method);
        }
        var result = new java.util.ArrayList<FileScore>();
        var order = Comparator.comparingDouble(MethodScore::score).reversed()
                .thenComparing(MethodScore::startLine).thenComparing(MethodScore::signature);
        for (var group : groups.entrySet()) {
            var sorted = group.getValue().stream().sorted(order).limit(3).toList();
            float additional = 0;
            for (int i = 1; i < sorted.size(); i++) {
                additional += sorted.get(i).score();
            }
            result.add(new FileScore(group.getKey(), sorted.getFirst().score() + bonus * additional, sorted));
        }
        return result.stream().sorted(Comparator.comparingDouble(FileScore::score).reversed()
                .thenComparing(FileScore::path)).toList();
    }
}

