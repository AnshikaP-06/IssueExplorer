package com.example.fixture.booking;

import java.time.LocalDateTime;

/** Allows cancellation until the configured number of hours before a reservation starts. */
public final class CancellationPolicy {
    private final int noticeHours;

    /** Creates a policy requiring a non-negative notice period. */
    public CancellationPolicy(int noticeHours) {
        if (noticeHours < 0) {
            throw new IllegalArgumentException("Notice hours must not be negative");
        }
        this.noticeHours = noticeHours;
    }

    /** Returns whether the request is at or before the cancellation deadline. */
    public boolean canCancel(Booking booking, LocalDateTime requestedAt) {
        return !booking.isCancelled() && !requestedAt.isAfter(booking.getStart().minusHours(noticeHours));
    }
}
