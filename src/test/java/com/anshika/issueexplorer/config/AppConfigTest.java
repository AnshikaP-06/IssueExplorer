package com.anshika.issueexplorer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    @TempDir Path temporary;

    @Test
    void bundledDefaultsHaveTheDocumentedValues() {
        assertEquals(new AppConfig(50_000_000, 5000, 200_000_000, 4, 5), AppConfig.defaults());
    }

    @Test
    void partialFileOverridesDefaultsAndFlagsOverrideFile() throws Exception {
        Path file = temporary.resolve("config.properties");
        Files.writeString(file, "parser.threads=2\ntop.results=3\nmax.java.files=20\n");
        var config = AppConfig.load(file);
        assertEquals(2, config.parserThreads());
        assertEquals(3, config.top());
        assertEquals(20, config.maxFiles());
        assertEquals(50_000_000, config.maxDownloadBytes());
        var overridden = config.override(100L, null, null, 1, 2);
        assertEquals(100, overridden.maxDownloadBytes());
        assertEquals(1, overridden.parserThreads());
        assertEquals(2, overridden.top());
        assertEquals(20, overridden.maxFiles());
    }

    @Test
    void emptyFileUsesDefaults() throws Exception {
        Path file = temporary.resolve("empty.properties");
        Files.writeString(file, "# defaults only\n");
        assertEquals(AppConfig.defaults(), AppConfig.load(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"parser.threads=0", "parser.threads=-1", "top.results=0", "max.java.files=-1",
            "max.download.bytes=0", "max.extracted.bytes=-1", "parser.threads=abc", "parser.threads=2147483648",
            "top.results=1.5", "max.download.bytes=9223372036854775808", "unknown.setting=1"})
    void invalidSettingsAreRejected(String text) throws Exception {
        Path file = temporary.resolve("invalid.properties");
        Files.writeString(file, text);
        assertThrows(IllegalArgumentException.class, () -> AppConfig.load(file));
    }

    @Test
    void missingExplicitConfigIsNotSilentlyIgnored() {
        assertThrows(IOException.class, () -> AppConfig.load(temporary.resolve("missing.properties")));
    }

    @Test
    void defaultUserConfigLocationIsLoadedWhenPresent() throws Exception {
        String previous = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.toString());
            assertEquals(AppConfig.defaults(), AppConfig.load(null));
            Files.createDirectories(temporary.resolve(".issue-explorer"));
            Files.writeString(temporary.resolve(".issue-explorer/config.properties"), "parser.threads=1\n");
            assertEquals(1, AppConfig.load(null).parserThreads());
        } finally {
            System.setProperty("user.home", previous);
        }
    }
}
