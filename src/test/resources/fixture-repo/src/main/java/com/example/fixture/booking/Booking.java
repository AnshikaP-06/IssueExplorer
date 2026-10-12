package com.example.fixture.booking;

import java.time.LocalDateTime;

/** A scheduled room reservation whose cancellation status is kept with the booking. */
public final class Booking {
    private final String id;
    private final LocalDateTime start;
    private boolean cancelled;

    /** Creates a reservation with a stable identifier and a local start time. */
    public Booking(String id, LocalDateTime start) {
        this.id = id;
        this.start = start;
    }

    /** Returns the identifier used to find this reservation. */
    public String getId() {
        return id;
    }

    /** Returns the scheduled start of the reservation. */
    public LocalDateTime getStart() {
        return start;
    }

    /** Records a cancellation without removing the reservation from history. */
    public void cancel() {
        cancelled = true;
    }

    /** Indicates whether the reservation has already been cancelled. */
    public boolean isCancelled() {
        return cancelled;
    }
}
