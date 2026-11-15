package web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import common.Campus;
import drms.grpc.AddItemRequest;
import drms.grpc.BookingRequest;
import drms.grpc.DRMSGrpc;
import drms.grpc.FindRequest;
import drms.grpc.IdRequest;
import drms.grpc.ItemRequest;
import drms.grpc.ReservationRequest;
import drms.grpc.StatusReply;
import drms.grpc.StatusRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// web frontend: serves index.html and turns each /api call into a gRPC call
// to the home campus of the ID (SGWU1111 -> SGW server). Browsers can't speak gRPC directly.
//
// config (env): WEB_HOST (default localhost, use 0.0.0.0 to expose), WEB_PORT (default 8080),
// plus the DRMS_<CAMPUS>_HOST / _PORT variables read by Campus
public class WebServer {

    private static final long CALL_TIMEOUT_MS = 10_000;
    private static final long STATUS_TIMEOUT_MS = 1_000;
    private static final int MAX_BODY_BYTES = 16 * 1024;
    private static final Set<String> OPERATIONS = Set.of(
            "addItem", "removeItem", "listItems", "reserve", "joinQueue", "update", "cancel", "find");

    private static final Map<Campus, ManagedChannel> CHANNELS = new EnumMap<>(Campus.class);
    private static byte[] page;

    public static void main(String[] args) throws IOException {
        String host = Campus.env("WEB_HOST", "localhost");
        int port = Integer.parseInt(Campus.env("WEB_PORT", "8080"));

        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                throw new IllegalStateException("web/index.html missing from the classpath");
            }
            page = in.readAllBytes();
        }
        for (Campus c : Campus.values()) {
            CHANNELS.put(c, ManagedChannelBuilder.forTarget(c.target()).usePlaintext().build());
        }

        ExecutorService pool = Executors.newFixedThreadPool(32);
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.setExecutor(pool);
        http.createContext("/", ex -> handle(ex, WebServer::page));
        http.createContext("/api/status", ex -> handle(ex, WebServer::status));
        http.createContext("/api/", ex -> handle(ex, WebServer::operation));
        http.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(1);
            pool.shutdown();
            CHANNELS.values().forEach(ManagedChannel::shutdown);
        }));
        System.out.println("Web frontend at http://" + host + ":" + port);
    }

    private interface Handler {
        void run(HttpExchange ex) throws IOException;
    }

    // common headers + turns unexpected errors into a 500 instead of a dropped connection
    private static void handle(HttpExchange ex, Handler handler) throws IOException {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        try {
            handler.run(ex);
        } catch (RuntimeException e) {
            System.err.println("Request " + ex.getRequestURI() + " failed: " + e);
            send(ex, 500, "text/plain; charset=utf-8", "FAILED: Internal error in the web server");
        } finally {
            ex.close();
        }
    }

    private static void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, "text/plain", "Use GET");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, page.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(page);
        }
    }

    // GET /api/status -> [{"campus":"SGW","up":true,"items":2,...}, ...], all campuses asked in parallel
    private static void status(HttpExchange ex) throws IOException {
        List<CompletableFuture<String>> parts = new ArrayList<>();
        for (Campus c : Campus.values()) {
            parts.add(CompletableFuture.supplyAsync(() -> campusStatus(c)));
        }
        StringBuilder json = new StringBuilder("[");
        for (CompletableFuture<String> part : parts) {
            json.append(json.length() > 1 ? "," : "").append(part.join());
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json", json.append(']').toString());
    }

    private static String campusStatus(Campus c) {
        String head = "{\"campus\":\"" + c + "\"";
        try {
            StatusReply s = DRMSGrpc.newBlockingStub(CHANNELS.get(c))
                    .withDeadlineAfter(STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .status(StatusRequest.getDefaultInstance());
            return head + ",\"up\":true,\"items\":" + s.getItems() + ",\"reservations\":" + s.getReservations()
                    + ",\"waiting\":" + s.getWaiting() + "}";
        } catch (StatusRuntimeException e) {
            return head + ",\"up\":false}";
        }
    }

    // POST /api/<op> with form fields -> plain-text result from the campus server
    private static void operation(HttpExchange ex) throws IOException {
        String op = ex.getRequestURI().getPath().substring("/api/".length());
        if (!OPERATIONS.contains(op)) {
            send(ex, 404, "text/plain", "Unknown operation");
            return;
        }
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, "text/plain", "Use POST");
            return;
        }
        byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            send(ex, 413, "text/plain", "Request too large");
            return;
        }
        Map<String, String> f = form(new String(body, StandardCharsets.UTF_8));
        String id = f.getOrDefault("id", "").trim().toUpperCase();
        Campus home = Campus.of(id);
        if (home == null) {
            send(ex, 200, "text/plain", "REJECTED: The ID must start with SGW, LOY or WIL");
            return;
        }

        DRMSGrpc.DRMSBlockingStub stub = DRMSGrpc.newBlockingStub(CHANNELS.get(home))
                .withDeadlineAfter(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        String result;
        try {
            result = call(stub, op, id, f);
        } catch (NumberFormatException e) {
            result = "REJECTED: Quantity must be a whole number";
        } catch (StatusRuntimeException e) {
            result = "FAILED: The " + home + " server is not reachable right now (" + e.getStatus().getCode() + ")";
        }
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "text/plain; charset=utf-8", result);
    }

    private static String call(DRMSGrpc.DRMSBlockingStub stub, String op, String id, Map<String, String> f) {
        String item = f.getOrDefault("itemId", "").trim().toUpperCase();
        // the page sends datetime-local values (2026-11-20T10:00), the server expects "2026-11-20 10:00"
        String start = f.getOrDefault("start", "").replace('T', ' ');
        String end = f.getOrDefault("end", "").replace('T', ' ');
        String reservation = f.getOrDefault("reservationId", "").trim();
        switch (op) {
            case "addItem":
                return stub.addItem(AddItemRequest.newBuilder().setManagerId(id).setItemId(item)
                        .setItemType(f.getOrDefault("type", "")).setItemName(f.getOrDefault("name", ""))
                        .setQuantity(Integer.parseInt(f.getOrDefault("quantity", "").trim())).build()).getResult();
            case "removeItem":
                return stub.removeItem(ItemRequest.newBuilder().setManagerId(id).setItemId(item).build()).getResult();
            case "listItems":
                return stub.listAvailableItems(IdRequest.newBuilder().setId(id).build()).getResult();
            case "reserve":
                return stub.reserveItem(booking(id, item, start, end)).getResult();
            case "joinQueue":
                return stub.joinWaitingQueue(booking(id, item, start, end)).getResult();
            case "update":
                return stub.updateReservation(ReservationRequest.newBuilder().setUserId(id)
                        .setReservationId(reservation).setStart(start).setEnd(end).build()).getResult();
            case "cancel":
                return stub.cancelReservation(ReservationRequest.newBuilder().setUserId(id)
                        .setReservationId(reservation).build()).getResult();
            default: // find
                return stub.findItem(FindRequest.newBuilder().setUserId(id)
                        .setItemType(f.getOrDefault("type", "")).setStart(start).setEnd(end).build()).getResult();
        }
    }

    private static BookingRequest booking(String id, String item, String start, String end) {
        return BookingRequest.newBuilder().setUserId(id).setItemId(item).setStart(start).setEnd(end).build();
    }

    // url-encoded form body -> map
    private static Map<String, String> form(String body) {
        Map<String, String> map = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
