package com.anshika.issueexplorer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IndexCommandTest {
    @TempDir Path cache;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private CommandLine command() {
        return new CommandLine(new IssueExplorerCommand()).setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    @Test
    void fixtureBuildAndReuseAreReported() throws Exception {
        Path fixture = Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
        String[] args = {"index", fixture.toString(), "--cache-dir", cache.toString()};
        assertEquals(0, command().execute(args));
        assertTrue(output.toString().contains("Built index:"));
        assertTrue(output.toString().contains("Methods indexed: 33"));
        assertTrue(output.toString().contains("Java files parsed: 15; failed: 0"));
        output.getBuffer().setLength(0);
        assertEquals(0, command().execute(args));
        assertTrue(output.toString().contains("Already indexed; reused cache:"));
        assertEquals("", errors.toString());
    }

    @Test
    void nonexistentSourceIsAUsageError() {
        assertEquals(2, command().execute("index", cache.resolve("missing").toString()));
        assertEquals("", output.toString());
        assertTrue(errors.toString().contains("existing directory"));
    }

    @Test
    void missingSourceIsAUsageError() {
        assertEquals(2, command().execute("index"));
    }
}
