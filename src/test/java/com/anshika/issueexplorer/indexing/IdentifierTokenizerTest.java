package com.anshika.issueexplorer.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IdentifierTokenizerTest {
    private final IdentifierTokenizer tokenizer = new IdentifierTokenizer();

    static Stream<Arguments> identifiers() {
        return Stream.of(
                example("createRecurringBooking", "create", "recurring", "booking", "createrecurringbooking"),
                example("CreateRecurringBooking", "create", "recurring", "booking", "createrecurringbooking"),
                example("create_recurring_booking", "create", "recurring", "booking", "create_recurring_booking"),
                example("HTTPServer", "http", "server", "httpserver"),
                example("XMLHttpRequest", "xml", "http", "request", "xmlhttprequest"),
                example("getHTTPResponse", "get", "http", "response", "gethttpresponse"),
                example("parseXML", "parse", "xml", "parsexml"),
                example("HTTP", "http"),
                example("HTTP_SERVER", "http", "server", "http_server"),
                example("readJSON2File", "read", "json", "2", "file", "readjson2file"),
                example("HTTP2Server", "http", "2", "server", "http2server"),
                example("version21Update", "version", "21", "update", "version21update"),
                example("2FAEnabled", "2", "fa", "enabled", "2faenabled"),
                example("getURL42", "get", "url", "42", "geturl42"),
                example("parse_v2_JSON", "parse", "v", "2", "json", "parse_v2_json"),
                example("123", "123"),
                example("001Value", "001", "value", "001value"),
                example("field1field2", "field", "1", "2", "field1field2"),
                example("__booking__ID__", "booking", "id", "__booking__id__"),
                example("a__b", "a", "b", "a__b"),
                example("booking", "booking"),
                example("Booking", "booking"),
                example("A", "a"),
                example("xY", "x", "y", "xy"),
                example("ABc", "a", "bc", "abc"),
                example("URLValue", "url", "value", "urlvalue"),
                example("saveSave", "save", "savesave"),
                example("CaféBooking", "café", "booking", "cafébooking"),
                example("cafe\u0301Booking", "cafe\u0301", "booking", "cafe\u0301booking"),
                example("résumé2File", "résumé", "2", "file", "résumé2file"),
                example("count٢Items", "count", "٢", "items", "count٢items"),
                example("$HTTPServer", "http", "server", "$httpserver"),
                example("price$USD", "price", "usd", "price$usd"),
                example("_", "_")
        );
    }

    private static Arguments example(String identifier, String... tokens) {
        return Arguments.of(identifier, List.of(tokens));
    }

    @ParameterizedTest
    @MethodSource("identifiers")
    void splitsWordsAndRetainsCompleteIdentifier(String identifier, List<String> expected) {
        assertEquals(expected, tokenizer.tokenize(identifier));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void blankInputHasNoTokens(String identifier) {
        assertEquals(List.of(), tokenizer.tokenize(identifier));
    }

    @Test
    void nullInputHasClearError() {
        var error = assertThrows(IllegalArgumentException.class, () -> tokenizer.tokenize(null));
        assertEquals("Identifier must not be null.", error.getMessage());
    }

    @Test
    void outputIsImmutable() {
        var tokens = tokenizer.tokenize("HTTPServer");
        assertThrows(UnsupportedOperationException.class, () -> tokens.add("unexpected"));
    }

    @Test
    void repeatedCallsAreIndependentAndDeterministic() {
        var first = tokenizer.tokenize("HTTP2Server");
        tokenizer.tokenize("another_name");
        assertEquals(first, tokenizer.tokenize("HTTP2Server"));
    }

    @Test
    void lowercasingDoesNotDependOnDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("id", "value", "idvalue"), tokenizer.tokenize("IDValue"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
