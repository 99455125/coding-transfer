package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Tests only minimal state and the current unacknowledged ciphertext cache. */
final class TransferSessionStoreTest {
    private TransferSessionStoreTest() {}

    static void run(Path fixture) throws Exception {
        TransferSessionStore store = new TransferSessionStore(fixture);
        String session = UUID.randomUUID().toString();
        byte[] state = "READY_WAIT_BLOCK_ACK:2/3".getBytes(StandardCharsets.UTF_8);
        String frame = "pair " + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        store.saveState(session, state);
        store.saveContext(new TransferSessionStore.Context(session,"project","transfer",ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.HOST_TO_VM,3));
        store.saveFrame(session, frame);
        store.saveFrame(session, frame);
        TestSupport.check(frame.equals(store.readFrame(session)), "current ciphertext can be reused byte-for-byte");
        TestSupport.check(Arrays.equals(state, store.readState(session)), "small transfer state survives persistence");
        fails(() -> store.saveFrame(session, "pair AQIDBA=="), "unacknowledged ciphertext cannot be replaced");
        TransferSessionStore reloaded = new TransferSessionStore(fixture);
        TestSupport.check(reloaded.currentContext().session().equals(session)&&reloaded.currentContext().total()==3,"single transfer context survives store reconstruction");
        fails(()->reloaded.saveContext(new TransferSessionStore.Context(UUID.randomUUID().toString(),"other","different",ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,1)),"a second session cannot silently replace the active context");
        String inner=UUID.randomUUID().toString();PendingWorktreeStore pending=new PendingWorktreeStore(fixture);pending.save(new PendingWorktreeStore.Pending("repo",session,inner));
        TestSupport.check(pending.read().innerSession().equals(inner),"pending WORKTREE pointer survives restart");pending.clear(UUID.randomUUID().toString());TestSupport.check(pending.read()!=null,"pending pointer cannot be cleared by a different session");pending.clear(session);TestSupport.check(pending.read()==null,"matching apply or explicit cleanup clears pending pointer");
        TestSupport.check(frame.equals(reloaded.readFrame(session)), "current retry frame survives store reconstruction");
        reloaded.clearFrame(session);
        TestSupport.check(Arrays.equals(state, reloaded.readState(session)), "acknowledged frame cleanup retains session state");
        Path sessionDir = fixture.resolve(".vmsync/transfer-sessions").resolve(session);
        Files.writeString(sessionDir.resolve("unexpected"), "preserve");
        fails(() -> reloaded.clearSession(session), "cleanup refuses unknown session files");
        Files.delete(sessionDir.resolve("unexpected"));
        reloaded.clearSession(session);
        TestSupport.check(reloaded.currentContext()==null,"completed session cleanup clears only its matching context");
        TestSupport.check(!Files.exists(sessionDir), "completed session cleanup removes only registered transport state");
        String restartSession=UUID.randomUUID().toString();reloaded.saveContext(new TransferSessionStore.Context(restartSession,"repo","pending",ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,2));
        TransferCompletionStore completions=new TransferCompletionStore(fixture);completions.advance(new TransferCompletionStore.Record(restartSession,"repo","pending",ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,TransferCompletionStore.Phase.WAIT_FINAL_ACK,new byte[]{7},System.currentTimeMillis()));
        TestSupport.check(reloaded.clearContext(restartSession)&&reloaded.currentContext()==null&&completions.find(restartSession).orElseThrow().phase()==TransferCompletionStore.Phase.WAIT_FINAL_ACK,"explicit end detaches only active context and retains business recovery record");
        String nextSession=UUID.randomUUID().toString();ReliableTransferEngine nextSender=ReliableTransferEngine.sender("pair",new byte[32],reloaded);String invite=nextSender.prepare(new ReliableTransferEngine.Message(nextSession,"repo","next",ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,ReliableTransferEngine.Type.INVITE,0,1,new byte[0]));TestSupport.check(ReliableTransferEngine.decode("pair",new byte[32],invite).type()==ReliableTransferEngine.Type.INVITE&&reloaded.currentContext().session().equals(nextSession),"a new invite can start after explicit context detachment");TestSupport.check(completions.find(restartSession).isPresent(),"detaching the old session preserves its business recovery record");
        System.out.println("PASS minimal transfer state and current ciphertext retry cache checks");
    }

    private static void fails(Throwing action, String label) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
