package com.anshika.issueexplorer.analysis;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.github.GitHubIssueUrlParser;
import com.anshika.issueexplorer.indexing.MethodIndex;
import com.anshika.issueexplorer.parsing.JavaDirectoryParser;
import com.anshika.issueexplorer.ranking.IssueRanker;
import com.anshika.issueexplorer.ranking.RankingEvidence;
import com.anshika.issueexplorer.ranking.RankingWeights;
import com.anshika.issueexplorer.snapshot.SnapshotDownloader;
import com.anshika.issueexplorer.snapshot.SnapshotExtractor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class AnalyzePipeline {
    public record Options(Path cacheRoot, String ref, int top, long maxDownloadBytes, int maxFiles,
                          long maxExtractedBytes, int parserThreads) {
        public Options(Path cacheRoot, String ref, int top, long maxDownloadBytes, int maxFiles, long maxExtractedBytes) {
            this(cacheRoot, ref, top, maxDownloadBytes, maxFiles, maxExtractedBytes,
                    com.anshika.issueexplorer.config.AppConfig.defaults().parserThreads());
        }
        public Options {
            if (cacheRoot == null || top <= 0 || maxDownloadBytes <= 0 || maxFiles <= 0 || maxExtractedBytes <= 0 || parserThreads <= 0) {
                throw new IllegalArgumentException("Top result count and download/extraction limits must be positive; cache path is required.");
            }
        }
    }

    public record Coverage(long filesIndexed, long filesSkipped, long filesFailed, int methodsIndexed,
                           Map<String, Integer> skippedByReason) {
        public Coverage {
            skippedByReason = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(skippedByReason));
        }
    }

    public record Report(int schemaVersion, String issueUrl, String title, String repository, String commitSha,
                         boolean archiveCached, boolean indexCached, Coverage coverage,
                         List<RankingEvidence.Result> results, Map<String, Double> timingsMs) {
        public Report {
            results = List.copyOf(results);
            timingsMs = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(timingsMs));
        }
    }

    private final GitHubClient client;

    public AnalyzePipeline(GitHubClient client) {
        this.client = client;
    }

    public Report analyze(String url, Options options, Consumer<String> progress)
            throws IOException, GitHubClient.GitHubException {
        var timings = new StageTimings(progress);
        progress.accept("[1/9] Validate issue URL");
        var reference = timings.measure("validate-url", () -> new GitHubIssueUrlParser().parse(url));
        progress.accept("[2/9] Fetch issue");
        var issue = timings.measure("fetch-issue", () -> client.fetchIssue(url));
        progress.accept("[3/9] Verify public repository and resolve commit SHA");
        String sha = timings.measure("resolve-commit", () -> {
            client.requirePublicRepository(url);
            return client.resolveRef(url, options.ref());
        });
        progress.accept("Resolved commit: " + sha);
        progress.accept("[4/9] Download repository snapshot");
        var download = timings.measure("download", () -> new SnapshotDownloader(client).download(url, sha, options.cacheRoot(), options.maxDownloadBytes()));
        progress.accept(download.cached() ? "Archive cache hit; skipped download" : "Archive downloaded");
        progress.accept("[5/9] Extract and filter Java sources");
        var extracted = timings.measure("extract", () -> new SnapshotExtractor().extract(download.archive(), download.archive().resolveSibling("src"), options.maxFiles(), options.maxExtractedBytes()));
        progress.accept("[6/9] Parse Java declarations");
        var parsed = timings.measure("parse", () -> new JavaDirectoryParser(options.parserThreads(), options.maxFiles(), options.maxExtractedBytes()).parse(extracted.sourceDirectory()));
        progress.accept("[7/9] Build or reuse method index");
        String repository = reference.owner() + "/" + reference.repo();
        var index = timings.measure("index", () -> new MethodIndex().build(extracted.sourceDirectory(), options.cacheRoot(), repository, sha, parsed));
        progress.accept(index.reused() ? "Index cache hit; reused index" : "Method index built");
        progress.accept("[8/9] Rank methods and files");
        var ranked = timings.measure("rank", () -> new IssueRanker(RankingWeights.defaults()).rank(index.directory(), issue.title() + "\n" + issue.body(), options.top()));
        progress.accept("[9/9] Prepare source evidence");
        var evidence = timings.measure("evidence", () -> new RankingEvidence().build(index.directory(), ranked));
        long noMethods = parsed.files().stream().filter(file -> file.successful() && file.declarations().methods().isEmpty()).count();
        var reasons = new LinkedHashMap<>(extracted.skippedByReason());
        if (noMethods > 0) {
            reasons.put("Java files without methods", Math.toIntExact(noMethods));
        }
        var coverage = new Coverage(parsed.parsed() - noMethods, extracted.skipped() + noMethods,
                parsed.failed(), index.methods(), reasons);
        progress.accept("Coverage: files indexed=" + coverage.filesIndexed() + ", skipped=" + coverage.filesSkipped()
                + ", failed=" + coverage.filesFailed() + "; methods indexed=" + coverage.methodsIndexed());
        return new Report(1, url, issue.title(), evidence.repository(), evidence.commitSha(), download.cached(),
                index.reused(), coverage, evidence.results(), timings.finish());
    }
}

