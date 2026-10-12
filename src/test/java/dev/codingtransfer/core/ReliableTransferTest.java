package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end pure-core tests for encrypted stop-and-wait block delivery. */
final class ReliableTransferTest {
    private ReliableTransferTest() {}

    private static void receiverDrivenRequests(Path fixture)throws Exception {
        // A pull request is a compact authenticated control, never a DATA payload.
        ReliableTransferEngine.Type request;
        try { request=ReliableTransferEngine.Type.valueOf("REQUEST"); }
        catch(IllegalArgumentException missing){throw new AssertionError("receiver-driven REQUEST is not implemented",missing);}
        byte[] key=new byte[32];String session=UUID.randomUUID().toString();
        var message=new ReliableTransferEngine.Message(session,"repo","pull",ReliableTransferEngine.Kind.PROJECT,
                ReliableTransferEngine.Direction.HOST_TO_VM,request,1,3,new byte[0]);
        String wire=ReliableTransferEngine.encode("pair",key,message);
        TestSupport.check(wire.length()<512&&ReliableTransferEngine.decode("pair",key,wire).equals(message),"request is small and preserves requested index");
        for(var direction:ReliableTransferEngine.Direction.values()) {
            var kind=direction==ReliableTransferEngine.Direction.FILE?ReliableTransferEngine.Kind.FILE:ReliableTransferEngine.Kind.PROJECT;
            Path sendPath=java.nio.file.Files.createDirectory(fixture.resolve("pull-send-"+direction)),receivePath=java.nio.file.Files.createDirectory(fixture.resolve("pull-receive-"+direction));
            var sendStore=new TransferSessionStore(sendPath);var receiveStore=new TransferSessionStore(receivePath);
            var sender=ReliableTransferEngine.sender("pair",key,sendStore);var receiver=ReliableTransferEngine.receiver("pair",key,receiveStore);
            String invitation=sender.prepare(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.INVITE,0,3,new byte[0]));
            String request1=receiver.acceptPullInviteResult(invitation).readyFrame();
            TestSupport.check(ReliableTransferEngine.decode("pair",key,request1).type()==request&&sender.acceptRequest(request1)==1,"receiver requests first block");
            var future=new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,request,3,3,new byte[0]);
            final var initialSender=sender;
            fails(()->initialSender.acceptRequest(ReliableTransferEngine.encode("pair",key,future)),"request cannot skip unprepared blocks");
            String data1=sender.prepare(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.DATA,1,3,new byte[]{1}));
            String outOfTurn=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.DATA,2,3,new byte[]{2}));
            final var invitedReceiver=receiver;AtomicInteger invalidWrites=new AtomicInteger();
            fails(()->invitedReceiver.acceptRequestedData(outOfTurn,d->invalidWrites.incrementAndGet()),"unrequested future DATA is rejected");
            TestSupport.check(invalidWrites.get()==0,"future DATA cannot reach the inbox");
            TestSupport.check(sender.acceptRequest(request1)==1&&sender.retry(session).equals(data1),"duplicate request retains exact DATA ciphertext");
            final var firstReceiver=receiver;
            fails(()->firstReceiver.acceptRequestedData(data1,d->{throw new IOException("disk full");}),"failed persistence cannot acknowledge or request next");
            TestSupport.check(receiver.retry(session).equals(request1)&&receiver.nextIndex(session)==1,"failed persistence keeps current request");
            String request2=receiver.acceptRequestedData(data1,d->{});
            receiver=ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(receivePath));
            TestSupport.check(receiver.acceptRequestedData(data1,d->{}).equals(request2),"duplicate DATA after restart replays current request");
            receiveStore.clearFrame(session);
            String rebuilt=receiver.resumePullReceiver();
            TestSupport.check(ReliableTransferEngine.decode("pair",key,rebuilt).index()==2,"receiver reconstructs request after state/frame crash gap");
            TestSupport.check(sender.acceptRequest(request2)==2,"request two confirms prepared first block");
            String data2=sender.prepare(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.DATA,2,3,new byte[]{2}));
            final var currentSender=sender;
            fails(()->currentSender.acceptRequest(request1),"stale request cannot rewind sender");
            String request3=receiver.acceptRequestedData(data2,d->{});
            TestSupport.check(receiver.acceptRequestedData(data1,d->{}).equals(request3),"delayed old DATA cannot rewind receiver request");
            sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(sendPath));
            TestSupport.check(sender.acceptRequest(request3)==3,"restarted sender advances only on next valid request");
            String data3=sender.prepare(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.DATA,3,3,new byte[]{3}));
            String finalAck=receiver.acceptRequestedData(data3,d->{});
            TestSupport.check(ReliableTransferEngine.decode("pair",key,finalAck).type()==ReliableTransferEngine.Type.BLOCK_ACK&&receiver.nextIndex(session)==4,"final block persists before completion");
            String retryFinal=receiver.resumePullReceiver();
            TestSupport.check(ReliableTransferEngine.decode("pair",key,retryFinal).type()==request&&sender.acceptRequest(retryFinal)==3,"receiver requests final DATA again when completion/apply was interrupted");
            sender.acceptBlockAck(finalAck);
            TestSupport.check(sender.acceptRequest(retryFinal)==3,"legacy final ACK permits explicitly requested final DATA recovery before business receipt");
            sender.prepare(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.DATA,3,3,new byte[]{3}));
            receiver.acceptRequestedData(data3,d->{});
            String completion=receiver.prepareCompletion(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,direction!=ReliableTransferEngine.Direction.HOST_TO_VM?ReliableTransferEngine.Type.BUSINESS_ACK:ReliableTransferEngine.Type.RECEIVED,0,3,new byte[0]));
            if(direction!=ReliableTransferEngine.Direction.HOST_TO_VM)sender.acceptBusinessAck(completion,session,"repo","pull");else sender.acceptReceived(completion,session,"repo","pull");
            TestSupport.check(ReliableTransferEngine.decode("pair",key,completion).type()!=ReliableTransferEngine.Type.BLOCK_ACK,"completion may replace final ACK after durable reception");
            String fin=sender.prepareCompletion(new ReliableTransferEngine.Message(session,"repo","pull",kind,direction,ReliableTransferEngine.Type.FINAL,0,3,new byte[0]));
            var restored=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(sendPath));
            if(direction!=ReliableTransferEngine.Direction.HOST_TO_VM)restored.acceptBusinessAck(completion,session,"repo","pull");else restored.acceptReceived(completion,session,"repo","pull");
            TestSupport.check(restored.retry(session).equals(fin),"restored FINAL accepts duplicate receipt without clearing its ciphertext");
        }

    }

    private static void sparseLegacyFileResumesPull(Path fixture)throws Exception {
        byte[] key=new byte[32];String session=UUID.randomUUID().toString();
        Path sendPath=java.nio.file.Files.createDirectory(fixture.resolve("legacy-pull-send")),receivePath=java.nio.file.Files.createDirectory(fixture.resolve("legacy-pull-receive"));
        var sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(sendPath));
        var receiver=ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(receivePath));
        java.util.function.BiFunction<ReliableTransferEngine.Type,Integer,ReliableTransferEngine.Message> message=(type,index)->new ReliableTransferEngine.Message(session,"file","legacy-pull",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,type,index,5,type==ReliableTransferEngine.Type.DATA?new byte[]{index.byteValue()}:new byte[0]);
        String invite=sender.prepare(message.apply(ReliableTransferEngine.Type.INVITE,0));
        sender.acceptReady(receiver.acceptInviteAndReady(invite),session);
        sender.prepare(message.apply(ReliableTransferEngine.Type.DATA,1));
        sender.acceptBlockAck(ReliableTransferEngine.encode("pair",key,message.apply(ReliableTransferEngine.Type.BLOCK_ACK,1)));
        String second=sender.prepare(message.apply(ReliableTransferEngine.Type.DATA,2));
        sender.acceptBlockAck(receiver.acceptData(second,data->{}));
        sender.prepare(message.apply(ReliableTransferEngine.Type.DATA,3));
        sender.acceptMissing(ReliableTransferEngine.encode("pair",key,message.apply(ReliableTransferEngine.Type.MISSING,1)));
        String first=sender.prepare(message.apply(ReliableTransferEngine.Type.DATA,1));
        sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(sendPath));
        receiver=ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(receivePath));
        TestSupport.check(sender.acceptRequest(receiver.resumePullReceiver())==1,"legacy sparse receiver first requests missing block one");
        String request3=receiver.acceptRequestedData(first,data->{});
        TestSupport.check(ReliableTransferEngine.decode("pair",key,request3).index()==3,"saved sparse block two is not requested again");
        var beforeAdvance=sender;
        fails(()->beforeAdvance.acceptRequest(ReliableTransferEngine.encode("pair",key,message.apply(ReliableTransferEngine.Type.REQUEST,5))),"sparse request cannot confirm unprepared block four");
        TestSupport.check(sender.acceptRequest(request3)==3,"prepared sparse prefix permits pull migration");
        sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(sendPath));
        receiver=ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(receivePath));
        String third=sender.prepare(message.apply(ReliableTransferEngine.Type.DATA,3));
        TestSupport.check(sender.acceptRequest(receiver.acceptRequestedData(third,data->{}))==4,"migration continues after restart");
    }

    private static void unorderedFileBlocks(Path fixture)throws Exception{
        Path workspace=java.nio.file.Files.createDirectory(fixture.resolve("unordered-file"));
        byte[] key=new byte[32];String session=UUID.randomUUID().toString();
        TransferSessionStore store=new TransferSessionStore(workspace);ReliableTransferEngine receiver=ReliableTransferEngine.receiver("pair",key,store);
        receiver.acceptInviteAndReady(ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,3,new byte[0])));
        java.util.Set<Integer> persisted=new java.util.HashSet<>();
        for(int index:new int[]{3,1,3,2}){
            String data=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,index,3,new byte[]{(byte)index}));
            String ack=receiver.acceptData(data,m->persisted.add(m.index()));
            var message=ReliableTransferEngine.decode("pair",key,ack);
            TestSupport.check(message.type()==ReliableTransferEngine.Type.BLOCK_ACK&&message.index()==index,"out-of-order DATA must persist then ACK its actual index");
            if(index==3&&persisted.size()==1)TestSupport.check(receiver.nextIndex(session)==1,"max block is not completion");
            if(index==1)TestSupport.check(receiver.nextIndex(session)==2,"smallest missing block wins");
            receiver=ReliableTransferEngine.receiver("pair",key,new TransferSessionStore(workspace));
        }
        TestSupport.check(persisted.size()==3&&receiver.nextIndex(session)==4,"restarted receiver retains sparse coverage");
        receiver.prepareCompletion(new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BUSINESS_ACK,0,3,new byte[]{1}));
        Path senderWorkspace=java.nio.file.Files.createDirectory(fixture.resolve("sparse-file-sender"));
        ReliableTransferEngine sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(senderWorkspace));
        String invite=sender.prepare(new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,3,new byte[0]));
        String ready=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.READY,0,3,new byte[0]));
        sender.acceptReady(ready,session);
        sender.prepare(new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,1,3,new byte[]{1}));
        String future=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BLOCK_ACK,2,3,new byte[0]));
        fails(()->sender.acceptBlockAck(future),"ACK cannot confirm an unsent file block");
        String ack1=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BLOCK_ACK,1,3,new byte[0]));
        TestSupport.check(sender.acceptBlockAckResult(ack1).nextIndex()==2,"first confirmation selects block two");
        String second=sender.prepare(new ReliableTransferEngine.Message(session,"file","file-id",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,2,3,new byte[]{2}));
        var restarted=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(senderWorkspace));
        TestSupport.check(restarted.acceptBlockAckResult(ack1).nextIndex()==2&&restarted.retry(session).equals(second),"duplicate ACK after restart keeps smallest pending block");
    }

    static void run(Path fixture) throws Exception {
        receiverDrivenRequests(fixture);
        unorderedFileBlocks(fixture);
        sparseLegacyFileResumesPull(fixture);
        String session = UUID.randomUUID().toString();
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 7);
        Path senderWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("sender"));
        Path receiverWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("receiver"));
        TransferSessionStore senderStore = new TransferSessionStore(senderWorkspace);
        TransferSessionStore receiverStore = new TransferSessionStore(receiverWorkspace);
        ReliableTransferEngine sender = ReliableTransferEngine.sender("pair", key, senderStore);
        ReliableTransferEngine receiver = ReliableTransferEngine.receiver("pair", key, receiverStore);
        ReliableTransferEngine.Message invite = new ReliableTransferEngine.Message(session, "repo", "inner-session",
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                ReliableTransferEngine.Type.INVITE, 0, 3, new byte[0]);
        String inviteFrame = sender.prepare(invite);
        TestSupport.check(senderStore.currentContext().direction()==ReliableTransferEngine.Direction.HOST_TO_VM&&senderStore.currentContext().scopeId().equals("repo"),"sender restart context records direction, project and session");
        String ready = receiver.acceptInviteAndReady(inviteFrame);
        var duplicateInvite=receiver.acceptInviteResult(inviteFrame);
        TestSupport.check(duplicateInvite.disposition()==ReliableTransferEngine.InviteDisposition.DUPLICATE_READY&&ready.equals(duplicateInvite.readyFrame()),"duplicate INVITE replays the cached READY");
        TestSupport.check(receiverStore.currentContext().session().equals(session),"receiver restart context follows accepted invitation");
        sender.acceptReady(ready, session);
        TestSupport.check(sender.acceptReadyResult(ready,session).disposition()==ReliableTransferEngine.ReadyDisposition.DUPLICATE,"duplicate READY does not restart DATA");
        ReliableTransferEngine.Message first = data(session, 1, 3, new byte[]{1, 2});
        String firstFrame = sender.prepare(first);
        ReliableTransferEngine restartedSender = ReliableTransferEngine.sender("pair", key, new TransferSessionStore(senderWorkspace));
        TestSupport.check(firstFrame.equals(restartedSender.retry(session)), "restart resumes exact unacknowledged ciphertext");

        AtomicInteger durableWrites = new AtomicInteger();
        String ack = receiver.acceptData(firstFrame, ignored -> durableWrites.incrementAndGet());
        TransferSessionStore restartedReceiverStore = new TransferSessionStore(receiverWorkspace);
        ReliableTransferEngine restartedReceiver = ReliableTransferEngine.receiver("pair", key, restartedReceiverStore);
        String repeatedAck = restartedReceiver.acceptData(firstFrame, ignored -> durableWrites.incrementAndGet());
        TestSupport.check(durableWrites.get() == 2, "duplicate data is rechecked by idempotent inner inbox");
        TestSupport.check(ack.equals(repeatedAck), "lost block ACK reuses identical encrypted ACK");
        TestSupport.check(sender.acceptBlockAck(ack) == 1, "matching ACK advances one block");

        ReliableTransferEngine.Message second = data(session, 2, 3, new byte[]{3});
        String secondFrame = sender.prepare(second);
        var duplicateAck=sender.acceptBlockAckResult(ack);
        TestSupport.check(duplicateAck.disposition()==ReliableTransferEngine.AckDisposition.DUPLICATE&&duplicateAck.nextIndex()==2&&secondFrame.equals(sender.retry(session)), "old duplicate ACK is ignored without clearing the next block");
        AtomicInteger persistedBeforeReply = new AtomicInteger();
        String secondAck = receiver.acceptData(secondFrame, ignored -> persistedBeforeReply.incrementAndGet());
        TestSupport.check(persistedBeforeReply.get() == 1 && receiverStore.hasFrame(session), "receiver persists block before making ACK available");
        var advancedAck=sender.acceptBlockAckResult(secondAck);
        TestSupport.check(advancedAck.disposition()==ReliableTransferEngine.AckDisposition.ADVANCED&&advancedAck.acknowledgedIndex()==2&&advancedAck.nextIndex()==3, "matching second ACK advances the cursor once");
        String thirdFrame=sender.prepare(data(session,3,3,new byte[]{4}));
        var staleAckDuringThird=sender.acceptBlockAckResult(ack);
        TestSupport.check(staleAckDuringThird.disposition()==ReliableTransferEngine.AckDisposition.DUPLICATE&&staleAckDuringThird.nextIndex()==3&&thirdFrame.equals(sender.retry(session)),"ACK 1 arriving while DATA 3 is pending cannot change the frame or cursor");
        String thirdAck=receiver.acceptData(thirdFrame,ignored->{});
        TestSupport.check(sender.acceptBlockAckResult(thirdAck).disposition()==ReliableTransferEngine.AckDisposition.ADVANCED&&sender.nextIndex(session)==4,"DATA 3 advances only after matching ACK 3");
        TestSupport.check(receiver.acceptInviteResult(inviteFrame).disposition()==ReliableTransferEngine.InviteDisposition.ALREADY_ADVANCED,"late duplicate INVITE cannot rewind an advanced receiver");

        String failedSession = UUID.randomUUID().toString();
        String failedFrame = sender.prepare(data(failedSession, 1, 1, new byte[]{9}));
        fails(() -> receiver.acceptData(failedFrame, ignored -> { throw new IOException("disk full"); }), "failed durable write produces no ACK");
        TestSupport.check(!receiverStore.hasFrame(failedSession), "failed receive has no success ACK cached");

        String wire = ReliableTransferEngine.encode("pair", key, first);
        TestSupport.check(first.equals(ReliableTransferEngine.decode("pair", key, wire)), "encrypted controls preserve message fields and binary data");
        fails(() -> ReliableTransferEngine.decode("pair", new byte[32], wire), "wrong key rejected before state mutation");
        testFileLifecycle(fixture);
        testProjectInbox(fixture);
        System.out.println("PASS reliable stop-and-wait persistence, duplicate, retry and ACK ordering checks");
    }

    private static void testFileLifecycle(Path fixture) throws Exception {
        Path senderWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("file-lifecycle-sender"));
        Path receiverWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("file-lifecycle-receiver"));
        Path source = senderWorkspace.resolve("source.bin");
        byte[] contents = new byte[10_100]; new java.util.Random(19).nextBytes(contents); java.nio.file.Files.write(source, contents);
        FileTransfer.Outgoing outgoing = FileTransfer.generate(source, 4, senderWorkspace);
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 11);
        String identity = "filepair", session = UUID.randomUUID().toString(), fileId = outgoing.metadata().session();
        ReliableTransferEngine sender = ReliableTransferEngine.sender(identity,key,new TransferSessionStore(senderWorkspace));
        ReliableTransferEngine receiver = ReliableTransferEngine.receiver(identity,key,new TransferSessionStore(receiverWorkspace));
        ReliableTransferEngine.Message invite = new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,
                ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,outgoing.metadata().count(),new byte[0]);
        sender.acceptRequest(receiver.acceptPullInviteResult(sender.prepare(invite)).readyFrame());
        FileTransfer.Received[] received = new FileTransfer.Received[1];
        for(int index=1;index<=outgoing.metadata().count();index++){
            byte[] chunk=outgoing.chunks().get(index-1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String data=sender.prepare(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,
                    ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,index,outgoing.metadata().count(),chunk));
            String ack=receiver.acceptRequestedData(data,message->received[0]=FileTransfer.receive(receiverWorkspace,new String(message.payload(),java.nio.charset.StandardCharsets.UTF_8)));
            if(index<outgoing.metadata().count()){
                FileTransfer.Received partial=received[0];
                fails(()->FileReceiveConfirmation.confirmVerified(receiverWorkspace,session,fileId,partial,identity,key),
                        "partial file cannot claim verified reception");
                sender.acceptRequest(ack);
            }
        }
        TestSupport.check(received[0]!=null&&received[0].complete(),"encrypted FILE blocks reassemble through existing binary inbox");
        Path target=receiverWorkspace.resolve("chosen-target.bin");
        String business=FileReceiveConfirmation.confirmVerified(receiverWorkspace,session,fileId,received[0],identity,key);
        TestSupport.check(!java.nio.file.Files.exists(target),"verified reception confirms before receiver selects a destination");
        TestSupport.check(FileReceiveConfirmation.replayData(receiverWorkspace,session,fileId,identity,key)!=null,
                "duplicate final DATA replays the durable receive confirmation");
        sender.acceptBusinessAck(business,session,"file",fileId);
        String fin=sender.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,
                ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,outgoing.metadata().count(),new byte[0]));
        String finalAck=FileReceiveConfirmation.acceptFinal(receiverWorkspace,fin,identity,key);
        TestSupport.check(ReliableTransferEngine.decode(identity,key,finalAck).type()==ReliableTransferEngine.Type.FINAL_ACK
                &&java.nio.file.Files.exists(received[0].verifiedFile()),"final confirmation preserves verified cache for later saving");
        TestSupport.check(FileReceiveConfirmation.acceptFinal(receiverWorkspace,fin,identity,key).equals(finalAck),
                "duplicate FINAL replays the same durable final confirmation");
        TestSupport.check(ReliableTransferEngine.decode(identity,key,
                FileReceiveConfirmation.replayData(receiverWorkspace,session,fileId,identity,key)).type()==ReliableTransferEngine.Type.BUSINESS_ACK,
                "completed receiver can still replay verification ACK for a delayed duplicate DATA");
        TestSupport.check(FileTransfer.latestVerified(receiverWorkspace).metadata().session().equals(fileId),
                "a completed unsaved file is discoverable for saving after restart");
        FileTransfer.Received restored=FileTransfer.resume(receiverWorkspace,fileId);
        TestSupport.check(restored.complete(),"completed receive remains resumable after final confirmation");
        FileTransfer.save(restored,FileTransfer.destination(target));
        TestSupport.check(java.util.Arrays.equals(contents,java.nio.file.Files.readAllBytes(target)),"receiver can save to a chosen target after sender completed");
    }

    private static void testProjectInbox(Path fixture) throws Exception {
        Path workspace = java.nio.file.Files.createDirectory(fixture.resolve("inner-inbox"));
        Config config = new Config(Model.Role.VM, java.util.List.of(new Config.Mapping("repo", ".", ".")), java.util.List.of(), 4, "git");
        byte[] content = new byte[18_000]; new java.util.Random(41).nextBytes(content);
        Model.Payload payload = Model.Payload.worktree("repo", "a".repeat(40), config.policy(), "refs/heads/main",
                java.util.List.of(new Model.Entry("data.bin", "-", Model.hash(content), Model.Encoding.RAW, content, null)));
        java.util.List<String> blocks = ClipboardTransport.split(payload, 4);
        String innerSession = ProjectChunkCodec.parse(blocks.getFirst()).session();
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 3);
        String outerSession = UUID.randomUUID().toString();
        Path senderWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("inner-sender"));
        Path receiverWorkspace = java.nio.file.Files.createDirectory(fixture.resolve("inner-receiver"));
        ProjectOutgoingStore sourceStore=new ProjectOutgoingStore(senderWorkspace);sourceStore.save(outerSession,payload,blocks);TestSupport.check(blocks.size()>2,"WORKTREE fixture spans more than two chunks");
        ReliableTransferEngine sender = ReliableTransferEngine.sender("pair", key, new TransferSessionStore(senderWorkspace));
        ReliableTransferEngine receiver = ReliableTransferEngine.receiver("pair", key, new TransferSessionStore(receiverWorkspace));
        ReliableTransferEngine.Message invite = new ReliableTransferEngine.Message(outerSession, "repo", innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                ReliableTransferEngine.Type.INVITE, 0, blocks.size(), new byte[0]);
        sender.acceptRequest(receiver.acceptPullInviteResult(sender.prepare(invite)).readyFrame());
        Model.Payload[] completed = new Model.Payload[1];
        ProjectTransferService service = new ProjectTransferService(workspace, config);
        List<String> sendBlocks=blocks;
        for (int index = 1; index <= blocks.size(); index++) {
            int current = index;
            byte[] text = sendBlocks.get(index - 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ReliableTransferEngine.Message data = new ReliableTransferEngine.Message(outerSession, "repo", innerSession,
                    ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                    ReliableTransferEngine.Type.DATA, index, blocks.size(), text);
            String frame = sender.prepare(data);
            ReliableTransferEngine.BlockSink inbox = incoming -> {
                ClipboardTransport.Received received = service.receiveChunk("repo",
                        new String(incoming.payload(), java.nio.charset.StandardCharsets.UTF_8));
                if (received.payload() != null) completed[0] = received.payload();
            };
            String ack = receiver.acceptRequestedData(frame, inbox);
            if (index == 1) TestSupport.check(ack.equals(receiver.acceptRequestedData(frame, inbox)), "inbox duplicate returns same durable ACK");
            if(index<blocks.size())TestSupport.check(sender.acceptRequest(ack)==current+1,"next request follows durable block persistence");
            if(index==1){sender=ReliableTransferEngine.sender("pair",key,new TransferSessionStore(senderWorkspace));sendBlocks=sourceStore.loadParts(outerSession,innerSession,blocks.size());TestSupport.check(sendBlocks.equals(blocks),"restarted HOST sender replays remaining original chunks byte-for-byte");}
        }
        TestSupport.check(completed[0] != null && java.util.Arrays.equals(content, completed[0].entries().getFirst().data()),
                "encrypted transport feeds full inner VMSYNC validation and payload recovery");
        TestSupport.check(java.util.Arrays.equals(Codec.encode(sourceStore.load(outerSession)),Codec.encode(payload)),"exact project sender payload survives process restart");sourceStore.clear(outerSession);
        String received = service.recordWorktreeReceived(outerSession, "repo", innerSession, blocks.size(), completed[0], "pair", key);
        TransferCompletionStore completions = new TransferCompletionStore(workspace);
        TestSupport.check(completions.find(outerSession).orElseThrow().phase() == TransferCompletionStore.Phase.RECEIVED_DURABLE,
                "HOST-to-VM becomes durably received before any local preview or apply");
        PendingWorktreeStore pendingStore=new PendingWorktreeStore(workspace);TestSupport.check(pendingStore.read().equals(new PendingWorktreeStore.Pending("repo",outerSession,innerSession)),"completed WORKTREE publishes one restart pointer");
        fails(()->pendingStore.save(new PendingWorktreeStore.Pending("other",UUID.randomUUID().toString(),UUID.randomUUID().toString())),"another project cannot replace an unprocessed WORKTREE pointer");
        ProjectTransferService hostService = new ProjectTransferService(senderWorkspace,
                new Config(Model.Role.HOST, java.util.List.of(new Config.Mapping("repo", ".", ".")), java.util.List.of(), 4, "git"));
        String fin = hostService.acceptWorktreeReceivedAndPrepareFinal(received, "repo", innerSession, "pair", key);
        String finalAck = service.acceptWorktreeFinalAndPrepareAck(fin, "pair", key);
        TestSupport.check(finalAck.equals(service.acceptWorktreeFinalAndPrepareAck(fin, "pair", key)),
                "duplicate HOST final is answered from durable worktree receipt without application");
        hostService.acceptWorktreeFinalAck(finalAck, "repo", innerSession, "pair", key);
        TestSupport.check(completions.find(outerSession).orElseThrow().phase() == TransferCompletionStore.Phase.COMPLETE,
                "HOST-to-VM transport completes only after both final confirmations");
        completed[0]=null;
        Model.Payload reloaded=service.loadCompletedWorktree("repo",innerSession);
        TestSupport.check(completed[0]==null&&java.util.Arrays.equals(content,reloaded.entries().getFirst().data()),
                "completed VM WORKTREE can be reloaded from disk after releasing the in-memory payload");
    }

    private static ReliableTransferEngine.Message data(String session, int index, int total, byte[] bytes) {
        return new ReliableTransferEngine.Message(session, "repo", "inner-session", ReliableTransferEngine.Kind.PROJECT,
                ReliableTransferEngine.Direction.HOST_TO_VM, ReliableTransferEngine.Type.DATA, index, total, bytes);
    }
    private static void fails(Throwing action, String label) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(label);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
