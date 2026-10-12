package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.ranking.IssueRanker;
import com.anshika.issueexplorer.config.AppConfig;
import com.anshika.issueexplorer.ranking.RankingWeights;
import com.anshika.issueexplorer.ranking.RankingEvidence;
import tools.jackson.databind.json.JsonMapper;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "rank", description = "Rank the top five files for an issue against a method index.", mixinStandardHelpOptions = true)
public class RankCommand implements Callable<Integer> {
    @Option(names = "--issue-file", required = true) private Path issueFile;
    @Option(names = "--index", required = true) private Path index;
    @Option(names = "--identifier-weight", defaultValue = "4") private float identifier;
    @Option(names = "--path-weight", defaultValue = "3") private float path;
    @Option(names = "--comments-weight", defaultValue = "2") private float comments;
    @Option(names = "--body-weight", defaultValue = "1") private float body;
    @Option(names = "--class-hint-weight", defaultValue = "8") private float classHint;
    @Option(names = "--file-hint-weight", defaultValue = "10") private float fileHint;
    @Option(names = "--stack-hint-weight", defaultValue = "12") private float stackHint;
    @Option(names = "--method-bonus", defaultValue = "0.1") private float bonus;
    @Option(names = "--json", description = "Print machine-readable JSON evidence.") private boolean json;
    @Option(names = "--config") private Path configFile;
    @Option(names = "--top") private Integer top;
    @Spec private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            var config = AppConfig.load(configFile).override(null, null, null, null, top);
            var weights = new RankingWeights(identifier, path, comments, body, classHint, fileHint, stackHint, bonus);
            if (!Files.isRegularFile(issueFile)) {
                throw new IllegalArgumentException("Issue file must be an existing text file.");
            }
            var results = new IssueRanker(weights).rank(index, Files.readString(issueFile), config.top());
            var report = new RankingEvidence().build(index, results);
            var output = spec.commandLine().getOut();
            if (json) {
                output.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
                return 0;
            }
            EvidencePrinter.print(output, report.results());
            return 0;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (IOException exception) {
            spec.commandLine().getErr().println("Error: Could not read the issue, index, or unchanged source snapshot; rebuild the index if source files changed.");
            return 1;
        }
    }
}



