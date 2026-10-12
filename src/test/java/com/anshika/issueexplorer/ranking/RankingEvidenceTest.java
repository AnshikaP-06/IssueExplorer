package com.anshika.issueexplorer.ranking;

import com.anshika.issueexplorer.indexing.MethodIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RankingEvidenceTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path temporary;

    @Test
    void permalinkIsPinnedToFullCommitAndInclusiveRange() {
        assertEquals("https://github.com/owner/repo/blob/" + SHA + "/src/main/java/BookingService.java#L42-L51",
                new GitHubPermalink().build("owner/repo", SHA, "src/main/java/BookingService.java", 42, 51));
        assertEquals("https://github.com/owner/repo/blob/" + SHA + "/A.java#L1-L1",
                new GitHubPermalink().build("owner/repo", SHA.toUpperCase(java.util.Locale.ROOT), "A.java", 1, 1));
    }

    @Test
    void permalinkEncodesPathSegmentsWithoutEncodingSlashes() {
        assertEquals("https://github.com/owner/repo/blob/" + SHA + "/src/Hello%20%23%3F%25.java#L1-L3",
                new GitHubPermalink().build("owner/repo", SHA, "src/Hello #?%.java", 1, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../Escape.java", "/Absolute.java", "a/../Escape.java", "a//B.java", "C:/A.java", "a\\B.java", ""})
    void unsafePermalinkPathsAreRejected(String path) {
        assertThrows(IllegalArgumentException.class, () -> new GitHubPermalink().build("owner/repo", SHA, path, 1, 2));
    }

    @Test
    void invalidCommitRepositoryAndLineRangesAreRejected() {
        var links = new GitHubPermalink();
        assertThrows(IllegalArgumentException.class, () -> links.build("owner/repo", "main", "A.java", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> links.build("owner/repo/extra", SHA, "A.java", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> links.build("owner/repo", SHA, "A.java", 0, 2));
        assertThrows(IllegalArgumentException.class, () -> links.build("owner/repo", SHA, "A.java", 3, 2));
    }

    @Test
    void snippetUsesOneBasedInclusiveLinesAndStopsAtMethodEnd() throws Exception {
        Files.writeString(temporary.resolve("A.java"), "outside\nmethod start\nbody\nmethod end\noutside again\n");
        var snippet = new SourceSnippet().read(temporary, "A.java", 2, 4, 8);
        assertEquals(2, snippet.startLine());
        assertEquals(4, snippet.endLine());
        assertEquals(List.of(new SourceSnippet.Line(2, "method start"), new SourceSnippet.Line(3, "body"),
                new SourceSnippet.Line(4, "method end")), snippet.lines());
    }

    @Test
    void longMethodSnippetIsShortenedWithAccurateLineNumbers() throws Exception {
        Files.writeString(temporary.resolve("A.java"), String.join("\n", java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(number -> "line " + number).toList()));
        var snippet = new SourceSnippet().read(temporary, "A.java", 3, 15, 8);
        assertEquals(3, snippet.startLine());
        assertEquals(10, snippet.endLine());
        assertEquals(8, snippet.lines().size());
        assertEquals("line 3", snippet.lines().getFirst().text());
        assertEquals("line 10", snippet.lines().getLast().text());
    }

    @Test
    void snippetAtLastLineWithoutFinalNewlineIsReadCorrectly() throws Exception {
        Files.writeString(temporary.resolve("A.java"), "first\nlast");
        assertEquals(List.of(new SourceSnippet.Line(2, "last")), new SourceSnippet().read(temporary, "A.java", 2, 2, 8).lines());
    }

    @Test
    void staleOrInvalidLineRangeIsRejected() throws Exception {
        Files.writeString(temporary.resolve("A.java"), "one line");
        assertThrows(IOException.class, () -> new SourceSnippet().read(temporary, "A.java", 1, 2, 8));
        assertThrows(IllegalArgumentException.class, () -> new SourceSnippet().read(temporary, "A.java", 0, 1, 8));
        assertThrows(IllegalArgumentException.class, () -> new SourceSnippet().read(temporary, "A.java", 2, 1, 8));
    }

    @Test
    void snippetsCannotEscapeSourceFolder() {
        assertThrows(IllegalArgumentException.class, () -> new SourceSnippet().read(temporary, "../A.java", 1, 2, 8));
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
    }

    @Test
    void snapshotEvidenceIncludesMatchedTermsSnippetsAndPinnedPermalink() throws Exception {
        var index = new MethodIndex().build(fixture(), temporary, "owner/repo", SHA).directory();
        var files = new IssueRanker(RankingWeights.defaults()).rank(index, "create recurring BookingService.java", 5);
        var report = new RankingEvidence().build(index, files);
        assertEquals("owner/repo", report.repository());
        assertEquals(SHA, report.commitSha());
        var result = report.results().getFirst();
        assertEquals("createRecurringBooking", result.method());
        assertTrue(result.matchedTerms().contains("create"));
        assertTrue(result.matchedTerms().contains("recurring"));
        assertTrue(result.permalink().contains("/blob/" + SHA + "/" + result.path()));
        assertTrue(result.permalink().endsWith("#L" + result.startLine() + "-L" + result.endLine()));
        assertEquals(result.startLine(), result.snippet().startLine());
        assertTrue(result.snippet().endLine() <= result.endLine());
        var original = Files.readAllLines(fixture().resolve(result.path()));
        for (var line : result.snippet().lines()) {
            assertEquals(original.get(line.number() - 1), line.text());
        }
    }

    @Test
    void localContentKeysDoNotBecomeFakeGithubPermalinks() throws Exception {
        var index = new MethodIndex().build(fixture(), temporary, null, null).directory();
        var files = new IssueRanker(RankingWeights.defaults()).rank(index, "create recurring", 5);
        var report = new RankingEvidence().build(index, files);
        assertNull(report.repository());
        assertNull(report.commitSha());
        assertTrue(report.results().stream().allMatch(result -> result.permalink() == null));
        assertFalse(report.results().getFirst().snippet().lines().isEmpty());
    }

    @Test
    void changedSourcesCannotProduceMisleadingSnippetEvidence() throws Exception {
        Path root = temporary.resolve("source");
        Files.createDirectories(root);
        Path source = root.resolve("Example.java");
        Files.writeString(source, "class Example { void createRecurringBooking() {} }");
        var index = new MethodIndex().build(root, temporary.resolve("cache"), "owner/repo", SHA).directory();
        var files = new IssueRanker(RankingWeights.defaults()).rank(index, "create recurring", 5);
        Files.writeString(source, "class Example { void unrelatedMethod() {} }");
        var error = assertThrows(IOException.class, () -> new RankingEvidence().build(index, files));
        assertTrue(error.getMessage().contains("Source changed since indexing"));
    }
}
