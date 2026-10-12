package com.anshika.issueexplorer.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "issue-explorer",
        mixinStandardHelpOptions = true,
        subcommands = {HelloCommand.class, ParseUrlCommand.class, ShowIssueCommand.class, DownloadCommand.class, ParseDirCommand.class, IndexCommand.class, RankCommand.class, AnalyzeCommand.class, EvaluateCommand.class}
)
public class IssueExplorerCommand {
    public static void main(String[] args) {
        System.exit(new CommandLine(new IssueExplorerCommand()).execute(args));
    }
}








