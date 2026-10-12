package com.anshika.issueexplorer.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public record AppConfig(long maxDownloadBytes, int maxFiles, long maxExtractedBytes, int parserThreads, int top) {
    private static final Set<String> KEYS = Set.of("max.download.bytes", "max.java.files", "max.extracted.bytes", "parser.threads", "top.results");
    private static final AppConfig DEFAULTS = readDefaults();

    public AppConfig {
        if (maxDownloadBytes <= 0 || maxFiles <= 0 || maxExtractedBytes <= 0 || parserThreads <= 0 || top <= 0) {
            throw new IllegalArgumentException("Download/extraction limits, parser threads, and top result count must be positive.");
        }
    }

    public static AppConfig defaults() {
        return DEFAULTS;
    }

    public static AppConfig load(Path explicitFile) throws IOException {
        Path file = explicitFile == null ? Path.of(System.getProperty("user.home"), ".issue-explorer", "config.properties") : explicitFile;
        if (explicitFile == null && !Files.exists(file)) {
            return defaults();
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("Configuration file is missing or is not a regular file.");
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Malformed configuration; use key=value properties.");
        }
        return fromProperties(properties, defaults());
    }

    public AppConfig override(Long download, Integer files, Long extracted, Integer threads, Integer results) {
        return new AppConfig(download == null ? maxDownloadBytes : download, files == null ? maxFiles : files,
                extracted == null ? maxExtractedBytes : extracted, threads == null ? parserThreads : threads,
                results == null ? top : results);
    }

    private static AppConfig readDefaults() {
        try (var input = AppConfig.class.getResourceAsStream("/issue-explorer-defaults.properties")) {
            if (input == null) {
                throw new IllegalStateException("Bundled configuration defaults are missing.");
            }
            var properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return fromProperties(properties, null);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled configuration defaults.", exception);
        }
    }

    private static AppConfig fromProperties(Properties properties, AppConfig fallback) {
        if (!KEYS.containsAll(properties.stringPropertyNames())) {
            throw new IllegalArgumentException("Configuration contains an unknown property.");
        }
        try {
            long download = number(properties, "max.download.bytes", fallback == null ? null : fallback.maxDownloadBytes);
            int files = Math.toIntExact(number(properties, "max.java.files", fallback == null ? null : (long) fallback.maxFiles));
            long extracted = number(properties, "max.extracted.bytes", fallback == null ? null : fallback.maxExtractedBytes);
            int threads = Math.toIntExact(number(properties, "parser.threads", fallback == null ? null : (long) fallback.parserThreads));
            int top = Math.toIntExact(number(properties, "top.results", fallback == null ? null : (long) fallback.top));
            return new AppConfig(download, files, extracted, threads, top);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Configuration values must be positive whole numbers within supported ranges.");
        }
    }

    private static long number(Properties properties, String key, Long fallback) {
        String value = properties.getProperty(key);
        if (value == null) {
            if (fallback == null) {
                throw new IllegalStateException("Missing bundled default: " + key);
            }
            return fallback;
        }
        return Long.parseLong(value.strip());
    }
}
