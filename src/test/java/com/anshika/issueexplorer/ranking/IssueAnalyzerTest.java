package com.anshika.issueexplorer.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssueAnalyzerTest {
    private final IssueAnalyzer analyzer = new IssueAnalyzer();

    private String resource(String path) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(input, "Missing resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void explicitFixtureIdentifiesTheNamedClassAndFile() throws Exception {
        var hints = analyzer.analyze("", resource("fixture-issues/explicit-class.txt"));
        assertEquals(List.of("BookingService"), hints.classNames());
        assertEquals(List.of("BookingService.java"), hints.javaFiles());
        assertEquals(List.of(), hints.stackFrames());
    }

    @Test
    void stackFixtureExtractsTheExactFrame() throws Exception {
        var hints = analyzer.analyze("", resource("fixture-issues/stack-trace.txt"));
        assertEquals(List.of("IllegalStateException", "AccountService"), hints.classNames());
        assertEquals(List.of("AccountService.java"), hints.javaFiles());
        assertEquals(List.of(new IssueHints.StackFrame("com.example.fixture.accounts.AccountService",
                "authenticate", "AccountService.java", 17)), hints.stackFrames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"vague", "no-match"})
    void fixturesWithoutExplicitIdentifiersHaveNoHints(String issue) throws Exception {
        assertEquals(new IssueHints(List.of(), List.of(), List.of()),
                analyzer.analyze("", resource("fixture-issues/" + issue + ".txt")));
    }

    @Test
    void pastedRealJacksonIssueExtractsTwoObjectMapperFrames() throws Exception {
        var hints = analyzer.analyze("", resource("real-issue-excerpts/jackson-4499.txt"));
        assertEquals(List.of("ObjectMapper"), hints.classNames());
        assertEquals(List.of("ObjectMapper.java"), hints.javaFiles());
        assertEquals(List.of(
                new IssueHints.StackFrame("com.fasterxml.jackson.databind.ObjectMapper", "_writeValueAndClose", "ObjectMapper.java", 4799),
                new IssueHints.StackFrame("com.fasterxml.jackson.databind.ObjectMapper", "writeValueAsString", "ObjectMapper.java", 4040)
        ), hints.stackFrames());
    }

    @Test
    void pastedRealJacksonIssueRetainsTheJavaImportAndLinkedFilename() throws Exception {
        var hints = analyzer.analyze("", resource("real-issue-excerpts/jackson-989.txt"));
        assertEquals(List.of("UntypedObjectDeserializer", "ObjectMapper"), hints.classNames());
        assertEquals(List.of("UntypedObjectDeserializer.java"), hints.javaFiles());
        assertTrue(hints.stackFrames().isEmpty());
    }

    @Test
    void analyzesTitleAndBodyAndDeduplicatesInFirstSeenOrder() {
        var hints = analyzer.analyze("HTTPServer fails with BookingService", "BookingService.java and HTTPServer.java; BookingService again");
        assertEquals(List.of("HTTPServer", "BookingService"), hints.classNames());
        assertEquals(List.of("BookingService.java", "HTTPServer.java"), hints.javaFiles());
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "text", "json", "javascript", "python", ""})
    void fencedUnrelatedWordsDoNotBecomeClassHints(String language) {
        var hints = analyzer.analyze("BookingService", "```" + language + "\nHappyBirthday FriendlyWords HTTPServer\n```\nAccountService");
        assertEquals(List.of("BookingService", "AccountService"), hints.classNames());
    }

    @Test
    void unrelatedJavaScriptTypesAreIgnoredButOutsideProseIsRetained() {
        var hints = analyzer.analyze("BookingService", "```typescript\nclass BrowserWidget {}\nconst x = new ReactComponent();\n```\nAccountService");
        assertEquals(List.of("BookingService", "AccountService"), hints.classNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", ""})
    void javaDeclarationsAndMethodCallsInCodeBlocksAreRetained(String language) {
        var hints = analyzer.analyze("", "```" + language + "\nclass BookingService {}\nAccountService.authenticate(user);\n```");
        assertEquals(List.of("BookingService", "AccountService"), hints.classNames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "text", "log", "bash", "java"})
    void validJavaStackFramesAreRetainedRegardlessOfFenceLabel(String language) {
        var hints = analyzer.analyze("", "```" + language + "\nat com.x.BookingService.create(BookingService.java:42)\n```");
        assertEquals(1, hints.stackFrames().size());
        assertEquals(new IssueHints.StackFrame("com.x.BookingService", "create", "BookingService.java", 42), hints.stackFrames().getFirst());
    }

    @Test
    void tildeAndLongBacktickFencesWorkAndUnclosedUnrelatedBlockIsIgnored() {
        var hints = analyzer.analyze("", "~~~text\nHappyBirthday\n~~~\n````java\nclass BookingService {}\n````\n```text\nUnrelatedWords");
        assertEquals(List.of("BookingService"), hints.classNames());
    }

    @Test
    void indentedUnrelatedCodeIsIgnoredAndIndentedJavaTraceIsKept() {
        var hints = analyzer.analyze("", "    FriendlyWords\n    HappyBirthday\n\n    at com.x.BookingService.create(BookingService.java:42)");
        assertEquals(List.of("BookingService"), hints.classNames());
        assertEquals(1, hints.stackFrames().size());
    }

    @Test
    void javaFilenamesAreExtractedFromPathsAndLinksWithoutWrongSuffixes() {
        var hints = analyzer.analyze("", "src/main/BookingService.java, [Account.java](src/Account.java#L12), "
                + "package-info.java and module-info.java; Bad.java.bak, Other.javac and bookingservice.txt");
        assertEquals(List.of("BookingService.java", "Account.java", "package-info.java", "module-info.java"), hints.javaFiles());
        assertTrue(hints.classNames().contains("Account"));
    }

    @ParameterizedTest
    @CsvSource({
            "at java.base/java.lang.Thread.run(Thread.java:840), java.lang.Thread, run, Thread.java, 840",
            "at app//com.x.BookingService.create(BookingService.java:42), com.x.BookingService, create, BookingService.java, 42",
            "at com.x.BookingService$Inner.create(BookingService.java:7), com.x.BookingService$Inner, create, BookingService.java, 7",
            "at com.x.BookingService.<init>(BookingService.java:1), com.x.BookingService, <init>, BookingService.java, 1"
    })
    void supportsModuleClassloaderNestedClassAndConstructorFrames(String text, String type, String method, String file, int line) {
        assertEquals(List.of(new IssueHints.StackFrame(type, method, file, line)), analyzer.analyze("", text).stackFrames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unknown Source", "Native Method"})
    void unknownOrNativeLocationStillPreservesClassAndMethod(String location) {
        var hints = analyzer.analyze("", "at com.x.BookingService.create(" + location + ")");
        assertEquals(List.of(new IssueHints.StackFrame("com.x.BookingService", "create", null, null)), hints.stackFrames());
    }

    @ParameterizedTest
    @ValueSource(strings = {"at com.x.BookingService.create(BookingService.java:0)",
            "at com.x.BookingService.create(BookingService.java:-1)",
            "at com.x.BookingService.create(BookingService.java:999999999999)",
            "at com.x.BookingService.create(BookingService.java:abc)",
            "at NotAFrame(BookingService.java:42)", "at com.x.BookingService.create(BookingService.ts:42)"})
    void malformedOrNonJavaFramesAreNotReturned(String text) {
        assertTrue(analyzer.analyze("", text).stackFrames().isEmpty());
    }

    @Test
    void ordinaryCapitalizedWordsAcronymsAndLowerCamelMethodsAreNotClassHints() {
        var hints = analyzer.analyze("Describe HTTP API failure", "Expected behavior: createRecurringBooking returns null. ÅngströmService also fails.");
        assertEquals(List.of("ÅngströmService"), hints.classNames());
    }

    @Test
    void repeatedFramesAreDeduplicatedButDifferentLineNumbersAreKept() {
        String frame = "at com.x.BookingService.create(BookingService.java:42)";
        var hints = analyzer.analyze("", frame + "\n" + frame + "\n" + frame.replace(":42", ":43"));
        assertEquals(2, hints.stackFrames().size());
        assertEquals(List.of("BookingService.java"), hints.javaFiles());
    }

    @Test
    void nullAndEmptyInputProduceEmptyImmutableHints() {
        var hints = analyzer.analyze(null, null);
        assertEquals(new IssueHints(List.of(), List.of(), List.of()), hints);
        assertEquals(hints, analyzer.analyze("", ""));
        assertThrows(UnsupportedOperationException.class, () -> hints.classNames().add("UnexpectedClass"));
        assertThrows(UnsupportedOperationException.class, () -> hints.javaFiles().add("Unexpected.java"));
        assertThrows(UnsupportedOperationException.class, () -> hints.stackFrames().clear());
    }
}
