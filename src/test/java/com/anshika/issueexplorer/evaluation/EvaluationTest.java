package com.anshika.issueexplorer.evaluation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EvaluationTest {
    @Test void threeHandComputedIssues() {
        var a = Evaluation.metrics(List.of("a", "x", "b"), List.of("a", "b"));
        var b = Evaluation.metrics(List.of("x", "c", "y", "z", "q", "d"), List.of("c", "d"));
        var c = Evaluation.metrics(List.of("x"), List.of("e"));
        assertEquals(new Evaluation.Metrics(1, 1, 1), a);
        assertEquals(new Evaluation.Metrics(1, .5, .5), b);
        assertEquals(new Evaluation.Metrics(0, 0, 0), c);
        var average = Evaluation.average(List.of(a, b, c));
        assertEquals(2.0 / 3, average.hitAt5(), 1e-12);
        assertEquals(.5, average.recallAt5(), 1e-12);
        assertEquals(.5, average.mrr(), 1e-12);
    }
    @Test void rankFiveAndSixBoundaryAndUntruncatedMrr() {
        assertEquals(new Evaluation.Metrics(1, 1, .2), Evaluation.metrics(List.of("a","b","c","d","e"), List.of("e")));
        assertEquals(new Evaluation.Metrics(0, 0, 1.0/6), Evaluation.metrics(List.of("a","b","c","d","e","f"), List.of("f")));
    }
    @Test void emptyPredictionsAndLabels() {
        assertEquals(new Evaluation.Metrics(0,0,0), Evaluation.metrics(List.of(), List.of("a")));
        assertEquals(new Evaluation.Metrics(0,0,0), Evaluation.metrics(List.of("a"), List.of()));
        assertEquals(new Evaluation.Metrics(0,0,0), Evaluation.average(List.of()));
    }
    @Test void duplicatesDoNotInflateScoresOrRanks() {
        assertEquals(new Evaluation.Metrics(1,.5,.5), Evaluation.metrics(List.of("x","x","a","a"), List.of("a","a","b")));
    }
    @Test void validatesDatasetEntries() {
        String url="https://github.com/example/repo/issues/1", sha="a".repeat(40);
        assertEquals(List.of("A.java"), new Evaluation.Entry(url,sha,List.of("A.java","A.java"),"dev").relevantFiles());
        for (String split : List.of("test", "DEV", "")) assertThrows(IllegalArgumentException.class, () -> new Evaluation.Entry(url,sha,List.of(),split));
        assertThrows(IllegalArgumentException.class, () -> new Evaluation.Entry(url,"abc",List.of(),"dev"));
        assertThrows(IllegalArgumentException.class, () -> new Evaluation.Entry(url,sha,null,"dev"));
        for(String path:List.of("../A.java","/A.java","a\\A.java","a//A.java","C:A.java",""))
            assertThrows(IllegalArgumentException.class, () -> new Evaluation.Entry(url,sha,List.of(path),"dev"));
        assertThrows(IllegalArgumentException.class, () -> new Evaluation.Entry("https://example.com/issues/1",sha,List.of(),"dev"));
    }
}
