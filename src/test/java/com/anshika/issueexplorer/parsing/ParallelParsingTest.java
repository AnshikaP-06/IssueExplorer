package com.anshika.issueexplorer.parsing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ParallelParsingTest {
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8})
    void parallelResultsExactlyEqualSequentialFixtureDeclarations(int threads) throws Exception {
        var sequential = new JavaDirectoryParser(1).parse(fixture());
        var parallel = new JavaDirectoryParser(threads).parse(fixture());
        assertEquals(sequential, parallel);
        assertEquals(15, parallel.parsed());
        assertEquals(0, parallel.failed());
        assertEquals(parallel, new JavaDirectoryParser(threads).parse(fixture()));
    }

    @Test
    void mixedFailuresAndNestedFilesAreIdenticalAcrossExecutionModes() throws Exception {
        Files.createDirectories(temporary.resolve("nested"));
        for (int i = 0; i < 30; i++) {
            Files.writeString(temporary.resolve("nested/Class" + i + ".java"), "class Class" + i + " { void method" + i + "() {} }");
        }
        Files.writeString(temporary.resolve("Broken.java"), "class Broken { void broken( }");
        var sequential = new JavaDirectoryParser(1).parse(temporary);
        assertEquals(sequential, new JavaDirectoryParser(4).parse(temporary));
        assertEquals(30, sequential.parsed());
        assertEquals(1, sequential.failed());
    }

    @Test
    void emptyDirectoryDoesNotRequireWorkers() throws Exception {
        assertEquals(new JavaDirectoryParser(1).parse(temporary), new JavaDirectoryParser(4).parse(temporary));
    }

    @Test
    void fileAndSizeLimitsAreEnforcedBeforeParsing() throws Exception {
        Files.writeString(temporary.resolve("A.java"), "class A {}");
        Files.writeString(temporary.resolve("B.java"), "class B {}");
        assertThrows(ParsingLimitException.class, () -> new JavaDirectoryParser(4, 1, 100).parse(temporary));
        assertThrows(ParsingLimitException.class, () -> new JavaDirectoryParser(4, 2, 1).parse(temporary));
        assertEquals(2, new JavaDirectoryParser(4, 2, 20).parse(temporary).parsed());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidThreadCountsAreRejected(int threads) {
        assertThrows(IllegalArgumentException.class, () -> new JavaDirectoryParser(threads));
    }

    @Test
    void interruptedParallelParsingPreservesInterruptStatus() throws Exception {
        Files.writeString(temporary.resolve("A.java"), "class A {}");
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> new JavaDirectoryParser(4).parse(temporary));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
