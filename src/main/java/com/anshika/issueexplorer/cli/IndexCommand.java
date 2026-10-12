package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.indexing.MethodIndex;
import com.anshika.issueexplorer.config.AppConfig;
import com.anshika.issueexplorer.parsing.JavaDirectoryParser;
import com.anshika.issueexplorer.parsing.ParsingLimitException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "index", description = "Build or reuse a cached Lucene method index for a local folder.",
        mixinStandardHelpOptions = true)
public class IndexCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<path>")
    private Path source;

    @Option(names = "--repo", paramLabel = "<owner/repo>", description = "Repository identity; requires --sha.")
    private String repo;

    @Option(names = "--sha", paramLabel = "<sha>", description = "Full commit SHA; requires --repo.")
    private String sha;

    @Option(names = "--cache-dir", paramLabel = "<path>", description = "Cache root (default: ~/.issue-explorer/cache).")
    private Path cache = Path.of(System.getProperty("user.home"), ".issue-explorer", "cache");

    @Option(names = "--threads") private Integer threads;
    @Option(names = "--config") private Path configFile;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            var config = AppConfig.load(configFile).override(null, null, null, threads, null);
            var parsed = new JavaDirectoryParser(config.parserThreads(), config.maxFiles(), config.maxExtractedBytes()).parse(source);
            var result = new MethodIndex().build(source, cache, repo, sha, parsed);
            var output = spec.commandLine().getOut();
            output.println((result.reused() ? "Already indexed; reused cache: " : "Built index: ") + result.directory());
            output.println("Methods indexed: " + result.methods());
            output.println("Java files parsed: " + result.parsedFiles() + "; failed: " + result.failedFiles());
            return 0;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (ParsingLimitException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 5;
        } catch (IOException exception) {
            spec.commandLine().getErr().println("Error: Could not build or read the index; check source and cache permissions.");
            return 1;
        }
    }
}

