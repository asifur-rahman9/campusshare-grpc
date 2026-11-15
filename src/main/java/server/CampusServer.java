package server;

import common.Campus;
import io.grpc.Server;
import io.grpc.ServerBuilder;

// starts one campus server: a gRPC server with the client service (DRMS) and the server-to-server service (Peer)
// DRMS_SEED=true adds a few sample items so a fresh demo isn't empty
public class CampusServer {

    public static void main(String[] args) throws Exception {
        Campus campus = args.length > 0 ? Campus.of(args[0]) : null;
        if (campus == null) {
            System.out.println("Usage: drms-server <SGW|LOY|WIL>");
            System.exit(2);
        }
        ServerLogger logger = new ServerLogger(campus);
        DRMSImpl impl = new DRMSImpl(campus, logger);
        if (Boolean.parseBoolean(Campus.env("DRMS_SEED", "false"))) {
            seed(impl, campus);
        }

        // gRPC runs each call on a pool thread
        Server server = ServerBuilder.forPort(campus.port)
                .addService(new DRMSGrpcService(impl))
                .addService(new PeerGrpcService(impl, logger))
                .build()
                .start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        System.out.println(campus + " server ready on gRPC port " + campus.port);
        server.awaitTermination();
    }

    // sample equipment per campus: item number, type, name, units
    private static void seed(DRMSImpl impl, Campus campus) {
        String[][] items;
        switch (campus) {
            case SGW:
                items = new String[][]{{"1001", "Laptop", "MacBook Air 13", "3"},
                        {"1002", "Camera", "Sony A7 III", "1"}, {"1003", "Projector", "Epson EF-12", "2"}};
                break;
            case LOY:
                items = new String[][]{{"1001", "Laptop", "ThinkPad X1 Carbon", "2"},
                        {"1002", "Camera", "Canon EOS R6", "2"}, {"1003", "Tripod", "Manfrotto Befree", "4"}};
                break;
            default:
                items = new String[][]{{"1001", "Laptop", "Dell XPS 13", "1"},
                        {"1002", "VR headset", "Meta Quest 3", "2"}};
        }
        String manager = campus + "M0000";
        for (String[] i : items) {
            impl.addItem(manager, campus + i[0], i[1], i[2], Integer.parseInt(i[3]));
        }
    }
}
