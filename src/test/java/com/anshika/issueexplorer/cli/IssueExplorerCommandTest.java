package com.anshika.issueexplorer.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IssueExplorerCommandTest {
    @Test
    void helloPrintsGreetingAndExitsSuccessfully() {
        StringWriter output = new StringWriter();
        StringWriter errors = new StringWriter();
        CommandLine command = new CommandLine(new IssueExplorerCommand());
        command.setOut(new PrintWriter(output));
        command.setErr(new PrintWriter(errors));

        int exitCode = command.execute("hello");

        assertEquals(0, exitCode);
        assertEquals("Hello from issue-explorer" + System.lineSeparator(), output.toString());
        assertEquals("", errors.toString());
    }
}
