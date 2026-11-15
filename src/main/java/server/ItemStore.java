package server;

import common.Campus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

// "item owner" side of a campus: the items this campus owns, their reservations and waiting queues.
// locking: each Item is locked with synchronized (item)
final class ItemStore {

    private final Campus campus;
    private final ServerLogger logger;

    // DRMSImpl.callServer, used by the queue to contact a user's home campus
    private final BiFunction<Campus, String, String> callServer;
    private final Map<String, Item> items = new ConcurrentHashMap<>(); // itemID -> Item
    private final ExecutorService queueWorker = Executors.newSingleThreadExecutor();

    ItemStore(Campus campus, ServerLogger logger, BiFunction<Campus, String, String> callServer) {
        this.campus = campus;
        this.logger = logger;
        this.callServer = callServer;
    }

    //manager operations

    // creates the item, or updates it if it already exists
    String addItem(String itemID, String type, String name, int quantity) {
        Item existing = items.putIfAbsent(itemID, new Item(itemID, type, name, quantity));
        if (existing == null) {
            return "OK:Created item " + itemID + " (" + type + ", " + name + ", quantity " + quantity + ")";
        }
        synchronized (existing) {
            if (existing.removed) {
                return "ERROR:Item " + itemID + " was removed at the same time; please retry";
            }

            int peak = existing.peakOverlap(LocalDateTime.now(), LocalDateTime.MAX, null);
            if (quantity < peak) {
                return "ERROR:Quantity " + quantity + " is below the " + peak
                        + " overlapping scheduled reservations; item unchanged";
            }
            boolean grew = quantity > existing.quantity;
            existing.type = type;
            existing.name = name;
            existing.quantity = quantity;
            if (grew) {
                scheduleQueue(itemID); // more units -> someone in the queue may book now
            }
            return "OK:Updated item " + itemID + " (" + type + ", " + name + ", quantity " + quantity + ")";
        }
    }

    String removeItem(String itemID) {
        Item item = items.get(itemID);
        if (item == null) {
            return "ERROR:Item " + itemID + " does not exist";
        }
        synchronized (item) {
            if (item.removed) {
                return "ERROR:Item " + itemID + " does not exist";
            }

            // not allowed while someone has a current or future reservation
            LocalDateTime now = LocalDateTime.now();
            if (item.reservations.stream().anyMatch(r -> r.end().isAfter(now))) {
                return "ERROR:Item " + itemID + " has current or future reservations";
            }
            int cancelledRequests = item.queue.size();
            item.queue.clear();
            item.removed = true;
            items.remove(itemID);
            return "OK:Removed item " + itemID + "; cancelled " + cancelledRequests + " waiting request(s)";
        }
    }

    // units free right now
    String listAvailableItems() {
        LocalDateTime now = LocalDateTime.now();
        StringBuilder out = new StringBuilder("Items at " + campus + " (available now):");
        for (Item item : new TreeMap<>(items).values()) {
            synchronized (item) {
                if (!item.removed) {
                    out.append("\n  ").append(item.id).append(" | ").append(item.type).append(" | ")
                            .append(item.name).append(" | ").append(item.availableAt(now))
                            .append(" of ").append(item.quantity).append(" available");
                }
            }
        }
        return "OK:" + (items.isEmpty() ? "No items at " + campus : out);
    }

    // dashboard counts: {items, reservations, waiting}
    int[] counts() {
        int[] c = new int[3];
        for (Item item : items.values()) {
            synchronized (item) {
                if (!item.removed) {
                    c[0]++;
                    c[1] += item.reservations.size();
                    c[2] += item.queue.size();
                }
            }
        }
        return c;
    }

    // requests from a user's home server
    String reserve(String userID, String reservationID, String itemID, LocalDateTime start, LocalDateTime end) {
        Item item = items.get(itemID);
        if (item == null) {
            return "ERROR:Item " + itemID + " does not exist";
        }
        synchronized (item) {
            if (item.removed) {
                return "ERROR:Item " + itemID + " does not exist";
            }
            if (!item.hasCapacity(start, end, null)) {
                return "FULL:No free unit of " + itemID + " for that interval";
            }
            item.reservations.add(new Reservation(reservationID, userID, itemID, start, end));
            return "OK:" + reservationID;
        }
    }

    // only allowed when there's really no free unit (or the user should just reserve)
    String enqueue(String userID, String reservationID, String itemID, LocalDateTime start, LocalDateTime end) {
        Item item = items.get(itemID);
        if (item == null) {
            return "ERROR:Item " + itemID + " does not exist";
        }
        synchronized (item) {
            if (item.removed) {
                return "ERROR:Item " + itemID + " does not exist";
            }
            if (item.hasCapacity(start, end, null)) {
                return "ERROR:A unit of " + itemID + " is free for this interval now; reserve it directly";
            }
            item.queue.addLast(new Item.WaitEntry(reservationID, userID, start, end, LocalDateTime.now()));
            return "OK:" + item.queue.size(); // position in the queue
        }
    }

    String update(String userID, String reservationID, String itemID, LocalDateTime start, LocalDateTime end) {
        Item item = items.get(itemID);
        if (item == null) {
            return "ERROR:Item " + itemID + " does not exist";
        }
        synchronized (item) {
            int index = indexOf(item.reservations, reservationID, userID);
            if (item.removed || index < 0) {
                return "ERROR:Reservation " + reservationID + " not found at " + campus;
            }
            if (!item.hasCapacity(start, end, reservationID)) { // ignores its own old interval
                return "FULL:No free unit of " + itemID + " for the new interval";
            }
            // replace it in one step
            item.reservations.set(index, new Reservation(reservationID, userID, itemID, start, end));
        }
        scheduleQueue(itemID);
        return "OK:";
    }

    String cancel(String userID, String reservationID, String itemID) {
        Item item = items.get(itemID);
        if (item == null) {
            return "ERROR:Reservation " + reservationID + " not found at " + campus;
        }
        synchronized (item) {
            boolean removed = !item.removed && item.reservations.removeIf(
                    r -> r.id().equals(reservationID) && r.userID().equals(userID));
            if (!removed) {
                return "ERROR:Reservation " + reservationID + " not found at " + campus;
            }
        }
        scheduleQueue(itemID);
        return "OK:";
    }

    // read only, every item of that type
    String find(String itemType, LocalDateTime start, LocalDateTime end) {
        StringBuilder lines = new StringBuilder();
        for (Item item : new TreeMap<>(items).values()) {
            synchronized (item) {
                if (!item.removed && item.type.equalsIgnoreCase(itemType)) {
                    lines.append(item.id).append(" | ").append(item.type).append(" | ").append(item.name)
                            .append(" | ").append(campus).append(" | ").append(item.freeUnits(start, end))
                            .append('\n');
                }
            }
        }
        return "OK:" + lines;
    }

    // waiting queue
    private void scheduleQueue(String itemID) {
        queueWorker.submit(() -> processQueue(itemID));
    }

    // for queue in FIFO order
    private void processQueue(String itemID) {
        Item item = items.get(itemID);
        if (item == null) {
            return;
        }
        List<Item.WaitEntry> waiting;
        synchronized (item) {
            if (item.removed) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            item.queue.removeIf(w -> w.start().isBefore(now)); // drop expired requests
            waiting = new ArrayList<>(item.queue);
        }

        for (Item.WaitEntry entry : waiting) {
            Reservation held = new Reservation(entry.reservationID(), entry.userID(), itemID,
                    entry.start(), entry.end());

            synchronized (item) {
                if (item.removed || !item.queue.contains(entry) || !item.hasCapacity(entry.start(), entry.end(), null)) {
                    continue; // try the next one
                }
                item.reservations.add(held);
            }

            String reply = callServer.apply(Campus.of(entry.userID()), PeerClient.message("QUEUE_ASSIGN",
                    entry.userID(), entry.reservationID(), itemID, format(entry.start()), format(entry.end())));
            boolean accepted = false;
            if (reply != null) {
                if (reply.startsWith("OK")) {
                    accepted = true;
                }
            }

            synchronized (item) {
                if (accepted) {
                    item.queue.remove(entry);
                } else {
                    item.reservations.remove(held);
                }
            }

            String result;
            if (accepted) {
                result = "OK:Assigned " + itemID + " from the waiting queue as " + entry.reservationID();
            } else if (reply == null) {
                result = "ERROR:Skipped, keeps its queue position: home server unreachable";
            } else {
                String reason = reply.substring(reply.indexOf(':') + 1); // "ERROR:reason" -> "reason"
                result = "ERROR:Skipped, keeps its queue position: " + reason;
            }
            logger.log("queueAssign", entry.userID(), result);
        }
    }

    private static int indexOf(List<Reservation> reservations, String reservationID, String userID) {
        for (int i = 0; i < reservations.size(); i++) {
            Reservation r = reservations.get(i);
            if (r.id().equals(reservationID) && r.userID().equals(userID)) {
                return i;
            }
        }
        return -1;
    }

    private static String format(LocalDateTime time) {
        return time.format(Reservation.DATE_FORMAT);
    }
}
