package common;

// the three campuses and where their gRPC servers run
// every manager, user and item ID starts with its campus code
// host and port can be overridden per campus, e.g. DRMS_LOY_HOST=10.0.0.12 DRMS_LOY_PORT=50052
public enum Campus {
    SGW(50051),
    LOY(50052),
    WIL(50053);

    public final String host;
    public final int port;

    Campus(int defaultPort) {
        this.host = env("DRMS_" + name() + "_HOST", "localhost");
        this.port = Integer.parseInt(env("DRMS_" + name() + "_PORT", String.valueOf(defaultPort)));
    }

    // gRPC target of the campus server, e.g. localhost:50051
    public String target() {
        return host + ":" + port;
    }

    // campus from the first 3 letters of an ID, null if it's not a valid campus
    public static Campus of(String id) {
        if (id == null || id.length() < 3) {
            return null;
        }
        try {
            return valueOf(id.substring(0, 3).toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
