package server;

import common.Campus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

// single-campus tests: everything stays on SGW, so no other server needs to be running
class DRMSImplTest {

    private static final String MANAGER = "SGWM1111";
    private static final String ALICE = "SGWU1111";
    private static final String BOB = "SGWU2222";

    private DRMSImpl sgw;
    private LocalDateTime monday; // next week's Monday 10:00, always in the future

    @BeforeEach
    void setUp() throws Exception {
        sgw = new DRMSImpl(Campus.SGW, new ServerLogger(Campus.SGW));
        monday = LocalDateTime.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        assertSuccess(sgw.addItem(MANAGER, "SGW1001", "Laptop", "MacBook Air", 3));
        assertSuccess(sgw.addItem(MANAGER, "SGW1002", "Camera", "Sony A7", 1));
    }

    private String t(int days, int hours) {
        return monday.plusDays(days).plusHours(hours).format(Reservation.DATE_FORMAT);
    }

    private static void assertSuccess(String reply) {
        assertTrue(reply.startsWith("SUCCESS"), reply);
    }

    private static void assertRejected(String reply, String reason) {
        assertTrue(reply.startsWith("REJECTED") && reply.contains(reason), reply);
    }

    private static String reservationId(String reply) {
        Matcher m = Pattern.compile("(SGW-R\\d+)").matcher(reply);
        assertTrue(m.find(), "no reservation ID in: " + reply);
        return m.group(1);
    }

    @Test
    void rolesAndCampusesAreChecked() {
        assertRejected(sgw.addItem(ALICE, "SGW1003", "Tripod", "Manfrotto", 1), "cannot perform manager operations");
        assertRejected(sgw.reserveItem(MANAGER, "SGW1001", t(0, 0), t(0, 2)), "cannot perform user operations");
        assertRejected(sgw.addItem(MANAGER, "LOY1001", "Tripod", "Manfrotto", 1), "");
        assertRejected(sgw.reserveItem("LOYU1111", "SGW1001", t(0, 0), t(0, 2)), "home campus");
    }

    @Test
    void reserveAndCancel() {
        String reply = sgw.reserveItem(ALICE, "SGW1002", t(0, 0), t(0, 2));
        assertSuccess(reply);
        assertSuccess(sgw.cancelReservation(ALICE, reservationId(reply)));
    }

    @Test
    void fullItemReportsNoCapacity() {
        assertSuccess(sgw.reserveItem(ALICE, "SGW1002", t(0, 0), t(0, 2)));
        assertRejected(sgw.reserveItem(BOB, "SGW1002", t(0, 1), t(0, 3)), "NO_CAPACITY");
    }

    @Test
    void weeklyLimitIsTwentyHours() {
        assertSuccess(sgw.reserveItem(ALICE, "SGW1001", t(0, 0), t(0, 8)));
        assertSuccess(sgw.reserveItem(ALICE, "SGW1001", t(1, 0), t(1, 8)));
        assertRejected(sgw.reserveItem(ALICE, "SGW1001", t(2, 0), t(2, 8)), "Weekly 20h budget exceeded");
    }

    @Test
    void failedUpdateLeavesReservationUnchanged() {
        assertSuccess(sgw.reserveItem(ALICE, "SGW1002", t(0, 0), t(0, 2)));
        String bobs = reservationId(sgw.reserveItem(BOB, "SGW1002", t(0, 4), t(0, 6)));

        assertRejected(sgw.updateReservation(BOB, bobs, t(0, 1), t(0, 3)), "reservation unchanged");
        assertSuccess(sgw.cancelReservation(BOB, bobs)); // still exists with the old times
    }

    @Test
    void cancelHandsUnitToNextInQueue() throws InterruptedException {
        String alices = reservationId(sgw.reserveItem(ALICE, "SGW1002", t(0, 0), t(0, 2)));
        String queued = sgw.joinWaitingQueue(BOB, "SGW1002", t(0, 0), t(0, 2));
        assertSuccess(queued);
        String bobs = reservationId(queued);

        assertSuccess(sgw.cancelReservation(ALICE, alices));

        // the queue is processed on a background thread, give it a moment
        String reply = "";
        for (int i = 0; i < 30 && !reply.startsWith("SUCCESS"); i++) {
            Thread.sleep(100);
            reply = sgw.cancelReservation(BOB, bobs);
        }
        assertSuccess(reply);
    }
}
