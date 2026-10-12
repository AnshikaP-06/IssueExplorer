package com.anshika.issueexplorer.cli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class EvaluateCommandTest {
    @TempDir Path temp;
    StringWriter out=new StringWriter(), err=new StringWriter();
    String row(int id,String split,String labels) {
        return "{\"issueUrl\":\"https://github.com/example/repo/issues/"+id+"\",\"commitSha\":\""+"a".repeat(40)+"\",\"relevantFiles\":"+labels+",\"split\":\""+split+"\"}";
    }
    int run(String json, EvaluateCommand.Predictor predictor) throws Exception {
        Path dataset=temp.resolve("dataset.json"); Files.writeString(dataset,json);
        Path config=temp.resolve("config.properties"); Files.writeString(config,"parser.threads=1\n");
        return new CommandLine(new EvaluateCommand(predictor)).setOut(new PrintWriter(out)).setErr(new PrintWriter(err))
                .execute(dataset.toString(),"--config",config.toString());
    }
    @Test void commandAgreesWithThreeIssueHandCalculation() throws Exception {
        assertEquals(0,run("["+row(1,"dev","[\"a\",\"b\"]")+","+row(2,"dev","[\"c\",\"d\"]")+","+row(3,"dev","[\"e\"]")+"]", (entry, options)-> {
            assertEquals("a".repeat(40),options.ref()); assertEquals(Integer.MAX_VALUE,options.top());
            return switch(entry.issueUrl().charAt(entry.issueUrl().length()-1)) {
                case '1' -> List.of("a","x","b"); case '2' -> List.of("x","c","y","z","q","d"); default -> List.of("x");
            };
        }));
        assertTrue(out.toString().contains("dev: issues=3 Hit@5=0.666667 Recall@5=0.500000 MRR=0.500000"));
        assertTrue(out.toString().contains("heldout: issues=0; metrics=N/A"));
        assertTrue(out.toString().replace("\r\n", "\n").contains("  Top 5:\n    1. a\n    2. x\n    3. b\n\n"));
        assertEquals(3,out.toString().lines().filter(s->s.contains(" @ ")).count());
    }
    @Test void splitsStaySeparate() throws Exception {
        assertEquals(0,run("["+row(1,"dev","[\"a\"]")+","+row(2,"heldout","[\"b\"]")+"]",(entry,options)->List.of("a")));
        assertTrue(out.toString().contains("dev: issues=1 Hit@5=1.000000 Recall@5=1.000000 MRR=1.000000"));
        assertTrue(out.toString().contains("heldout: issues=1 Hit@5=0.000000 Recall@5=0.000000 MRR=0.000000"));
    }
    @Test void rejectsInvalidDatasetsBeforePrediction() throws Exception {
        for(String json:List.of("[]","null","[null]","{}","bad","["+row(1,"invalid","[]")+"]","["+row(1,"dev","[]")+","+row(1,"heldout","[]")+"]")) {
            assertEquals(2,run(json,(entry,options)->{fail("Should validate first");return List.of();}));
        }
    }
    @Test void ioFailureDoesNotProduceCompleteSummary() throws Exception {
        assertEquals(1,run("["+row(1,"dev","[]")+"]",(entry,options)->{throw new IOException();}));
        assertFalse(out.toString().contains("dev: issues="));
    }
    @Test void registeredInRootHelp() {
        assertTrue(new CommandLine(new IssueExplorerCommand()).getSubcommands().containsKey("evaluate"));
    }
    @Test void evaluatesThroughMockGitHubAndCachedFixturePipeline() throws Exception {
        try(var server = new com.anshika.issueexplorer.github.MockGitHubServer()) {
            String sha="a".repeat(40);
            server.respondAt("/repos/example/repo/issues/1",200,"{\"title\":\"BookingService cancellation\",\"body\":\"CancellationPolicy deadline\"}",java.util.Map.of());
            server.respondAt("/repos/example/repo",200,"{\"private\":false}",java.util.Map.of());
            server.respondAt("/repos/example/repo/commits/"+sha,200,"{\"sha\":\""+sha+"\"}",java.util.Map.of());
            try(var zip=getClass().getClassLoader().getResourceAsStream("fixture-archive.zip")) {
                server.respondBytes("/repos/example/repo/zipball/"+sha,zip.readAllBytes(),false);
            }
            var pipeline=new com.anshika.issueexplorer.analysis.AnalyzePipeline(new com.anshika.issueexplorer.github.GitHubClient(
                    server.baseUri(),null,java.time.Duration.ofSeconds(3),java.time.Clock.systemUTC()));
            assertEquals(0,run("["+row(1,"dev","[\"src/main/java/com/example/fixture/booking/BookingService.java\"]")+"]",
                    (entry, options)->pipeline.analyze(entry.issueUrl(),new com.anshika.issueexplorer.analysis.AnalyzePipeline.Options(
                            temp.resolve("cache"),options.ref(),options.top(),options.maxDownloadBytes(),options.maxFiles(),options.maxExtractedBytes(),1), message->{}).results().stream().map(r->r.path()).toList()));
            assertTrue(out.toString().contains("Hit@5=1.000000 Recall@5=1.000000"));
        }
    }}


