package com.anshika.issueexplorer.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(name = "hello", description = "Print a greeting.", mixinStandardHelpOptions = true)
public class HelloCommand implements Runnable {
    @Spec
    private CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().getOut().println("Hello from issue-explorer");
    }
}
