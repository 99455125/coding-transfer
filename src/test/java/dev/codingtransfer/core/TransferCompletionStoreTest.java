package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Completion decisions are small, durable, direction-aware and safe for duplicate response replay. */
final class TransferCompletionStoreTest {
    private TransferCompletionStoreTest() {}

    static void run(Path fixture) throws Exception {
        Path workspace = Files.createDirectory(fixture.resolve("completion-store"));
        TransferCompletionStore store = new TransferCompletionStore(workspace);
        String hostToVmId = UUID.randomUUID().toString();
        long now = Instant.now().toEpochMilli();
        TransferCompletionStore.Record received = new TransferCompletionStore.Record(hostToVmId, "repo", "inner-v3",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                TransferCompletionStore.Phase.RECEIVED_DURABLE, new byte[0], now);
        store.advance(received);
        TestSupport.check(store.find(hostToVmId).orElseThrow().phase() == TransferCompletionStore.Phase.RECEIVED_DURABLE,
                "HOST-to-VM durable receive record survives restart");
        fails(() -> store.advance(new TransferCompletionStore.Record(hostToVmId, "repo", "inner-v3",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                TransferCompletionStore.Phase.BUSINESS_APPLIED, new byte[]{1}, now)), "HOST-to-VM cannot acquire a business-apply phase");

        String vmToHostId = UUID.randomUUID().toString();
        byte[] exactBusinessAck = "validated-existing-v2-ack".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        TransferCompletionStore.Record applied = new TransferCompletionStore.Record(vmToHostId, "repo", "snapshot-exact",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                TransferCompletionStore.Phase.BUSINESS_APPLIED, exactBusinessAck, now);
        store.advance(applied);
        TestSupport.check(java.util.Arrays.equals(exactBusinessAck, store.find(vmToHostId).orElseThrow().response()),
                "business ACK payload is retained for exact duplicate response");
        TransferCompletionStore.Record waitingFinal = new TransferCompletionStore.Record(vmToHostId, "repo", "snapshot-exact",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                TransferCompletionStore.Phase.WAIT_FINAL_ACK, exactBusinessAck, now + 1);
        store.advance(waitingFinal);
        fails(() -> store.advance(applied), "completion phase cannot roll back after baseline ACK acceptance");
        fails(() -> store.advance(new TransferCompletionStore.Record(vmToHostId, "repo", "different-pending",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                TransferCompletionStore.Phase.COMPLETE, exactBusinessAck, now + 2)), "completion record binds the exact pending transfer");
        store.advance(new TransferCompletionStore.Record(vmToHostId, "repo", "snapshot-exact",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                TransferCompletionStore.Phase.COMPLETE, exactBusinessAck, now + 2));
        store.advance(new TransferCompletionStore.Record(hostToVmId, "repo", "inner-v3",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                TransferCompletionStore.Phase.COMPLETE, new byte[0], now - Duration.ofDays(31).toMillis()));
        TestSupport.check(store.cleanupExpired(Instant.now()) == 1 && store.find(vmToHostId).isPresent(),
                "cleanup prunes expired completion only and retains recent/unfinished records");
        System.out.println("PASS direction-specific durable completion record checks");
    }

    private static void fails(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
