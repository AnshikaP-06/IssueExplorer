package com.anshika.issueexplorer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseUrlCommandTest {
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private CommandLine command() {
        return new CommandLine(new IssueExplorerCommand())
                .setOut(new PrintWriter(output))
                .setErr(new PrintWriter(errors));
    }

    @Test
    void printsAllThreeParts() {
        int exitCode = command().execute("parse-url", "https://github.com/anshika/issue-explorer/issues/123");

        assertEquals(0, exitCode);
        assertEquals(String.join(System.lineSeparator(),
                "Owner: anshika", "Repo: issue-explorer", "Issue number: 123", ""), output.toString());
        assertEquals("", errors.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/o/r/pull/1",
            "https://example.com/o/r/issues/1",
            "https://github.com/o/r/issues/",
            "https://github.com/o/r/issues/1/extra"
    })
    void invalidUrlPrintsOneErrorLineAndReturnsUsageExitCode(String url) {
        assertEquals(2, command().execute("parse-url", url));
        assertEquals("", output.toString());
        assertTrue(errors.toString().startsWith("Error: "));
        assertEquals(1, errors.toString().lines().count());
    }

    @Test
    void missingUrlIsAUsageError() {
        assertEquals(2, command().execute("parse-url"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("Missing required parameter: '<url>'"));
    }

    @Test
    void extraArgumentIsAUsageError() {
        assertEquals(2, command().execute("parse-url", "https://github.com/o/r/issues/1", "junk"));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("Unmatched argument"));
    }
}
