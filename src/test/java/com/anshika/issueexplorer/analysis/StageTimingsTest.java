package com.anshika.issueexplorer.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StageTimingsTest {
    @Test
    void recordsStageAndTotalUsingMonotonicElapsedTime() throws Exception {
        var clock = new AtomicLong();
        var output = new ArrayList<String>();
        var timings = new StageTimings(() -> clock.getAndAdd(1_000_000), output::add);
        assertEquals("result", timings.measure("parse", () -> "result"));
        var values = timings.finish();
        assertEquals(1.0, values.get("parse"));
        assertEquals(3.0, values.get("total"));
        assertTrue(output.getFirst().contains("Timing parse: 1.000 ms"));
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
    }

    @Test
    void failedStageStillPrintsItsElapsedTime() {
        var clock = new AtomicLong();
        var output = new ArrayList<String>();
        var timings = new StageTimings(() -> clock.getAndAdd(1_000_000), output::add);
        assertThrows(IOException.class, () -> timings.measure("download", () -> { throw new IOException("failure"); }));
        assertTrue(output.getFirst().contains("Timing download:"));
    }
}
