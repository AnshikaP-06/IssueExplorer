package com.anshika.issueexplorer.cli;

import com.anshika.issueexplorer.ranking.RankingEvidence;

import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** Shared human-readable evidence formatting for rank and analyze. */
public final class EvidencePrinter {
    private EvidencePrinter() { }

    public static void print(PrintWriter output, List<RankingEvidence.Result> results) {
        if (results.isEmpty()) {
            output.println("No matching files.");
            return;
        }
        for (int i = 0; i < results.size(); i++) {
            output.println();
            output.println("------------------------------------------------------------");
            var file = results.get(i);
            output.printf(Locale.ROOT, "%d. %s (score=%.4f)%n", i + 1, file.path(), file.score());
            output.println("   Method: " + file.method() + " (lines " + file.startLine() + "-" + file.endLine() + ")");
            output.println("   Matched terms: " + String.join(", ", file.matchedTerms()));
            output.println();
            output.println("   Snippet (lines " + file.snippet().startLine() + "-" + file.snippet().endLine() + "):");
            for (var line : file.snippet().lines()) {
                output.printf("     %d | %s%n", line.number(), line.text());
            }
            output.println();
            output.println("   Permalink: " + (file.permalink() == null
                    ? "unavailable for local folders (no GitHub commit recorded)" : file.permalink()));
        }
        output.println();
    }
}


