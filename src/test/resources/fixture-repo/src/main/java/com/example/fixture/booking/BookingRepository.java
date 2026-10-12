package com.example.fixture.booking;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory reservation storage used by the small fixture application. */
public final class BookingRepository {
    private final Map<String, Booking> bookings = new LinkedHashMap<>();

    /** Saves a reservation under its identifier, replacing an existing value. */
    public void save(Booking booking) {
        bookings.put(booking.getId(), booking);
    }

    /** Looks up a reservation without treating an absent identifier as an error. */
    public Optional<Booking> findById(String id) {
        return Optional.ofNullable(bookings.get(id));
    }
}
