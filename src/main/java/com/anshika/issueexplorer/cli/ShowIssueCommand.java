package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.github.GitHubClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Command(name = "show-issue", description = "Fetch and print a GitHub issue's title and body.",
        mixinStandardHelpOptions = true)
public class ShowIssueCommand implements Callable<Integer> {
    private final Supplier<GitHubClient> client;

    @Parameters(index = "0", paramLabel = "<url>")
    private String url;

    @Option(names = "--ref", paramLabel = "<ref>", description = "Branch, tag, or commit (default: repository's default branch).")
    private String ref;

    @Spec
    private CommandSpec spec;

    public ShowIssueCommand() {
        this(() -> new GitHubClient(System.getenv("GITHUB_TOKEN")));
    }

    public ShowIssueCommand(Supplier<GitHubClient> client) {
        this.client = client;
    }

    @Override
    public Integer call() {
        try {
            var github = client.get();
            var issue = github.fetchIssue(url);
            var sha = github.resolveRef(url, ref);
            var output = spec.commandLine().getOut();
            output.println("Title: " + issue.title());
            output.println("Body:");
            output.println(issue.body());
            output.println("Commit SHA: " + sha);
            return 0;
        } catch (IllegalArgumentException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return 2;
        } catch (GitHubClient.GitHubException exception) {
            spec.commandLine().getErr().println("Error: " + exception.getMessage());
            return exception.exitCode();
        }
    }
}

