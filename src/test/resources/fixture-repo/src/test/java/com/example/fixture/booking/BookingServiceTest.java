package com.example.fixture.booking;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises weekly scheduling, repository lookups, and the cancellation deadline. */
class BookingServiceTest {
    /** Recurring reservations have unique identifiers and weekly start dates. */
    @Test
    void createsWeeklyOccurrences() {
        var repository = new BookingRepository();
        var service = new BookingService(repository, new CancellationPolicy(24));
        var start = LocalDateTime.of(2026, 10, 20, 10, 0);
        var bookings = service.createRecurringBooking("series", start, 3);
        assertEquals(3, bookings.size());
        assertEquals(start.plusWeeks(2), bookings.get(2).getStart());
        assertTrue(repository.findById("series-2").isPresent());
        assertThrows(IllegalArgumentException.class, () -> service.createRecurringBooking("invalid", start, 0));
    }

    /** A reservation can be cancelled at the cutoff, but not twice or after the cutoff. */
    @Test
    void respectsCancellationCutoff() {
        var repository = new BookingRepository();
        var service = new BookingService(repository, new CancellationPolicy(24));
        var start = LocalDateTime.of(2026, 10, 20, 10, 0);
        service.createRecurringBooking("series", start, 2);
        assertFalse(service.cancelBooking("series-0", start.minusHours(23)));
        assertTrue(service.cancelBooking("series-0", start.minusHours(24)));
        assertFalse(service.cancelBooking("series-0", start.minusHours(25)));
        assertTrue(repository.findById("series-0").orElseThrow().isCancelled());
        assertThrows(IllegalArgumentException.class, () -> new CancellationPolicy(-1));
        assertThrows(IllegalArgumentException.class, () -> service.cancelBooking("missing", start));
    }
}
