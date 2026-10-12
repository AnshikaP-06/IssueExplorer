package com.anshika.issueexplorer.indexing;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MethodIndexTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
    }

    @Test
    void fixtureHasOneDocumentPerMethodAndStoredEvidence() throws Exception {
        var result = new MethodIndex().build(fixture(), temporary, "example/fixture", SHA);
        assertEquals(33, result.methods());
        assertEquals(15, result.parsedFiles());
        assertEquals(0, result.failedFiles());
        assertEquals(temporary.resolve("example/fixture/" + SHA + "/index-v3"), result.directory());
        try (var directory = FSDirectory.open(result.directory()); var reader = DirectoryReader.open(directory)) {
            assertEquals(33, reader.numDocs());
            var searcher = new IndexSearcher(reader);
            assertEquals(25, searcher.count(new TermQuery(new Term("kind", "source"))));
            assertEquals(8, searcher.count(new TermQuery(new Term("kind", "test"))));
            for (int i = 0; i < reader.maxDoc(); i++) {
                var document = reader.storedFields().document(i);
                assertNotNull(document.get("identifier"));
                assertNotNull(document.get("path"));
                assertNotNull(document.get("comments"));
                assertNotNull(document.get("body"));
                assertNotNull(document.get("signature"));
                assertTrue(document.getField("startLine").numericValue().intValue() > 0);
                assertTrue(document.getField("endLine").numericValue().intValue()
                        >= document.getField("startLine").numericValue().intValue());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"createRecurring", "create recurring", "createRecurringBooking"})
    void bothSearchFormsFindTheCorrectMethod(String text) throws Exception {
        var result = new MethodIndex().build(fixture(), temporary, "example/fixture", SHA);
        try (var directory = FSDirectory.open(result.directory()); var reader = DirectoryReader.open(directory);
             var analyzer = new IdentifierAnalyzer()) {
            var searcher = new IndexSearcher(reader);
            var query = new QueryParser("identifier", analyzer).parse(text);
            var hits = searcher.search(query, 5);
            assertTrue(hits.totalHits.value() > 0);
            var document = reader.storedFields().document(hits.scoreDocs[0].doc);
            assertEquals("createRecurringBooking", document.get("method"));
            assertEquals("src/main/java/com/example/fixture/booking/BookingService.java", document.get("path"));
            assertTrue(document.get("comments").contains("weekly occurrences"));
            assertTrue(document.get("body").contains("repository.save(booking)"));
        }
    }

    @Test
    void existingCommittedIndexIsReusedWithoutChangingItsFiles() throws Exception {
        var index = new MethodIndex();
        var first = index.build(fixture(), temporary, "example/fixture", SHA);
        long firstCommit;
        try (var directory = FSDirectory.open(first.directory()); var reader = DirectoryReader.open(directory)) {
            firstCommit = reader.getIndexCommit().getGeneration();
        }
        var second = index.build(fixture(), temporary, "example/fixture", SHA);
        assertTrue(second.reused());
        assertEquals(first.directory(), second.directory());
        assertEquals(first.methods(), second.methods());
        try (var directory = FSDirectory.open(second.directory()); var reader = DirectoryReader.open(directory)) {
            assertEquals(firstCommit, reader.getIndexCommit().getGeneration());
        }
    }

    @Test
    void versionAndShaCreateDistinctIndexes() throws Exception {
        var first = new MethodIndex().build(fixture(), temporary, "example/fixture", SHA);
        var second = new MethodIndex(4).build(fixture(), temporary, "example/fixture", SHA);
        var third = new MethodIndex().build(fixture(), temporary, "example/fixture", "a".repeat(40));
        assertNotEquals(first.directory(), second.directory());
        assertNotEquals(first.directory(), third.directory());
        assertTrue(second.directory().endsWith("index-v4"));
        assertFalse(second.reused());
        assertFalse(third.reused());
    }

    @Test
    void localContentChangesProduceANewCacheKey() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Path cache = temporary.resolve("cache");
        Files.writeString(source.resolve("A.java"), "class A { void first() {} }");
        var index = new MethodIndex();
        var first = index.build(source, cache, null, null);
        assertTrue(index.build(source, cache, null, null).reused());
        Files.writeString(source.resolve("A.java"), "class A { void second() {} }");
        var second = index.build(source, cache, null, null);
        assertNotEquals(first.directory(), second.directory());
        assertFalse(second.reused());
    }

    @Test
    void malformedSourceIsCountedAndDoesNotStopIndexing() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.writeString(source.resolve("Bad.java"), "class Bad { void bad( }");
        Files.writeString(source.resolve("Good.java"), "class Good { /** Method docs. */ void ok() { /* internal note */ int x = 1; } }");
        var result = new MethodIndex().build(source, temporary.resolve("cache"), "example/source", SHA);
        assertEquals(1, result.methods());
        assertEquals(1, result.parsedFiles());
        assertEquals(1, result.failedFiles());
        try (var directory = FSDirectory.open(result.directory()); var reader = DirectoryReader.open(directory)) {
            var document = reader.storedFields().document(0);
            assertTrue(document.get("comments").contains("Method docs."));
            assertTrue(document.get("comments").contains("internal note"));
            assertTrue(document.get("body").contains("int x = 1"));
        }
    }

    @Test
    void incompleteIndexDirectoryIsRebuilt() throws Exception {
        Path target = temporary.resolve("example/fixture/" + SHA + "/index-v3");
        Files.createDirectories(target);
        Files.writeString(target.resolve("incomplete.txt"), "interrupted build");
        var result = new MethodIndex().build(fixture(), temporary, "example/fixture", SHA);
        assertFalse(result.reused());
        assertEquals(33, result.methods());
        assertFalse(Files.exists(target.resolve("incomplete.txt")));
    }

    @Test
    void cachedSnapshotFolderInfersRepositoryAndSha() throws Exception {
        Path source = temporary.resolve("cache/owner/repo/" + SHA + "/src");
        Files.createDirectories(source);
        Files.writeString(source.resolve("A.java"), "class A { void ok() {} }");
        var result = new MethodIndex().build(source, temporary.resolve("cache"), null, null);
        assertEquals(source.resolveSibling("index-v3"), result.directory());
        assertEquals(1, result.methods());
    }

    @Test
    void emptyFolderProducesReusableEmptyIndex() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("empty"));
        var index = new MethodIndex();
        assertEquals(0, index.build(source, temporary.resolve("cache"), null, null).methods());
        assertTrue(index.build(source, temporary.resolve("cache"), null, null).reused());
    }

    @Test
    void invalidIdentityIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new MethodIndex().build(fixture(), temporary, "example/fixture", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MethodIndex().build(fixture(), temporary, "example/fixture", "bad"));
        assertThrows(IllegalArgumentException.class, () -> new MethodIndex(0));
    }
    @Test
    void indexingATestSourceFolderDirectlyRetainsTestKind() throws Exception {
        Path source = temporary.resolve("project/src/test/java");
        Files.createDirectories(source);
        Files.writeString(source.resolve("ExampleTest.java"), "class ExampleTest { void check() {} }");
        var result = new MethodIndex().build(source, temporary.resolve("cache"), null, null);
        try (var directory = FSDirectory.open(result.directory()); var reader = DirectoryReader.open(directory)) {
            assertEquals("test", reader.storedFields().document(0).get("kind"));
        }
    }
}



