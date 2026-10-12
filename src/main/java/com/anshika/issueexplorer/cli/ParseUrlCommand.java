package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.github.GitHubIssueUrlParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;

@Command(name = "parse-url", description = "Print the owner, repo, and issue number.",
        mixinStandardHelpOptions = true)
public class ParseUrlCommand implements Callable<Integer> {
    @Parameters(index = "0", paramLabel = "<url>")
    private String url;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {
        try {
            var issue = new GitHubIssueUrlParser().parse(url);
            var output = spec.commandLine().getOut();
            output.println("Owner: " + issue.owner());
            output.println("Repo: " + issue.repo());
            output.println("Issue number: " + issue.issueNumber());
            return 0;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        }
    }
}
