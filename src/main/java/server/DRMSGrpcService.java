package server;

import drms.grpc.AddItemRequest;
import drms.grpc.BookingRequest;
import drms.grpc.DRMSGrpc;
import drms.grpc.FindRequest;
import drms.grpc.IdRequest;
import drms.grpc.ItemRequest;
import drms.grpc.Reply;
import drms.grpc.ReservationRequest;
import drms.grpc.StatusReply;
import drms.grpc.StatusRequest;
import io.grpc.stub.StreamObserver;

// client-facing gRPC service, forwards every call to DRMSImpl
final class DRMSGrpcService extends DRMSGrpc.DRMSImplBase {

    private final DRMSImpl impl;

    DRMSGrpcService(DRMSImpl impl) {
        this.impl = impl;
    }

    @Override
    public void addItem(AddItemRequest r, StreamObserver<Reply> out) {
        reply(out, impl.addItem(r.getManagerId(), r.getItemId(), r.getItemType(), r.getItemName(), r.getQuantity()));
    }

    @Override
    public void removeItem(ItemRequest r, StreamObserver<Reply> out) {
        reply(out, impl.removeItem(r.getManagerId(), r.getItemId()));
    }

    @Override
    public void listAvailableItems(IdRequest r, StreamObserver<Reply> out) {
        reply(out, impl.listAvailableItems(r.getId()));
    }

    @Override
    public void reserveItem(BookingRequest r, StreamObserver<Reply> out) {
        reply(out, impl.reserveItem(r.getUserId(), r.getItemId(), r.getStart(), r.getEnd()));
    }

    @Override
    public void joinWaitingQueue(BookingRequest r, StreamObserver<Reply> out) {
        reply(out, impl.joinWaitingQueue(r.getUserId(), r.getItemId(), r.getStart(), r.getEnd()));
    }

    @Override
    public void updateReservation(ReservationRequest r, StreamObserver<Reply> out) {
        reply(out, impl.updateReservation(r.getUserId(), r.getReservationId(), r.getStart(), r.getEnd()));
    }

    @Override
    public void cancelReservation(ReservationRequest r, StreamObserver<Reply> out) {
        reply(out, impl.cancelReservation(r.getUserId(), r.getReservationId()));
    }

    @Override
    public void findItem(FindRequest r, StreamObserver<Reply> out) {
        reply(out, impl.findItem(r.getUserId(), r.getItemType(), r.getStart(), r.getEnd()));
    }

    @Override
    public void status(StatusRequest r, StreamObserver<StatusReply> out) {
        int[] c = impl.status();
        out.onNext(StatusReply.newBuilder().setCampus(impl.campus().name())
                .setItems(c[0]).setReservations(c[1]).setWaiting(c[2]).build());
        out.onCompleted();
    }

    private static void reply(StreamObserver<Reply> out, String result) {
        out.onNext(Reply.newBuilder().setResult(result).build());
        out.onCompleted();
    }
}
