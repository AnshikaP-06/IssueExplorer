package com.example.fixture.booking;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Coordinates weekly recurring reservations and cancellation rules. */
public final class BookingService {
    private final BookingRepository repository;
    private final CancellationPolicy policy;

    /** Uses explicit collaborators so reservation behavior can be tested without a database. */
    public BookingService(BookingRepository repository, CancellationPolicy policy) {
        this.repository = repository;
        this.policy = policy;
    }

    /** Creates the requested number of weekly occurrences, each with a distinct identifier. */
    public List<Booking> createRecurringBooking(String seriesId, LocalDateTime firstStart, int occurrences) {
        if (occurrences <= 0) {
            throw new IllegalArgumentException("Occurrences must be positive");
        }
        var result = new ArrayList<Booking>();
        for (int index = 0; index < occurrences; index++) {
            var booking = new Booking(seriesId + "-" + index, firstStart.plusWeeks(index));
            repository.save(booking);
            result.add(booking);
        }
        return List.copyOf(result);
    }

    /** Cancels a known reservation when it satisfies the required notice period. */
    public boolean cancelBooking(String id, LocalDateTime requestedAt) {
        var booking = repository.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown booking"));
        if (!policy.canCancel(booking, requestedAt)) {
            return false;
        }
        booking.cancel();
        return true;
    }
}
