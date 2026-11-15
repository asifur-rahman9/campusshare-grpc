package server;

import common.Campus;
import drms.grpc.PeerGrpc;
import drms.grpc.PeerMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// sending side of server-to-server calls (request-reply over gRPC)
// messages keep the OP|field|field format that DRMSImpl.dispatch reads
final class PeerClient {

    static final int TIMEOUT_MS = 3000;

    // one channel per campus, reused for every call
    private static final Map<Campus, ManagedChannel> CHANNELS = new ConcurrentHashMap<>();

    private PeerClient() {
    }

    // sends the message to that campus and waits for the reply, null if it doesn't answer in time
    static String send(Campus target, String message) {
        ManagedChannel channel = CHANNELS.computeIfAbsent(target,
                c -> ManagedChannelBuilder.forTarget(c.target()).usePlaintext().build());
        try {
            return PeerGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .call(PeerMessage.newBuilder().setMessage(message).build())
                    .getMessage();
        } catch (StatusRuntimeException e) {
            return null;
        }
    }

    static String message(String... fields) {
        return String.join("|", fields);
    }

    static String[] fields(String message) {
        return message.split("\\|", -1);
    }
}
