package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.analysis.AnalyzePipeline;
import com.anshika.issueexplorer.config.AppConfig;
import com.anshika.issueexplorer.parsing.ParsingLimitException;
import com.anshika.issueexplorer.github.GitHubClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command(name = "analyze", description = "Analyze a public GitHub issue and recommend Java files with evidence.",
        mixinStandardHelpOptions = true)
public class AnalyzeCommand implements Callable<Integer> {
    private final Supplier<GitHubClient> client;
    @Parameters(index = "0", paramLabel = "<issue-url>") private String url;
    @Option(names = "--ref", paramLabel = "<ref>") private String ref;
    @Option(names = "--top", paramLabel = "<N>") private Integer top;
    @Option(names = "--json", description = "Print a JSON report; progress goes to stderr.") private boolean json;
    @Option(names = "--cache-dir", paramLabel = "<path>")
    private Path cacheRoot = Path.of(System.getProperty("user.home"), ".issue-explorer", "cache");
    @Option(names = "--max-download-bytes") private Long maxDownloadBytes;
    @Option(names = "--max-files") private Integer maxFiles;
    @Option(names = "--max-extracted-bytes") private Long maxExtractedBytes;
    @Option(names = "--threads", description = "Parser workers (default from config).") private Integer threads;
    @Option(names = "--config", description = "Override ~/.issue-explorer/config.properties.") private Path configFile;
    @Spec private CommandSpec spec;

    public AnalyzeCommand() {
        this(() -> new GitHubClient(System.getenv("GITHUB_TOKEN")));
    }

    public AnalyzeCommand(Supplier<GitHubClient> client) {
        this.client = client;
    }

    @Override
    public Integer call() {
        try {
            var config = AppConfig.load(configFile).override(maxDownloadBytes, maxFiles, maxExtractedBytes, threads, top);
            var options = new AnalyzePipeline.Options(cacheRoot, ref, config.top(), config.maxDownloadBytes(), config.maxFiles(), config.maxExtractedBytes(), config.parserThreads());
            var report = new AnalyzePipeline(client.get()).analyze(url, options, message -> {
                spec.commandLine().getErr().println(message);
                spec.commandLine().getErr().flush();
            });
            var output = spec.commandLine().getOut();
            if (json) {
                output.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            } else {
                output.println("Issue: " + report.title());
                output.println("Commit SHA: " + report.commitSha());
                EvidencePrinter.print(output, report.results());
                var coverage = report.coverage();
                output.println("Coverage: files indexed=" + coverage.filesIndexed() + ", skipped=" + coverage.filesSkipped()
                        + ", failed=" + coverage.filesFailed() + "; methods indexed=" + coverage.methodsIndexed());
                coverage.skippedByReason().forEach((reason, count) -> output.println("  " + reason + ": " + count));
            }
            return 0;
        } catch (GitHubClient.GitHubException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return exception.exitCode();
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (ParsingLimitException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 5;
        } catch (IOException exception) {
            spec.commandLine().getErr().println("Error: Could not read the configuration or read/write the cache, index, or source snapshot.");
            return 1;
        } catch (RuntimeException exception) {
            spec.commandLine().getErr().println("Error: Analysis failed unexpectedly; check the input and cache, then retry.");
            return 1;
        }
    }
}

