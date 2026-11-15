package server;

import common.Campus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// one log file per campus: logs/SGW_server.log etc.
// each line: timestamp | server | operation | ID | SUCCESS/REJECTION/FAILURE | reason
final class ServerLogger {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final Campus campus;
    private final Path file;

    ServerLogger(Campus campus) throws IOException {
        this.campus = campus;
        Path dir = Paths.get("logs");
        Files.createDirectories(dir);
        this.file = dir.resolve(campus.name() + "_server.log");
    }

    // result is the reply that was sent:
    // for client - SUCCESS/REJECTED/FAILED
    // for server - server: OK/FULL/ERROR
    synchronized void log(String operation, String id, String result) {

        int colon = result.indexOf(':');
        String status = colon < 0 ? result : result.substring(0, colon);
        String outcome;
        switch (status) {
            case "SUCCESS":
            case "OK":
                outcome = "SUCCESS";
                break;
            case "FAILED":
                outcome = "FAILURE";
                break;
            default:
                outcome = "REJECTION";
        }

        String details = colon < 0 ? "" : result.substring(colon + 1).trim().replace("\n", "; ");
        String line = String.join(" | ", LocalDateTime.now().format(TIME), campus.name(), operation,
                id == null ? "-" : id, outcome, details);

        System.out.println(line); // prints in console
        try {
            Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Could not write log file: " + e.getMessage());
        }
    }
}
