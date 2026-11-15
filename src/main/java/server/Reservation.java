package server;

import common.Campus;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.TreeMap;

// one reservation = one unit of an item for [start, end)
// both the item's campus (capacity) and the user's home campus (for budget/cross-campus) keep copy
// final fields so it can't be changed, an update replaces it with a new one
final class Reservation {

    // date format shared by the server classes
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String id;
    private final String userID;
    private final String itemID;
    private final LocalDateTime start;
    private final LocalDateTime end;

    Reservation(String id, String userID, String itemID, LocalDateTime start, LocalDateTime end) {
        this.id = id;
        this.userID = userID;
        this.itemID = itemID;
        this.start = start;
        this.end = end;
    }

    String id() {
        return id;
    }

    String userID() {
        return userID;
    }

    String itemID() {
        return itemID;
    }

    LocalDateTime start() {
        return start;
    }

    LocalDateTime end() {
        return end;
    }

    Campus campus() {
        return Campus.of(itemID);
    }

    boolean overlaps(LocalDateTime otherStart, LocalDateTime otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    static Map<LocalDate, Long> minutesPerWeek(LocalDateTime start, LocalDateTime end) {
        Map<LocalDate, Long> perWeek = new TreeMap<>(); // sorted so weeks come out in order
        LocalDateTime cursor = start;

        while (cursor.isBefore(end)) {
            LocalDate monday = cursor.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDateTime nextMonday = monday.plusWeeks(1).atStartOfDay();

            LocalDateTime segmentEnd = end.isBefore(nextMonday) ? end : nextMonday;
            perWeek.merge(monday, Duration.between(cursor, segmentEnd).toMinutes(), Long::sum);
            cursor = segmentEnd;
        }
        return perWeek;
    }
}
