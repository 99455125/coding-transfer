package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static dev.codingtransfer.core.Model.*;
import static dev.codingtransfer.core.TestSupport.git;

/** VM→HOST apply, exact pending ACK, and final-ACK-loss recovery on disposable Git fixtures. */
final class TransferCompletionIntegrationTest {
    private TransferCompletionIntegrationTest() {}

    static void run(Path fixture) throws Exception {
        Path vm = Files.createDirectory(fixture.resolve("completion-vm"));
        Path hostWorkspace = Files.createDirectory(fixture.resolve("completion-host"));
        Path host = Files.createDirectory(hostWorkspace.resolve("project"));
        TestSupport.initRepository(vm);
        byte[] binary = new byte[24_000]; new java.util.Random(31).nextBytes(binary);
        Files.write(vm.resolve("payload.bin"), binary);
        git(vm, "add", "payload.bin"); git(vm, "commit", "-qm", "initial");
        Config vmConfig = new Config(Role.VM, List.of(new Config.Mapping("demo", vm.toString(), vm.toString())), List.of(), 4, "git");
        Config hostConfig = new Config(Role.HOST, List.of(new Config.Mapping("demo", host.toString(), host.toString())), List.of(), 4, "git");
        ProjectTransferService vmService = new ProjectTransferService(vm, vmConfig);
        ProjectTransferService hostService = new ProjectTransferService(hostWorkspace, hostConfig);
        TransferCompletionStore records=new TransferCompletionStore(hostWorkspace);
        Payload packet = vmService.generate("demo", false).generated().payload();
        String innerSession = packet.git().snapshot(), outerSession = UUID.randomUUID().toString();
        List<String> chunks = ClipboardTransport.split(packet, 4);
        ProjectOutgoingStore outgoingStore=new ProjectOutgoingStore(vm);outgoingStore.save(outerSession,packet,chunks);TestSupport.check(chunks.size()>2,"VM-to-HOST fixture spans more than two chunks");
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 8);
        ReliableTransferEngine vmSender = ReliableTransferEngine.sender("pair",key,new TransferSessionStore(vm));
        ReliableTransferEngine hostReceiver = ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(hostWorkspace));
        ReliableTransferEngine.Message invite = new ReliableTransferEngine.Message(outerSession,"demo",innerSession,
                ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,
                ReliableTransferEngine.Type.INVITE,0,chunks.size(),new byte[0]);
        vmSender.acceptReady(hostReceiver.acceptInviteAndReady(vmSender.prepare(invite)),outerSession);
        ClipboardTransport.Received[] received = new ClipboardTransport.Received[1];
        List<String> sendChunks=chunks;
        for (int i=0;i<chunks.size();i++) {
            int index=i+1;
            String frame=vmSender.prepare(new ReliableTransferEngine.Message(outerSession,"demo",innerSession,
                    ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,
                    ReliableTransferEngine.Type.DATA,index,chunks.size(),sendChunks.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String blockAck=hostReceiver.acceptData(frame,data->received[0]=hostService.receiveChunk("demo",
                    new String(data.payload(),java.nio.charset.StandardCharsets.UTF_8)));
            if(index<chunks.size())vmSender.acceptBlockAck(blockAck);
            else TestSupport.check(ReliableTransferEngine.decode("pair",key,blockAck).index()==chunks.size(),
                    "last real inner DATA is durably ACK-pending before business completion replaces it");
            if(index==1){vmSender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(vm));sendChunks=outgoingStore.loadParts(outerSession,null,chunks.size());TestSupport.check(sendChunks.equals(chunks),"restarted VM sender replays remaining original chunks byte-for-byte");}
        }
        TestSupport.check(received[0] != null && received[0].payload() != null, "real encrypted INVITE/DATA/ACK path persists a complete V2 Bundle");
        Path acknowledged = vm.resolve(".vmsync/bundle-state/acknowledged/demo.gz");
        TestSupport.check(!Files.exists(acknowledged), "block and completion receipt preparation do not advance VM baseline");

        // A full snapshot must replace an older baseline even after exclusion settings change.
        Path oldBaseline=hostWorkspace.resolve(".vmsync/baselines/demo.gz");
        Files.createDirectories(oldBaseline.getParent());
        Payload stale=new Payload(Type.SNAPSHOT,packet.project(),packet.head(),Model.hash("old-policy".getBytes()),
                packet.entries(),packet.git().withoutBundle());
        Files.write(oldBaseline,Codec.encode(stale));
        String businessAck = hostService.receiveAndApplyAckFrame(outerSession, chunks.size(), "demo", received[0].payload(), "pair", key);
        ReliableTransferEngine.Message businessMessage=ReliableTransferEngine.decode("pair",key,businessAck);
        TestSupport.check(businessMessage.total()==chunks.size(),"business ACK retains actual multi-block transfer total");
        TestSupport.check(Files.exists(acknowledged) == false, "HOST application alone does not advance VM baseline");
        String finalFrame = vmService.acceptBusinessAckAndPrepareFinal(businessAck, "pair", key);
        TestSupport.check(ReliableTransferEngine.decode("pair",key,finalFrame).type()==ReliableTransferEngine.Type.FINAL
                        &&ReliableTransferEngine.decode("pair",key,finalFrame).total()==chunks.size()
                        &&new TransferSessionStore(vm).hasFrame(outerSession),
                "VM consumes last DATA before durably preparing FINAL with the real total");
        TestSupport.check(Files.exists(acknowledged), "exact business ACK durably advances VM pending baseline");
        String finalAck = hostService.acceptFinalAndPrepareAck(finalFrame, "pair", key);
        String replayedFinalAck = hostService.acceptFinalAndPrepareAck(finalFrame, "pair", key);
        TestSupport.check(finalAck.equals(replayedFinalAck), "lost FINAL_ACK is replayed byte-for-byte without reapplying");
        TestSupport.check(records.findLatest(ReliableTransferEngine.Kind.PROJECT,"demo",ReliableTransferEngine.Direction.VM_TO_HOST,TransferCompletionStore.Phase.COMPLETE).orElseThrow().session().equals(outerSession),"receiver-side completion recovery selects local VM-to-HOST direction");
        vmService.acceptFinalAck(finalAck, "pair", key);
        outgoingStore.clear(outerSession);
        TestSupport.check(vmService.acceptBusinessAckAndPrepareFinal(businessAck, "pair", key).equals(finalFrame),
                "duplicate business ACK after completion returns the durable FINAL frame");
        TestSupport.check(Files.readAllBytes(host.resolve("payload.bin")).length == binary.length, "business apply preserved exact binary bytes");

        Payload wrong = new Payload(Type.ACK, "demo", packet.head(), packet.policy(), List.of(),
                new GitData(UUID.randomUUID().toString(), "-", "", new java.util.TreeMap<>(), new byte[0]));
        ReliableTransferEngine.Message wrongAck = new ReliableTransferEngine.Message(UUID.randomUUID().toString(), "demo", innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                ReliableTransferEngine.Type.BUSINESS_ACK, 0, 1, Codec.encode(wrong));
        String wrongFrame = ReliableTransferEngine.encode("pair", key, wrongAck);
        fails(() -> vmService.acceptBusinessAckAndPrepareFinal(wrongFrame, "pair", key), "nonmatching pending ACK rejected");
        System.out.println("PASS VM-to-HOST apply, exact pending baseline ACK and final handshake recovery checks");
    }

    private static void fails(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
