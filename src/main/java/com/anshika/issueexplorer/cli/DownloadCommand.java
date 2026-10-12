package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.github.GitHubClient;
import com.anshika.issueexplorer.config.AppConfig;
import java.io.IOException;
import com.anshika.issueexplorer.snapshot.SnapshotDownloader;
import com.anshika.issueexplorer.snapshot.SnapshotExtractor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command(name = "download", description = "Download and cache the repository ZIP at a resolved commit.",
        mixinStandardHelpOptions = true)
public class DownloadCommand implements Callable<Integer> {
    private final Supplier<GitHubClient> client;

    @Parameters(index = "0", paramLabel = "<url>")
    private String url;

    @Option(names = "--ref", paramLabel = "<ref>", description = "Branch, tag, or commit (default: default branch).")
    private String ref;

    @Option(names = "--max-download-bytes", paramLabel = "<bytes>",
            description = "Maximum archive size in bytes (default: 50000000 = 50 MB).")
    private Long maxBytes;

    @Option(names = "--cache-dir", paramLabel = "<path>", description = "Cache root (default: ~/.issue-explorer/cache).")
    private Path cacheRoot = Path.of(System.getProperty("user.home"), ".issue-explorer", "cache");

    @Option(names = "--max-files", paramLabel = "<count>",
            description = "Maximum retained Java files (default: 5000).")
    private Integer maxFiles;

    @Option(names = "--max-extracted-bytes", paramLabel = "<bytes>",
            description = "Maximum total extracted Java bytes (default: 200000000 = 200 MB).")
    private Long maxExtractedBytes;

    @Option(names = "--config", description = "Configuration properties file.")
    private Path configFile;

    @Spec
    private CommandSpec spec;

    public DownloadCommand() {
        this(() -> new GitHubClient(System.getenv("GITHUB_TOKEN")));
    }

    public DownloadCommand(Supplier<GitHubClient> client) {
        this.client = client;
    }

    @Override
    public Integer call() {
        try {
            var config = AppConfig.load(configFile).override(maxBytes, maxFiles, maxExtractedBytes, null, null);
            maxBytes = config.maxDownloadBytes();
            maxFiles = config.maxFiles();
            maxExtractedBytes = config.maxExtractedBytes();
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("Maximum download size must be greater than zero.");
            }
            if (maxFiles <= 0 || maxExtractedBytes <= 0) {
                throw new IllegalArgumentException("Extraction file-count and size limits must be greater than zero.");
            }
            var github = client.get();
            github.fetchIssue(url);
            github.requirePublicRepository(url);
            String sha = github.resolveRef(url, ref);
            var result = new SnapshotDownloader(github).download(url, sha, cacheRoot, maxBytes);
            var extracted = new SnapshotExtractor().extract(result.archive(),
                    result.archive().resolveSibling("src"), maxFiles, maxExtractedBytes);
            var output = spec.commandLine().getOut();
            output.println("Commit SHA: " + sha);
            output.println((result.cached() ? "Already cached; skipped download: " : "Downloaded archive: ") + result.archive());
            output.println("Source directory: " + extracted.sourceDirectory());
            output.println("Java files kept: " + extracted.kept());
            output.println("Files skipped: " + extracted.skipped());
            extracted.skippedByReason().forEach((reason, count) -> output.println("  " + reason + ": " + count));
            output.println("Extracted Java bytes: " + extracted.extractedBytes());
            return 0;
        } catch (IOException exception) {
            spec.commandLine().getErr().println("Error: Could not read the configuration file.");
            return 1;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (GitHubClient.GitHubException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return exception.exitCode();
        }
    }
}



