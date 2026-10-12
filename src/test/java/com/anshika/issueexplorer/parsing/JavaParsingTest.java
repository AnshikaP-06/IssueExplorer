package com.anshika.issueexplorer.parsing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaParsingTest {
    @TempDir Path temporary;

    private Path fixture() throws Exception {
        return Path.of(getClass().getClassLoader().getResource("fixture-repo").toURI());
    }

    @ParameterizedTest
    @CsvSource({
            "accounts/Account,0,1,4",
            "accounts/AccountRepository,4,1,2",
            "accounts/AccountService,0,1,1",
            "accounts/PasswordPolicy,0,1,1",
            "booking/Booking,1,1,4",
            "booking/BookingRepository,3,1,2",
            "booking/BookingService,3,1,2",
            "booking/CancellationPolicy,1,1,1",
            "reporting/CsvReportWriter,1,1,2",
            "reporting/ReportRow,1,1,2",
            "reporting/ReportService,2,1,2",
            "reporting/RevenueReport,2,1,2",
            "accounts/AccountServiceTest,2,1,3",
            "booking/BookingServiceTest,3,1,2",
            "reporting/ReportServiceTest,5,1,3"
    })
    void fixtureCountsMatchExactly(String name, int imports, int types, int methods) throws Exception {
        String relative = (name.endsWith("Test") ? "src/test/java/" : "src/main/java/")
                + "com/example/fixture/" + name + ".java";
        var result = new JavaFileParser().parse(fixture().resolve(relative), relative);
        assertTrue(result.successful(), result.error());
        var declarations = result.declarations();
        assertEquals("com.example.fixture." + name.split("/")[0], declarations.packageName());
        assertEquals(imports, declarations.imports().size());
        assertEquals(types, declarations.types().size());
        assertEquals(methods, declarations.methods().size());
        assertTrue(declarations.methods().stream().allMatch(method -> !method.javadoc().isBlank()));
        assertTrue(declarations.methods().stream().allMatch(method -> method.startLine() > 0 && method.endLine() >= method.startLine()));
    }

    @Test
    void fixtureDirectoryTotalsAreExactAndOrderingIsDeterministic() throws Exception {
        var parser = new JavaDirectoryParser();
        var result = parser.parse(fixture());
        assertEquals(15, result.files().size());
        assertEquals(15, result.parsed());
        assertEquals(0, result.failed());
        assertEquals(28, result.files().stream().mapToInt(file -> file.declarations().imports().size()).sum());
        assertEquals(15, result.files().stream().mapToInt(file -> file.declarations().types().size()).sum());
        assertEquals(33, result.files().stream().mapToInt(file -> file.declarations().methods().size()).sum());
        assertEquals(result, parser.parse(fixture()));
        var paths = result.files().stream().map(JavaFileParser.FileResult::path).toList();
        assertEquals(paths.stream().sorted().toList(), paths);
    }

    @Test
    void extractsImportsNestedTypesSignaturesLineRangesAndJavadoc() throws Exception {
        Path file = temporary.resolve("Sample.java");
        Files.writeString(file, String.join("\n",
                "package demo;", "", "import java.util.List;", "import static java.util.Collections.*;", "",
                "/** Container. */", "class Sample {", "/** Adds a value.",
                " * @param value number", " * @return incremented", " */", "public int add(int value) {",
                "    return value + 1;", "}", "/** Overload. */",
                "public int add(int left, int right) { return left + right; }", "class Inner { void ping() {} }", "}"));
        var result = new JavaFileParser().parse(file, "Sample.java");
        assertTrue(result.successful(), result.error());
        var declarations = result.declarations();
        assertEquals("demo", declarations.packageName());
        assertEquals(List.of(new JavaDeclarations.ImportInfo("java.util.List", false, false),
                new JavaDeclarations.ImportInfo("java.util.Collections", true, true)), declarations.imports());
        assertEquals(List.of(new JavaDeclarations.TypeInfo("Sample", "class"),
                new JavaDeclarations.TypeInfo("Inner", "class")), declarations.types());
        assertEquals(3, declarations.methods().size());
        var method = declarations.methods().getFirst();
        assertEquals("add", method.name());
        assertEquals("public int add(int value)", method.signature());
        assertEquals(12, method.startLine());
        assertEquals(14, method.endLine());
        assertTrue(method.javadoc().contains("Adds a value."));
        assertTrue(method.javadoc().contains("@param value number"));
        assertTrue(method.javadoc().contains("@return incremented"));
        assertEquals("public int add(int left, int right)", declarations.methods().get(1).signature());
        assertEquals("", declarations.methods().get(2).javadoc());
        assertThrows(UnsupportedOperationException.class, () -> declarations.methods().clear());
    }

    @Test
    void supportsJava21TypesAndDoesNotCountImplicitRecordMethodsOrConstructors() throws Exception {
        Path file = temporary.resolve("Types.java");
        Files.writeString(file, "interface Task { void run(); }\n"
                + "enum State { READY; int code() { return ordinal(); } }\n"
                + "record Item(int count) { int value() { return count; } }\n"
                + "@interface Marker { String value(); }\n"
                + "class Plain { Plain() {} }");
        var result = new JavaFileParser().parse(file, "Types.java");
        assertTrue(result.successful(), result.error());
        assertEquals("", result.declarations().packageName());
        assertEquals(List.of("interface", "enum", "record", "annotation", "class"),
                result.declarations().types().stream().map(JavaDeclarations.TypeInfo::kind).toList());
        assertEquals(3, result.declarations().methods().size());
    }

    @Test
    void malformedFileIsCountedAndOtherFilesContinue() throws Exception {
        Files.writeString(temporary.resolve("Bad.java"), "class Bad { void broken( }");
        Files.writeString(temporary.resolve("Good.java"), "class Good { void hello() {} }");
        Files.writeString(temporary.resolve("ignored.txt"), "not java");
        var result = new JavaDirectoryParser().parse(temporary);
        assertEquals(2, result.files().size());
        assertEquals(1, result.parsed());
        assertEquals(1, result.failed());
        assertNull(result.files().getFirst().declarations());
        assertEquals("Java syntax could not be parsed.", result.files().getFirst().error());
        assertEquals("hello", result.files().get(1).declarations().methods().getFirst().name());
    }

    @Test
    void missingFileIsAFailureResult() {
        var result = new JavaFileParser().parse(temporary.resolve("missing.java"), "missing.java");
        assertFalse(result.successful());
        assertNull(result.declarations());
        assertEquals("Could not read Java file.", result.error());
    }

    @Test
    void excludedDirectoriesAreNotParsed() throws Exception {
        Files.createDirectories(temporary.resolve("target"));
        Files.writeString(temporary.resolve("target/Bad.java"), "invalid");
        Files.writeString(temporary.resolve("Good.java"), "class Good {}");
        var result = new JavaDirectoryParser().parse(temporary);
        assertEquals(1, result.parsed());
        assertEquals(0, result.failed());
    }

    @Test
    void emptyDirectoryIsSuccessful() throws Exception {
        var result = new JavaDirectoryParser().parse(temporary);
        assertEquals(0, result.parsed());
        assertEquals(0, result.failed());
    }

    @Test
    void invalidDirectoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JavaDirectoryParser().parse(temporary.resolve("missing")));
    }
}
