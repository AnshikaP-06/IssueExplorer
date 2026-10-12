package com.anshika.issueexplorer.fixtures;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FixtureResourcesTest {
    private Path resource(String name) throws Exception {
        var url = getClass().getClassLoader().getResource(name);
        assertNotNull(url, "Missing fixture resource: " + name);
        return Path.of(url.toURI());
    }

    private List<Path> javaFiles(Path root) throws Exception {
        try (var files = Files.walk(root.resolve("src"))) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    void fixtureHasFifteenDocumentedValidClassesAcrossThreeAreas() throws Exception {
        Path root = resource("fixture-repo");
        List<Path> files = javaFiles(root);
        assertEquals(15, files.size());
        assertEquals(12, files.stream().filter(path -> path.startsWith(root.resolve("src/main/java"))).count());
        assertEquals(3, files.stream().filter(path -> path.startsWith(root.resolve("src/test/java"))).count());
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        for (Path path : files) {
            var result = parser.parse(path);
            assertTrue(result.isSuccessful(), () -> path + ": " + result.getProblems());
            var unit = result.getResult().orElseThrow();
            assertEquals(1, unit.getTypes().size(), path.toString());
            assertTrue(unit.getType(0).isClassOrInterfaceDeclaration());
            assertTrue(unit.getType(0).getJavadoc().isPresent(), "Missing class Javadoc: " + path);
            String area = unit.getPackageDeclaration().orElseThrow().getNameAsString();
            assertTrue(Set.of("com.example.fixture.booking", "com.example.fixture.accounts",
                    "com.example.fixture.reporting").contains(area), path.toString());
        }
        for (String area : List.of("booking", "accounts", "reporting")) {
            assertEquals(5, files.stream().filter(path -> path.getParent().getFileName().toString().equals(area)).count());
        }
    }

    @Test
    void allFourIssueRecordsReferenceExistingIssueTextAndProductionFiles() throws Exception {
        Path issues = resource("fixture-issues");
        Path repository = resource("fixture-repo");
        var records = JsonMapper.builder().build().readTree(Files.readString(issues.resolve("expected-relevant-files.json")));
        assertTrue(records.isArray());
        assertEquals(4, records.size());
        var ids = new HashSet<String>();
        var categories = new HashSet<String>();
        for (var record : records) {
            assertTrue(ids.add(record.get("id").asString()), "Duplicate fixture ID");
            String category = record.get("category").asString();
            assertTrue(categories.add(category), "Duplicate category");
            String issueFile = record.get("issueFile").asString();
            Path issue = issues.resolve(issueFile).normalize();
            assertTrue(issue.startsWith(issues));
            assertTrue(Files.isRegularFile(issue));
            assertTrue(Files.readString(issue).startsWith("Title: "));
            var labels = record.get("relevantFiles");
            assertTrue(labels.isArray());
            assertEquals(category.equals("no-match"), labels.isEmpty());
            var uniquePaths = new HashSet<String>();
            for (var label : labels) {
                String relative = label.asString();
                assertTrue(uniquePaths.add(relative), "Duplicate file label");
                assertTrue(relative.startsWith("src/main/java/"));
                Path relevant = repository.resolve(relative).normalize();
                assertTrue(relevant.startsWith(repository));
                assertTrue(Files.isRegularFile(relevant), "Missing expected relevant file: " + relative);
                assertTrue(relative.endsWith(".java"));
            }
        }
        assertEquals(Set.of("explicit-class", "stack-trace", "vague", "no-match"), categories);
    }

    @Test
    void stackTracePointsInsideTheNamedMethodAtTheActualThrowLine() throws Exception {
        String issue = Files.readString(resource("fixture-issues/stack-trace.txt"));
        var frame = Pattern.compile("at (com\\.example\\.fixture\\.[a-z]+)\\.([A-Za-z]+)\\.([A-Za-z]+)\\(([A-Za-z]+\\.java):(\\d+)\\)")
                .matcher(issue);
        assertTrue(frame.find());
        assertEquals(frame.group(2) + ".java", frame.group(4));
        Path source = resource("fixture-repo").resolve("src/main/java/" + frame.group(1).replace('.', '/') + "/" + frame.group(4));
        int line = Integer.parseInt(frame.group(5));
        assertTrue(Files.readAllLines(source).get(line - 1).contains("throw new IllegalStateException"));
        var unit = new JavaParser().parse(source).getResult().orElseThrow();
        var type = unit.getClassByName(frame.group(2)).orElseThrow();
        var method = type.getMethodsByName(frame.group(3)).getFirst();
        var range = method.getRange().orElseThrow();
        assertTrue(line >= range.begin.line && line <= range.end.line);
    }

    @Test
    void vagueIssueDoesNotRevealClassNamesWhileExplicitIssueDoes() throws Exception {
        assertTrue(Files.readString(resource("fixture-issues/explicit-class.txt")).contains("BookingService"));
        String vague = Files.readString(resource("fixture-issues/vague.txt"));
        for (Path source : javaFiles(resource("fixture-repo"))) {
            String className = source.getFileName().toString().replace(".java", "");
            assertFalse(vague.contains(className), "Vague issue reveals " + className);
        }
    }
}
