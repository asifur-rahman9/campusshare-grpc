package server;

import drms.grpc.PeerGrpc;
import drms.grpc.PeerMessage;
import io.grpc.stub.StreamObserver;

// receiving side of server-to-server calls: hands the message to DRMSImpl.dispatch
final class PeerGrpcService extends PeerGrpc.PeerImplBase {

    private final DRMSImpl impl;
    private final ServerLogger logger;

    PeerGrpcService(DRMSImpl impl, ServerLogger logger) {
        this.impl = impl;
        this.logger = logger;
    }

    @Override
    public void call(PeerMessage request, StreamObserver<PeerMessage> out) {
        String message = request.getMessage();
        String reply = impl.dispatch(message);

        String[] fields = PeerClient.fields(message);
        logger.log("peer:" + fields[0], fields.length > 1 ? fields[1] : "-", reply);

        out.onNext(PeerMessage.newBuilder().setMessage(reply).build());
        out.onCompleted();
    }
}
