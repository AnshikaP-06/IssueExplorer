package com.anshika.issueexplorer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ParseDirCommandTest {
    @TempDir Path directory;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private CommandLine command() {
        return new CommandLine(new IssueExplorerCommand())
                .setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    @Test
    void printsPerFileCountsAndFixtureCoverage() throws Exception {
        Path fixture = Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
        assertEquals(0, command().execute("parse-dir", fixture.toString()));
        assertTrue(output.toString().contains("src/main/java/com/example/fixture/booking/BookingService.java: "
                + "package=com.example.fixture.booking, imports=3, types=1, methods=2"));
        assertTrue(output.toString().contains("Java files: 15; parsed: 15; failed: 0"));
        assertEquals(16, output.toString().lines().count());
        assertTrue(errors.toString().startsWith("Timing parse:"));
        assertFalse(errors.toString().contains("Error:"));
    }

    @Test
    void failedFileIsReportedWithoutStoppingSuccessfulFiles() throws Exception {
        Files.writeString(directory.resolve("Bad.java"), "class Bad { void broken( }");
        Files.writeString(directory.resolve("Good.java"), "class Good { void ok() {} }");
        assertEquals(0, command().execute("parse-dir", directory.toString()));
        assertTrue(output.toString().contains("Bad.java: FAILED - Java syntax could not be parsed."));
        assertTrue(output.toString().contains("Good.java: package=(default), imports=0, types=1, methods=1"));
        assertTrue(output.toString().contains("Java files: 2; parsed: 1; failed: 1"));
        assertTrue(errors.toString().startsWith("Timing parse:"));
        assertFalse(errors.toString().contains("Error:"));
    }

    @Test
    void nonexistentPathReturnsUsageError() {
        assertEquals(2, command().execute("parse-dir", directory.resolve("missing").toString()));
        assertTrue(errors.toString().contains("existing directory"));
        assertEquals("", output.toString());
    }

    @Test
    void missingPathReturnsUsageError() {
        assertEquals(2, command().execute("parse-dir"));
    }
    @Test
    void configLimitsReturnFiveAndThreadFlagsOverrideConfiguration() throws Exception {
        Path config = directory.resolve("test.properties");
        Files.writeString(config, "max.java.files=1\nparser.threads=1\n");
        Files.writeString(directory.resolve("A.java"), "class A {}");
        Files.writeString(directory.resolve("B.java"), "class B {}");
        assertEquals(5, command().execute("parse-dir", directory.toString(), "--config", config.toString(), "--threads", "4"));
        assertTrue(errors.toString().contains("configured maximum of 1"));
    }
}

