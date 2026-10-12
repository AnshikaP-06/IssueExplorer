package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.analysis.AnalyzePipeline;
import com.anshika.issueexplorer.config.AppConfig;
import com.anshika.issueexplorer.evaluation.Evaluation;
import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.parsing.ParsingLimitException;
import picocli.CommandLine.*;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "evaluate", mixinStandardHelpOptions = true,
        description = "Evaluate file ranking at pinned commits; report dev and heldout separately.")
public class EvaluateCommand implements Callable<Integer> {
    @FunctionalInterface
    public interface Predictor {
        List<String> rank(Evaluation.Entry entry, AnalyzePipeline.Options options) throws IOException, GitHubClient.GitHubException;
    }
    private final Predictor predictor;
    @Parameters(index = "0", paramLabel = "<dataset.json>") private Path dataset;
    @Option(names = "--config") private Path configFile;
    @Option(names = "--cache-dir") private Path cacheRoot = Path.of(System.getProperty("user.home"), ".issue-explorer", "cache");
    @Spec private Model.CommandSpec spec;
    public EvaluateCommand() { predictor = null; }
    public EvaluateCommand(Predictor predictor) { this.predictor = Objects.requireNonNull(predictor); }
    public Integer call() {
        try {
            Evaluation.Entry[] entries;
            try {
                entries = JsonMapper.builder().build().readValue(Files.readString(dataset), Evaluation.Entry[].class);
            } catch (tools.jackson.core.JacksonException e) {
                throw new IllegalArgumentException("Invalid dataset: use a JSON array with issueUrl, commitSha, relevantFiles, and split (dev or heldout).", e);
            }
            if (entries == null || entries.length == 0 || Arrays.stream(entries).anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Dataset must be a non-empty array of issue entries.");
            var config = AppConfig.load(configFile);
            var groups = new LinkedHashMap<String, List<Evaluation.Metrics>>();
            groups.put("dev", new ArrayList<>()); groups.put("heldout", new ArrayList<>());
            var seen = new HashSet<String>();
            for (var entry : entries) {
                String identity = entry.issueUrl().toLowerCase(Locale.ROOT) + "@" + entry.commitSha().toLowerCase(Locale.ROOT);
                if (!seen.add(identity)) throw new IllegalArgumentException("Duplicate issue/commit pair in dataset: " + entry.issueUrl());
            }
            var pipeline = predictor == null ? new AnalyzePipeline(new GitHubClient(System.getenv("GITHUB_TOKEN"))) : null;
            for (var entry : entries) {
                var options = new AnalyzePipeline.Options(cacheRoot, entry.commitSha(), Integer.MAX_VALUE,
                        config.maxDownloadBytes(), config.maxFiles(), config.maxExtractedBytes(), config.parserThreads());
                var ranked = predictor != null ? predictor.rank(entry, options) : pipeline.analyze(entry.issueUrl(), options,
                        message -> spec.commandLine().getErr().println(message)).results().stream().map(r -> r.path()).toList();
                var metrics = Evaluation.metrics(ranked, entry.relevantFiles());
                groups.get(entry.split()).add(metrics);
                spec.commandLine().getOut().printf(Locale.ROOT, "%s %s @ %s: Hit@5=%.6f Recall@5=%.6f MRR=%.6f%n",
                        entry.split(), entry.issueUrl(), entry.commitSha(), metrics.hitAt5(), metrics.recallAt5(), metrics.mrr());
                spec.commandLine().getOut().println("  Top 5:");
                var topFiles = ranked.stream().distinct().limit(5).toList();
                if (topFiles.isEmpty()) spec.commandLine().getOut().println("    No matching files.");
                for (int i = 0; i < topFiles.size(); i++) {
                    spec.commandLine().getOut().println("    " + (i + 1) + ". " + topFiles.get(i));
                }
                spec.commandLine().getOut().println();
            }
            for (var group : groups.entrySet()) {
                var metrics = Evaluation.average(group.getValue());
                if (group.getValue().isEmpty()) spec.commandLine().getOut().println(group.getKey() + ": issues=0; metrics=N/A");
                else spec.commandLine().getOut().printf(Locale.ROOT, "%s: issues=%d Hit@5=%.6f Recall@5=%.6f MRR=%.6f%n",
                        group.getKey(), group.getValue().size(), metrics.hitAt5(), metrics.recallAt5(), metrics.mrr());
            }
            return 0;
        } catch (GitHubClient.GitHubException e) {
            spec.commandLine().getErr().println("Error: " + e.getMessage()); return e.exitCode();
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println("Error: " + e.getMessage()); return 2;
        } catch (ParsingLimitException e) {
            spec.commandLine().getErr().println("Error: " + e.getMessage()); return 5;
        } catch (IOException e) {
            spec.commandLine().getErr().println("Error: Could not read dataset/configuration or process the repository cache."); return 1;
        } catch (RuntimeException e) {
            spec.commandLine().getErr().println("Error: Evaluation failed; no complete summary was produced."); return 1;
        }
    }
}

