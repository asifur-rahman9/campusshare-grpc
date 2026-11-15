package server;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationTest {

    private static LocalDateTime at(String text) {
        return LocalDateTime.parse(text, Reservation.DATE_FORMAT);
    }

    @Test
    void overlapUsesHalfOpenIntervals() {
        Reservation r = new Reservation("R1", "SGWU1111", "SGW1001", at("2026-11-23 10:00"), at("2026-11-23 12:00"));

        assertTrue(r.overlaps(at("2026-11-23 11:00"), at("2026-11-23 13:00")));
        assertFalse(r.overlaps(at("2026-11-23 12:00"), at("2026-11-23 14:00")), "back-to-back slots don't overlap");
        assertFalse(r.overlaps(at("2026-11-23 08:00"), at("2026-11-23 10:00")));
    }

    @Test
    void minutesAreSplitAtMondayMidnight() {
        // Sunday 22:00 -> Monday 02:00 crosses into a new week
        Map<LocalDate, Long> perWeek = Reservation.minutesPerWeek(at("2026-11-22 22:00"), at("2026-11-23 02:00"));

        assertEquals(2, perWeek.size());
        assertEquals(120L, perWeek.get(LocalDate.of(2026, 11, 16)));
        assertEquals(120L, perWeek.get(LocalDate.of(2026, 11, 23)));
    }
}
