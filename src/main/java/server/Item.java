package server;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

// item owned by a campus: quantity, its reservations and its waiting queue
final class Item {

    static final class WaitEntry {
        private final String reservationID;
        private final String userID;
        private final LocalDateTime start;
        private final LocalDateTime end;
        private final LocalDateTime enqueuedAt;

        WaitEntry(String reservationID, String userID, LocalDateTime start, LocalDateTime end,
                  LocalDateTime enqueuedAt) {
            this.reservationID = reservationID;
            this.userID = userID;
            this.start = start;
            this.end = end;
            this.enqueuedAt = enqueuedAt;
        }

        String reservationID() {
            return reservationID;
        }

        String userID() {
            return userID;
        }

        LocalDateTime start() {
            return start;
        }

        LocalDateTime end() {
            return end;
        }

        LocalDateTime enqueuedAt() {
            return enqueuedAt;
        }
    }

    final String id;
    String type;
    String name;
    int quantity;
    // set when removed, so a thread that already got this Item from the map knows it's gone
    boolean removed;
    final List<Reservation> reservations = new ArrayList<>();
    final Deque<WaitEntry> queue = new ArrayDeque<>(); // FIFO: add at the back, oldest at the front

    Item(String id, String type, String name, int quantity) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.quantity = quantity;
    }

    // busiest moment is always at 'from' or when some reservation starts inside the window,
    // so only count at those moments for comparison during those intervals
    int peakOverlap(LocalDateTime from, LocalDateTime to, String excludeId) {
        // moments to check: the window start + every reservation start inside the window
        List<LocalDateTime> points = new ArrayList<>();
        points.add(from);
        for (Reservation r : reservations) {
            if (r.start().isAfter(from) && r.start().isBefore(to)) {
                points.add(r.start());
            }
        }

        int peak = 0;
        for (LocalDateTime p : points) {
            int active = 0;
            for (Reservation r : reservations) {
                boolean skip = r.id().equals(excludeId);                         // reservation being updated
                boolean activeAtP = !r.start().isAfter(p) && r.end().isAfter(p); // start <= p < end
                if (!skip && activeAtP) {
                    active++;
                }
            }
            peak = Math.max(peak, active);
        }
        return peak;
    }

    // true if even at the busiest moment there's a free unit
    boolean hasCapacity(LocalDateTime from, LocalDateTime to, String excludeId) {
        return peakOverlap(from, to, excludeId) < quantity;
    }

    // units free for the whole interval (findItem)
    int freeUnits(LocalDateTime from, LocalDateTime to) {
        return Math.max(0, quantity - peakOverlap(from, to, null));
    }

    // units free at one moment
    int availableAt(LocalDateTime instant) {
        long inUse = reservations.stream()
                .filter(r -> !r.start().isAfter(instant) && r.end().isAfter(instant))
                .count();
        return (int) Math.max(0, quantity - inUse);
    }
}
