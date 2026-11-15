package server;

import common.Campus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

// Server for one campus, clients call it over gRPC
public final class DRMSImpl {

    //SGWM1111 (manager), SGWU1111 (user), SGW1001 (item)
    private static final Pattern MANAGER_ID = Pattern.compile("(SGW|LOY|WIL)M\\d{4}");
    private static final Pattern USER_ID = Pattern.compile("(SGW|LOY|WIL)U\\d{4}");
    private static final Pattern ITEM_ID = Pattern.compile("(SGW|LOY|WIL)\\d{4}");

    private static final long WEEKLY_LIMIT_MINUTES = 20 * 60; // in minutes and amounts to 20h

    // queue assignment waits max 1s for a busy user
    // less than the 3s peer call timeout
    private static final long QUEUE_LOCK_WAIT_MS = 1000;

    private final Campus campus;
    private final ServerLogger logger;
    private final ItemStore store;
    private final AtomicLong reservationCounter = new AtomicLong();

    // findItem asks the 3 campuses at once
    private final ExecutorService findPool = Executors.newCachedThreadPool();

    private final Map<String, List<Reservation>> ledger = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public DRMSImpl(Campus campus, ServerLogger logger) {
        this.campus = campus;
        this.logger = logger;
        this.store = new ItemStore(campus, logger, this::callServer);
    }

    // the 8 client operations, DRMSGrpcService forwards each call here
    public String addItem(String managerID, String itemID, String itemType, String itemName, int itemQuantity) {
        return logged("addItem", managerID, () -> doAddItem(managerID, itemID, itemType, itemName, itemQuantity));
    }

    public String removeItem(String managerID, String itemID) {
        return logged("removeItem", managerID, () -> doRemoveItem(managerID, itemID));
    }

    public String listAvailableItems(String managerID) {
        return logged("listAvailableItems", managerID, () -> doListAvailableItems(managerID));
    }

    public String reserveItem(String userID, String itemID, String startDateTime, String endDateTime) {
        return logged("reserveItem", userID, () -> doReserveItem(userID, itemID, startDateTime, endDateTime));
    }

    public String joinWaitingQueue(String userID, String itemID, String startDateTime, String endDateTime) {
        return logged("joinWaitingQueue", userID, () -> doJoinWaitingQueue(userID, itemID, startDateTime, endDateTime));
    }

    public String updateReservation(String userID, String reservationID, String newStartDateTime, String newEndDateTime) {
        return logged("updateReservation", userID,
                () -> doUpdateReservation(userID, reservationID, newStartDateTime, newEndDateTime));
    }

    public String cancelReservation(String userID, String reservationID) {
        return logged("cancelReservation", userID, () -> doCancelReservation(userID, reservationID));
    }

    public String findItem(String userID, String itemType, String startDateTime, String endDateTime) {
        return logged("findItem", userID, () -> doFindItem(userID, itemType, startDateTime, endDateTime));
    }

    // dashboard: {items, reservations, waiting} for the items this campus owns
    public int[] status() {
        return store.counts();
    }

    public Campus campus() {
        return campus;
    }

    // manager operations: validated here, ItemStore does the actual work
    private String doAddItem(String managerID, String itemID, String type, String name, int quantity) {
        String error = checkId(managerID, true);
        if (error != null) {
            return reject(error);
        }
        error = checkItemId(itemID, true); // item must belong to the manager's campus
        if (error != null) {
            return reject(error);
        }
        if (isBlank(type) || isBlank(name)) {
            return reject("Item type and name are required");
        }
        if (quantity <= 0) {
            return reject("Quantity must be a positive integer");
        }
        String reply = store.addItem(itemID, type.trim(), name.trim(), quantity);
        return toClientReply(reply);
    }

    private String doRemoveItem(String managerID, String itemID) {
        String error = checkId(managerID, true);
        if (error != null) {
            return reject(error);
        }
        error = checkItemId(itemID, true);
        if (error != null) {
            return reject(error);
        }
        String reply = store.removeItem(itemID);
        return toClientReply(reply);
    }

    private String doListAvailableItems(String managerID) {
        String error = checkId(managerID, true);
        if (error != null) {
            return reject(error);
        }
        String reply = store.listAvailableItems();
        return toClientReply(reply);
    }

    // user operations, order of checks:
    //  - cross-campus
    //  - weekly budget
    //  - capacity

    private String doReserveItem(String userID, String itemID, String startText, String endText) {
        // input checks first, no lock required
        LocalDateTime start = parse(startText);
        LocalDateTime end = parse(endText);
        String error = firstError(checkId(userID, false), checkItemId(itemID, false), checkInterval(start, end, true));
        if (error != null) {
            return reject(error);
        }

        // locks user so their requests run one at a time
        // so 2 requests can't both pass the checks at the same time
        ReentrantLock lock = userLock(userID);
        lock.lock();
        try {
            List<Reservation> mine = reservationsOf(userID);
            // checks cross campus, weekly budget. capacity is done by the item's campus
            error = firstError(
                    checkCrossCampus(mine, itemID, start, end, null),
                    checkBudget(mine, start, end, null));
            if (error != null) {
                return reject(error);
            }

            String reservationID = nextReservationId();
            Campus owner = Campus.of(itemID);
            String reply = callServer(owner,
                    PeerClient.message("RESERVE", userID, reservationID, itemID, format(start), format(end)));
            if (reply == null) {
                callServer(owner, PeerClient.message("CANCEL", userID, reservationID, itemID));
                return fail(owner + " server did not respond; reservation not made");
            }
            if (reply.startsWith("FULL")) {
                return reject("NO_CAPACITY: no free unit of " + itemID
                        + " for that interval. You may join its waiting queue.");
            }
            if (!reply.startsWith("OK")) {
                return reject(body(reply)); // item doesn't exist
            }

            mine.add(new Reservation(reservationID, userID, itemID, start, end));
            return success("Reserved " + itemID + " from " + format(start) + " to " + format(end)
                    + ". Reservation ID: " + reservationID);
        } finally {
            lock.unlock();
        }
    }

    // same checks as reserve, but sends ENQUEUE. ItemStore refuses it if a unit is actually free
    private String doJoinWaitingQueue(String userID, String itemID, String startText, String endText) {
        LocalDateTime start = parse(startText);
        LocalDateTime end = parse(endText);
        String error = firstError(checkId(userID, false), checkItemId(itemID, false), checkInterval(start, end, true));
        if (error != null) {
            return reject(error);
        }

        ReentrantLock lock = userLock(userID);
        lock.lock();
        try {
            // the queue is only offered if cross-campus and budget pass
            List<Reservation> mine = reservationsOf(userID);
            error = firstError(
                    checkCrossCampus(mine, itemID, start, end, null),
                    checkBudget(mine, start, end, null));
            if (error != null) {
                return reject(error);
            }

            String reservationID = nextReservationId();
            Campus owner = Campus.of(itemID);
            String reply = callServer(owner,
                    PeerClient.message("ENQUEUE", userID, reservationID, itemID, format(start), format(end)));
            if (reply == null) {
                return fail(owner + " server did not respond; not added to the waiting queue");
            }
            if (!reply.startsWith("OK")) {
                return reject(body(reply));
            }
            return success("Added to the waiting queue of " + itemID + " at position " + body(reply)
                    + ". If a unit is assigned, your reservation ID will be " + reservationID);
        } finally {
            lock.unlock();
        }
    }

    // update has to be atomic: either the reservation fully moves to the new times or nothing changes
    private String doUpdateReservation(String userID, String reservationID, String startText, String endText) {
        LocalDateTime start = parse(startText);
        LocalDateTime end = parse(endText);
        String error = firstError(
                checkId(userID, false),
                isBlank(reservationID) ? "Reservation ID is required" : null,
                checkInterval(start, end, true));
        if (error != null) {
            return reject(error);
        }

        ReentrantLock lock = userLock(userID);
        lock.lock();
        try {
            List<Reservation> mine = reservationsOf(userID);
            // makes sure to only search this user's list
            Reservation old = findReservation(mine, reservationID);
            if (old == null) {
                return reject("Reservation " + reservationID + " not found for user " + userID);
            }

            error = firstError(
                    checkCrossCampus(mine, old.itemID(), start, end, old.id()),
                    checkBudget(mine, start, end, old.id()));
            if (error != null) {
                return reject(error + " (reservation unchanged)");
            }

            String reply = callServer(old.campus(),
                    PeerClient.message("UPDATE", userID, old.id(), old.itemID(), format(start), format(end)));
            if (reply == null) {
                return fail(old.campus() + " server did not respond; reservation unchanged");
            }
            if (!reply.startsWith("OK")) {
                return reject(body(reply) + " (reservation unchanged)");
            }

            mine.set(mine.indexOf(old), new Reservation(old.id(), userID, old.itemID(), start, end));
            return success("Reservation " + old.id() + " now runs from " + format(start) + " to " + format(end));
        } finally {
            lock.unlock();
        }
    }

    private String doCancelReservation(String userID, String reservationID) {
        String error = firstError(checkId(userID, false), isBlank(reservationID) ? "Reservation ID is required" : null);
        if (error != null) {
            return reject(error);
        }

        ReentrantLock lock = userLock(userID);
        lock.lock();
        try {
            List<Reservation> mine = reservationsOf(userID);
            Reservation old = findReservation(mine, reservationID);
            if (old == null) {
                return reject("Reservation " + reservationID + " not found for user " + userID);
            }

            String reply = callServer(old.campus(), PeerClient.message("CANCEL", userID, old.id(), old.itemID()));
            if (reply == null) {
                return fail(old.campus() + " server did not respond; reservation unchanged");
            }
            if (!reply.startsWith("OK")) {
                return reject(body(reply));
            }
            mine.remove(old);
            StringBuilder credited = new StringBuilder();
            Reservation.minutesPerWeek(old.start(), old.end()).forEach((week, minutes) ->
                    credited.append(" [week of ").append(week).append(": ").append(hours(minutes)).append("]"));
            return success("Cancelled " + old.id() + ". Hours credited back:" + credited);
        } finally {
            lock.unlock();
        }
    }

    // read only, no user lock required
    private String doFindItem(String userID, String itemType, String startText, String endText) {
        LocalDateTime start = parse(startText);
        LocalDateTime end = parse(endText);
        String error = firstError(
                checkId(userID, false),
                isBlank(itemType) ? "Item type is required" : null,
                checkInterval(start, end, false)); // false: searching the past is allowed
        if (error != null) {
            return reject(error);
        }

        // ask all 3 campuses at the same time, each call gives up after PeerClient.TIMEOUT_MS
        String request = PeerClient.message("FIND", userID, itemType.trim(), format(start), format(end));
        Map<Campus, Future<String>> replies = new EnumMap<>(Campus.class);
        for (Campus c : Campus.values()) {
            replies.put(c, findPool.submit(() -> callServer(c, request)));
        }

        StringBuilder out = new StringBuilder("Items of type '" + itemType.trim() + "' free from "
                + format(start) + " to " + format(end) + " (itemID | type | name | campus | free units):");
        for (Map.Entry<Campus, Future<String>> entry : replies.entrySet()) {
            String reply = await(entry.getValue());
            if (reply == null) {
                out.append("\n  ").append(entry.getKey()).append(": UNAVAILABLE (no response within ")
                        .append(PeerClient.TIMEOUT_MS).append(" ms)");
            } else if (isBlank(body(reply))) {
                out.append("\n  ").append(entry.getKey()).append(": no matching items");
            } else {
                out.append("\n  ").append(body(reply).trim().replace("\n", "\n  "));
            }
        }
        return success(out.toString());
    }

    // server-to-server requests, handles one message from another server
    // format OPERATION|field|field|etc
    String dispatch(String message) {
        String[] f = PeerClient.fields(message);
        try {
            switch (f[0]) {
                case "RESERVE":
                    return store.reserve(f[1], f[2], f[3], parse(f[4]), parse(f[5]));
                case "ENQUEUE":
                    return store.enqueue(f[1], f[2], f[3], parse(f[4]), parse(f[5]));
                case "UPDATE":
                    return store.update(f[1], f[2], f[3], parse(f[4]), parse(f[5]));
                case "CANCEL":
                    return store.cancel(f[1], f[2], f[3]);
                case "FIND":
                    return store.find(f[2], parse(f[3]), parse(f[4]));
                //an item's campus asks the user's home server to accept a queued request
                case "QUEUE_ASSIGN":
                    return acceptQueuedRequest(f[1], f[2], f[3], parse(f[4]), parse(f[5]));
                default:
                    return "ERROR:Unknown operation " + f[0];
            }
        } catch (RuntimeException e) {
            return "ERROR:Malformed request";
        }
    }

    private String callServer(Campus target, String message) {
        if (target == campus) {
            // own campus, no network call needed
            return dispatch(message);
        } else {
            // another campus. send over gRPC and wait for the reply
            return PeerClient.send(target, message);
        }
    }

    // QUEUE_ASSIGN: unit is free for the user's queued request
    private String acceptQueuedRequest(String userID, String reservationID, String itemID,
                                       LocalDateTime start, LocalDateTime end) {
        ReentrantLock lock = userLock(userID);
        try {
            if (!lock.tryLock(QUEUE_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return "ERROR:User busy with another request";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "ERROR:Interrupted";
        }
        try {
            List<Reservation> mine = reservationsOf(userID);
            String error = firstError(
                    checkCrossCampus(mine, itemID, start, end, null),
                    checkBudget(mine, start, end, null));
            if (error != null) {
                return "ERROR:" + error;
            }
            mine.add(new Reservation(reservationID, userID, itemID, start, end));
            return "OK:";
        } finally {
            lock.unlock();
        }
    }

    // excludeId = reservation being updated, so it's not counted against itself, null for new ones
    private String checkCrossCampus(List<Reservation> mine, String itemID, LocalDateTime start,
                                    LocalDateTime end, String excludeId) {
        Campus target = Campus.of(itemID);
        if (target == campus) {
            return null;
        }
        for (Reservation r : mine) {
            if (!r.id().equals(excludeId) && r.campus() == target && r.overlaps(start, end)) {
                return "Cross-campus limit: you already hold overlapping reservation " + r.id() + " at " + target;
            }
        }
        return null;
    }

    // max 20h per calendar week, Mon 00:00 - Mon 00:00
    // overlapping reservations still count separately
    private String checkBudget(List<Reservation> mine, LocalDateTime start, LocalDateTime end, String excludeId) {
        Map<LocalDate, Long> used = new TreeMap<>();
        for (Reservation r : mine) {
            if (!r.id().equals(excludeId)) {
                Reservation.minutesPerWeek(r.start(), r.end()).forEach((week, minutes) -> used.merge(week, minutes, Long::sum));
            }
        }

        // the new request split by week too, then check every week it touches
        Map<LocalDate, Long> requested = Reservation.minutesPerWeek(start, end);
        boolean exceeded = requested.entrySet().stream()
                .anyMatch(e -> used.getOrDefault(e.getKey(), 0L) + e.getValue() > WEEKLY_LIMIT_MINUTES);
        if (!exceeded) {
            return null;
        }
        StringBuilder message = new StringBuilder("Weekly 20h budget exceeded. Remaining budget:");
        requested.forEach((week, minutes) -> message
                .append(" [week of ").append(week).append(": ")
                .append(hours(Math.max(0, WEEKLY_LIMIT_MINUTES - used.getOrDefault(week, 0L))))
                .append(" left, ").append(hours(minutes)).append(" requested]"));
        return message.toString();
    }

    // id role validation
    private String checkId(String id, boolean managerOperation) {
        if (id == null) {
            return "ID is required";
        }
        if (managerOperation && USER_ID.matcher(id).matches()) {
            return "User " + id + " cannot perform manager operations";
        }
        if (!managerOperation && MANAGER_ID.matcher(id).matches()) {
            return "Manager " + id + " cannot perform user operations";
        }
        if (!(managerOperation ? MANAGER_ID : USER_ID).matcher(id).matches()) {
            return "Invalid ID format: " + id;
        }
        if (Campus.of(id) != campus) {
            return "Requests from " + id + " must be sent to its home campus " + Campus.of(id);
        }
        return null;
    }

    // mustBeOwnCampus is true for managers, false for users
    private String checkItemId(String itemID, boolean mustBeOwnCampus) {
        if (itemID == null || !ITEM_ID.matcher(itemID).matches()) {
            return "Invalid item ID (expected campus + 4 digits, e.g. SGW1001): " + itemID;
        }
        if (mustBeOwnCampus && Campus.of(itemID) != campus) {
            return "Managers can only manage items of their own campus (" + campus + ")";
        }
        return null;
    }

    private static String checkInterval(LocalDateTime start, LocalDateTime end, boolean mustNotBeInPast) {
        if (start == null || end == null) {
            return "Dates must use the format yyyy-MM-dd HH:mm";
        }
        if (!start.isBefore(end)) {
            return "Start must be earlier than end";
        }
        if (mustNotBeInPast && start.isBefore(LocalDateTime.now())) {
            return "Start time is in the past";
        }
        return null;
    }

    // one lock per user, created the first time we see them
    private ReentrantLock userLock(String userID) {
        return userLocks.computeIfAbsent(userID, id -> new ReentrantLock());
    }

    private List<Reservation> reservationsOf(String userID) {
        return ledger.computeIfAbsent(userID, id -> new ArrayList<>());
    }

    private static Reservation findReservation(List<Reservation> reservations, String reservationID) {
        return reservations.stream().filter(r -> r.id().equals(reservationID)).findFirst().orElse(null);
    }

    // the campus prefix keeps IDs unique across the servers
    private String nextReservationId() {
        return String.format("%s-R%05d", campus, reservationCounter.incrementAndGet());
    }

    private static String firstError(String... errors) {
        for (String error : errors) {
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    // null if missing or wrong format
    private static LocalDateTime parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim(), Reservation.DATE_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String format(LocalDateTime time) {
        return time.format(Reservation.DATE_FORMAT);
    }

    private static String hours(long minutes) {
        return String.format("%.2fh", minutes / 60.0);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // "OK:data" -> "data"
    // "ERROR:reason" -> "reason"
    private static String body(String reply) {
        return reply.substring(reply.indexOf(':') + 1);
    }

    private static String await(Future<String> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    // ItemStore's OK/ERROR -> SUCCESS/REJECTED for the client
    private static String toClientReply(String reply) {
        String message = body(reply);   // "OK:Created item..." -> "Created item..."
        if (reply.startsWith("OK")) {
            return success(message);    // "SUCCESS: Created item..."
        } else {
            return reject(message);     // "REJECTED: Item does not exist"
        }
    }

    // client replies: SUCCESS = done, REJECTED = broke a rule, FAILED = system problem
    private static String success(String message) {
        return "SUCCESS: " + message;
    }

    private static String reject(String message) {
        return "REJECTED: " + message;
    }

    private static String fail(String message) {
        return "FAILED: " + message;
    }

    // runs the request, turns unexpected exceptions into FAILED, and logs the result
    private String logged(String operation, String id, Supplier<String> action) {
        String result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            result = fail("Unexpected error: " + e);
        }
        logger.log(operation, id, result);
        return result;
    }
}
