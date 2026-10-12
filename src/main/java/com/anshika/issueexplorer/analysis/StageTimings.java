package com.anshika.issueexplorer.analysis;

import com.anshika.issueexplorer.github.GitHubClient;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class StageTimings {
    @FunctionalInterface
    public interface Work<T> {
        T run() throws IOException, GitHubClient.GitHubException;
    }
    private final LongSupplier nanos;
    private final Consumer<String> output;
    private final long started;
    private final Map<String, Double> values = new LinkedHashMap<>();

    public StageTimings(Consumer<String> output) {
        this(System::nanoTime, output);
    }

    public StageTimings(LongSupplier nanos, Consumer<String> output) {
        this.nanos = nanos;
        this.output = output;
        started = nanos.getAsLong();
    }

    public <T> T measure(String stage, Work<T> work) throws IOException, GitHubClient.GitHubException {
        long start = nanos.getAsLong();
        try {
            return work.run();
        } finally {
            record(stage, start);
        }
    }

    public Map<String, Double> finish() {
        record("total", started);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private void record(String stage, long start) {
        double milliseconds = Math.max(0L, nanos.getAsLong() - start) / 1_000_000.0;
        values.put(stage, milliseconds);
        output.accept(String.format(Locale.ROOT, "Timing %s: %.3f ms", stage, milliseconds));
    }
}
