package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.indexing.MethodIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RankCommandTest {
    @TempDir Path temporary;
    private final StringWriter output = new StringWriter();
    private final StringWriter errors = new StringWriter();

    private CommandLine command() {
        return new CommandLine(new IssueExplorerCommand()).setOut(new PrintWriter(output)).setErr(new PrintWriter(errors));
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }

    @Test
    void ranksFixtureAndAcceptsConfiguredWeights() throws Exception {
        Path index = new MethodIndex().build(resource("fixture-repo"), temporary, "example/fixture", "a".repeat(40)).directory();
        assertEquals(0, command().execute("rank", "--issue-file", resource("fixture-issues/explicit-class.txt").toString(),
                "--index", index.toString(), "--identifier-weight", "5", "--method-bonus", "0.05"));
        assertTrue(output.toString().contains("BookingService.java"));
        assertTrue(output.toString().contains("score="));
        assertTrue(output.toString().lines().filter(line -> line.matches("^[1-5]\\. .*")).count() <= 5);
        assertEquals("", errors.toString());
    }

    @Test
    void noMatchIsReportedWithoutFabricatedResults() throws Exception {
        Path index = new MethodIndex().build(resource("fixture-repo"), temporary, "example/fixture", "a".repeat(40)).directory();
        assertEquals(0, command().execute("rank", "--issue-file", resource("fixture-issues/no-match.txt").toString(), "--index", index.toString()));
        assertEquals("No matching files." + System.lineSeparator(), output.toString());
    }

    @Test
    void missingFlagsAreUsageErrors() {
        assertEquals(2, command().execute("rank"));
    }

    @Test
    void invalidWeightIsRejected() throws Exception {
        assertEquals(2, command().execute("rank", "--issue-file", resource("fixture-issues/vague.txt").toString(),
                "--index", temporary.toString(), "--body-weight", "-1"));
        assertTrue(errors.toString().contains("non-negative"));
        assertEquals("", output.toString());
    }

    @Test
    void missingIssueFileIsRejected() {
        assertEquals(2, command().execute("rank", "--issue-file", temporary.resolve("missing.txt").toString(), "--index", temporary.toString()));
        assertTrue(errors.toString().contains("existing text file"));
    }
    @Test
    void jsonOutputIsParseableAndIncludesStructuredEvidence() throws Exception {
        String sha = "a".repeat(40);
        Path index = new MethodIndex().build(resource("fixture-repo"), temporary, "example/fixture", sha).directory();
        assertEquals(0, command().execute("rank", "--issue-file", resource("fixture-issues/explicit-class.txt").toString(),
                "--index", index.toString(), "--json"));
        var report = tools.jackson.databind.json.JsonMapper.builder().build().readTree(output.toString());
        assertEquals(1, report.get("schemaVersion").asInt());
        assertEquals(sha, report.get("commitSha").asString());
        assertTrue(report.get("results").isArray());
        var first = report.get("results").get(0);
        assertTrue(first.get("path").asString().endsWith("BookingService.java"));
        assertTrue(first.get("method").isString());
        assertTrue(first.get("matchedTerms").isArray());
        assertTrue(first.get("snippet").get("lines").isArray());
        assertTrue(first.get("permalink").asString().contains("/blob/" + sha + "/"));
        assertEquals("", errors.toString());
    }

    @Test
    void jsonNoMatchHasEmptyResultsWithoutHumanText() throws Exception {
        Path index = new MethodIndex().build(resource("fixture-repo"), temporary, null, null).directory();
        assertEquals(0, command().execute("rank", "--issue-file", resource("fixture-issues/no-match.txt").toString(),
                "--index", index.toString(), "--json"));
        var report = tools.jackson.databind.json.JsonMapper.builder().build().readTree(output.toString());
        assertTrue(report.get("results").isEmpty());
        assertTrue(report.get("commitSha").isNull());
        assertFalse(output.toString().contains("No matching files."));
    }

    @Test
    void humanOutputIncludesMethodLinesTermsSnippetAndPermalink() throws Exception {
        Path index = new MethodIndex().build(resource("fixture-repo"), temporary, "example/fixture", "a".repeat(40)).directory();
        assertEquals(0, command().execute("rank", "--issue-file", resource("fixture-issues/explicit-class.txt").toString(), "--index", index.toString()));
        assertTrue(output.toString().contains("Method:"));
        assertTrue(output.toString().contains("Matched terms:"));
        assertTrue(output.toString().contains("Snippet (lines"));
        assertTrue(output.toString().contains("https://github.com/example/fixture/blob/"));
    }
}

