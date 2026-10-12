package com.anshika.issueexplorer.ranking;

import com.anshika.issueexplorer.indexing.MethodIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssueRankerTest {
    @TempDir Path temporary;
    private Path index;

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getClassLoader().getResource(name).toURI());
    }

    @BeforeEach
    void buildIndex() throws Exception {
        index = new MethodIndex().build(resource("fixture-repo"), temporary, "example/fixture", "a".repeat(40)).directory();
    }

    @Test
    void allFixtureExpectedFilesAppearInTopFiveAndNoMatchIsEmpty() throws Exception {
        var issues = resource("fixture-issues");
        var labels = JsonMapper.builder().build().readTree(Files.readString(issues.resolve("expected-relevant-files.json")));
        var ranker = new IssueRanker(RankingWeights.defaults());
        for (var fixture : labels) {
            var results = ranker.rank(index, Files.readString(issues.resolve(fixture.get("issueFile").asString())), 5);
            var paths = results.stream().map(FileScoreAggregator.FileScore::path).toList();
            if (fixture.get("relevantFiles").isEmpty()) {
                assertTrue(results.isEmpty(), "No-match fixture returned: " + paths);
            } else {
                for (var expected : fixture.get("relevantFiles")) {
                    assertTrue(paths.contains(expected.asString()), fixture.get("id").asString() + ": missing " + expected + " from " + paths);
                }
            }
            assertTrue(results.size() <= 5);
            assertEquals(paths.size(), paths.stream().distinct().count());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BookingService", "BookingService.java", "at com.example.fixture.booking.BookingService.createRecurringBooking(BookingService.java:19)", "createRecurring", "create recurring"})
    void exactHintsAndSplitIdentifierQueriesPrioritizeBookingService(String text) throws Exception {
        var results = new IssueRanker(RankingWeights.defaults()).rank(index, text, 5);
        assertFalse(results.isEmpty());
        assertEquals("src/main/java/com/example/fixture/booking/BookingService.java", results.getFirst().path());
    }

    @Test
    void configuredHintBoostChangesScoresAndZeroBoostDisablesIt() throws Exception {
        String text = "BookingService.java";
        var strong = new RankingWeights(4, 3, 2, 1, 100, 100, 100, 0.1f);
        var zero = new RankingWeights(4, 3, 2, 1, 0, 0, 0, 0.1f);
        var boosted = new IssueRanker(strong).rank(index, text, 5);
        var plain = new IssueRanker(zero).rank(index, text, 5);
        assertFalse(boosted.isEmpty());
        assertTrue(plain.isEmpty() || boosted.getFirst().score() > plain.getFirst().score());
    }

    @Test
    void rankingIsDeterministicAndRequestedLimitIsApplied() throws Exception {
        var ranker = new IssueRanker(RankingWeights.defaults());
        var first = ranker.rank(index, "booking cancellation reservation", 2);
        assertEquals(first, ranker.rank(index, "booking cancellation reservation", 2));
        assertTrue(first.size() <= 2);
    }

    @Test
    void blankIssueReturnsNoMatches() throws Exception {
        assertTrue(new IssueRanker(RankingWeights.defaults()).rank(index, "", 5).isEmpty());
    }

    @Test
    void invalidIndexOrResultLimitHasClearInputError() {
        assertThrows(IllegalArgumentException.class, () -> new IssueRanker(RankingWeights.defaults()).rank(temporary.resolve("missing"), "booking", 5));
        assertThrows(IllegalArgumentException.class, () -> new IssueRanker(RankingWeights.defaults()).rank(index, "booking", 0));
    }

    @Test
    void aggregationUsesMaxPlusBonusForOnlyTheOtherTopTwoMethods() {
        var methods = List.of(method("A.java", 10, 1), method("A.java", 4, 2),
                method("A.java", 2, 3), method("A.java", 1, 4), method("B.java", 11, 1));
        var results = new FileScoreAggregator().aggregate(methods, 0.1f);
        assertEquals("B.java", results.getFirst().path());
        assertEquals(10.6f, results.get(1).score(), 0.0001f);
        assertEquals(3, results.get(1).methods().size());
        assertEquals(10f, new FileScoreAggregator().aggregate(methods, 0).get(1).score());
    }

    @Test
    void tiedFilesHaveStableAlphabeticalOrder() {
        var results = new FileScoreAggregator().aggregate(List.of(method("B.java", 1, 1), method("A.java", 1, 1)), 0.1f);
        assertEquals(List.of("A.java", "B.java"), results.stream().map(FileScoreAggregator.FileScore::path).toList());
    }

    private FileScoreAggregator.MethodScore method(String path, float score, int line) {
        return new FileScoreAggregator.MethodScore(path, "method" + line, "void method" + line + "()", line, line, score);
    }

    @ParameterizedTest
    @ValueSource(floats = {-1, Float.NaN, Float.POSITIVE_INFINITY})
    void invalidWeightsAreRejected(float value) {
        assertThrows(IllegalArgumentException.class, () -> new RankingWeights(value, 3, 2, 1, 8, 10, 12, 0.1f));
    }

    @Test
    void allDisabledFieldsAndOversizedBonusAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RankingWeights(0, 0, 0, 0, 8, 10, 12, 0.1f));
        assertThrows(IllegalArgumentException.class, () -> new RankingWeights(4, 3, 2, 1, 8, 10, 12, 2));
    }
    @Test
    void configurableFieldWeightsChangeWhichEvidenceWins() throws Exception {
        Path source = temporary.resolve("weight-fixture");
        Files.createDirectories(source);
        Files.writeString(source.resolve("IdentifierMatch.java"), "class IdentifierMatch { void invoiceTotal() {} }");
        Files.writeString(source.resolve("CommentMatch.java"), "class CommentMatch { /** invoice total invoice total */ void describe() {} }");
        Path weightedIndex = new MethodIndex().build(source, temporary.resolve("weight-cache"), "example/weights", "b".repeat(40)).directory();
        var identifiers = new RankingWeights(20, 0, 1, 0, 0, 0, 0, 0);
        var comments = new RankingWeights(1, 0, 20, 0, 0, 0, 0, 0);
        assertEquals("IdentifierMatch.java", new IssueRanker(identifiers).rank(weightedIndex, "invoice total", 5).getFirst().path());
        assertEquals("CommentMatch.java", new IssueRanker(comments).rank(weightedIndex, "invoice total", 5).getFirst().path());
    }

    @Test
    void outdatedIndexHasAnActionableRebuildError() throws Exception {
        Path old = new MethodIndex(1).build(resource("fixture-repo"), temporary.resolve("old-cache"), "example/fixture", "a".repeat(40)).directory();
        var error = assertThrows(IllegalArgumentException.class,
                () -> new IssueRanker(RankingWeights.defaults()).rank(old, "booking", 5));
        assertTrue(error.getMessage().contains("rebuild with the index command"));
    }
}

