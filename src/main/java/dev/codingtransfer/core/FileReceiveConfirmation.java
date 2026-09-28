package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static dev.codingtransfer.core.Model.require;

/** Confirms durable, verified file reception independently of the receiver's later save path. */
public final class FileReceiveConfirmation {
    private FileReceiveConfirmation() {}

    public static String confirmVerified(Path workspace, String session, String transferId,
                                         FileTransfer.Received received, String identity, byte[] key) throws IOException {
        require(received != null && received.complete() && received.metadata().session().equals(transferId)
                && Files.isRegularFile(received.verifiedFile())
                && Files.size(received.verifiedFile()) == received.metadata().size(), "文件尚未完整校验并保存到接收缓存");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        Optional<TransferCompletionStore.Record> previous = records.find(session);
        if (previous.isPresent()) {
            check(previous.get(), transferId);
            if (previous.get().phase() == TransferCompletionStore.Phase.BUSINESS_APPLIED)
                return new String(previous.get().response(), StandardCharsets.UTF_8);
            if (previous.get().phase() == TransferCompletionStore.Phase.COMPLETE)
                return receipt(session, transferId, received.metadata(), identity, key);
        }
        ReliableTransferEngine.Message message = receiptMessage(session, transferId, received.metadata());
        String frame = ReliableTransferEngine.receiver(identity, key, new TransferSessionStore(workspace)).prepareCompletion(message);
        records.advance(new TransferCompletionStore.Record(session, "file", transferId,
                ReliableTransferEngine.Kind.FILE, ReliableTransferEngine.Direction.FILE,
                TransferCompletionStore.Phase.BUSINESS_APPLIED, frame.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        return frame;
    }

    /** A duplicate final DATA block receives the same durable confirmation, even after completion. */
    public static String replayData(Path workspace, String session, String transferId,
                                    String identity, byte[] key) throws IOException {
        Optional<TransferCompletionStore.Record> found = new TransferCompletionStore(workspace).find(session);
        if (found.isEmpty()) return null;
        TransferCompletionStore.Record record = found.get();
        check(record, transferId);
        if (record.phase() == TransferCompletionStore.Phase.BUSINESS_APPLIED)
            return new String(record.response(), StandardCharsets.UTF_8);
        if (record.phase() == TransferCompletionStore.Phase.COMPLETE)
            return receipt(session, transferId, FileTransfer.resume(workspace, transferId).metadata(), identity, key);
        return null;
    }

    public static String acceptFinal(Path workspace, String frame, String identity, byte[] key) throws IOException {
        ReliableTransferEngine.Message fin = ReliableTransferEngine.decode(identity, key, frame);
        require(fin.type() == ReliableTransferEngine.Type.FINAL && fin.scopeId().equals("file")
                && fin.kind() == ReliableTransferEngine.Kind.FILE
                && fin.direction() == ReliableTransferEngine.Direction.FILE, "不是文件传输的最终确认");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        TransferCompletionStore.Record record = records.find(fin.session()).orElseThrow(() -> new IOException("缺少已校验文件的接收确认"));
        check(record, fin.transferId());
        if (record.phase() == TransferCompletionStore.Phase.COMPLETE)
            return new String(record.response(), StandardCharsets.UTF_8);
        require(record.phase() == TransferCompletionStore.Phase.BUSINESS_APPLIED
                && ReliableTransferEngine.decode(identity, key, new String(record.response(), StandardCharsets.UTF_8)).total() == fin.total(),
                "最终确认与已接收文件不匹配");
        ReliableTransferEngine.Message ack = new ReliableTransferEngine.Message(fin.session(), "file", fin.transferId(),
                fin.kind(), fin.direction(), ReliableTransferEngine.Type.FINAL_ACK, 0, fin.total(), new byte[0]);
        String response = ReliableTransferEngine.receiver(identity, key, new TransferSessionStore(workspace)).prepareCompletion(ack);
        records.advance(new TransferCompletionStore.Record(fin.session(), "file", fin.transferId(), fin.kind(), fin.direction(),
                TransferCompletionStore.Phase.COMPLETE, response.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        new TransferSessionStore(workspace).clearSession(fin.session());
        return response;
    }

    private static String receipt(String session, String transferId, FileTransfer.Metadata metadata,
                                  String identity, byte[] key) throws IOException {
        return ReliableTransferEngine.encode(identity, key, receiptMessage(session, transferId, metadata));
    }

    private static ReliableTransferEngine.Message receiptMessage(String session, String transferId, FileTransfer.Metadata metadata) {
        byte[] payload = (metadata.filename() + "\n" + metadata.size() + "\n" + metadata.sha256()).getBytes(StandardCharsets.UTF_8);
        return new ReliableTransferEngine.Message(session, "file", transferId, ReliableTransferEngine.Kind.FILE,
                ReliableTransferEngine.Direction.FILE, ReliableTransferEngine.Type.BUSINESS_ACK, 0, metadata.count(), payload);
    }

    private static void check(TransferCompletionStore.Record record, String transferId) throws IOException {
        require(record.scopeId().equals("file") && record.transferId().equals(transferId)
                && record.kind() == ReliableTransferEngine.Kind.FILE
                && record.direction() == ReliableTransferEngine.Direction.FILE, "文件接收确认与会话不匹配");
    }
}
