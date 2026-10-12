package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.ranking.RankingEvidence;
import com.anshika.issueexplorer.ranking.SourceSnippet;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvidencePrinterTest {
    @Test void separatesFileResultsAndEvidenceSections() {
        var snippet = new SourceSnippet.Snippet(10, 10, List.of(new SourceSnippet.Line(10, "void run() {}")));
        var first = new RankingEvidence.Result("A.java", 2, "run", "void run()", 10, 10, List.of("run"), snippet, "https://example.com/A");
        var second = new RankingEvidence.Result("B.java", 1, "run", "void run()", 10, 10, List.of("run"), snippet, null);
        var text = new StringWriter();
        EvidencePrinter.print(new PrintWriter(text), List.of(first, second));
        String printed = text.toString().replace("\r\n", "\n");
        assertTrue(printed.contains("https://example.com/A\n\n------------------------------------------------------------\n2. B.java"));
        assertTrue(printed.contains("Matched terms: run\n\n   Snippet"));
        assertTrue(printed.contains("10 | void run() {}\n\n   Permalink:"));
        assertTrue(printed.endsWith("recorded)\n\n"));
    }
    @Test void emptyResultsRemainClear() {
        var text = new StringWriter();
        EvidencePrinter.print(new PrintWriter(text), List.of());
        assertEquals("No matching files.\n", text.toString().replace("\r\n", "\n"));
    }
}

