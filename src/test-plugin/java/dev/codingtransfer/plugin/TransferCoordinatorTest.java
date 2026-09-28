package dev.codingtransfer.plugin;

import dev.codingtransfer.core.ReliableTransferEngine;
import dev.codingtransfer.core.TransferSessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Virtual-clock coordinator tests: no IDEA UI, clipboard, or wall-clock sleeps. */
public final class TransferCoordinatorTest {
    private TransferCoordinatorTest() {}
    public static void main(String[] args) throws Exception {
        handlerFailureActuallyPauses();
        fixedClockSlotsAndPeerProtection();
        ownSlotWaitsWithoutRewriting();
        onlyUnprocessableFramesCanBeRecovered();
        pendingResponseRecoversFromInvalidPeer();
        hostInputPulseIsOncePerFrameAndStopsWithSession();
        busyReadStillAllowsInputAssist();
        retryRechecksClipboardAfterInput();
        retryUsesRevisionWithoutRereadingPayload();
        statusHistoryCoalescesStructuredEventsAndRejectsStaleSessions();
        uncommittedResponseIsRetainedAcrossClipboardRace();
        failedClipboardWriteRetainsResponseAndOrdinaryText();
        retriesUseBoundedBackoffAndPauseResumably();
        logOptInToggleAndSafeClear();
        delayedRdpBridgeReproducesHandshakeInterruption();
        terminalResponseReleasesOwnerAndReplays();
        fileConfirmsVerifiedReceptionAndRecoversLostFrames();
        queuedUiCallbacksDoNotBlockProtocolProgress();
        clipboardStartupFailureReleasesLeaseAndCanRetry();
        receiveInitializationFailureStopsButBusyClipboardRetries();
        windowsPartialStartupStopsReceiveAndFreshBindingCanRetry();
        restoredFinalAckPublishesAsTerminalCompletion();
        restoredPublishFailureKeepsNativeStartupDetails();
        restoredFramesKeepTheirProtocolPhaseStatus();
        System.out.println("PASS coordinator terminal release, immediate verified-file ACK, and lost-frame recovery checks");
    }

    private static void handlerFailureActuallyPauses(){
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        AtomicInteger attempts=new AtomicInteger();List<String> statuses=new ArrayList<>();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        Object owner=new Object();
        coordinator.startReceive(owner,"pair",f->{attempts.incrementAndGet();throw new java.io.IOException("EXCLUDE_MISMATCH: 排除配置不同");},new TransferCoordinator.Listener(){
            public void onStatus(String text){statuses.add(text);}
            public void onStopped(TransferCoordinator.StopReason reason){}
        });
        clipboard.write("pair DATA");
        for(int i=0;i<12;i++){clock.advance(6000);scheduler.runAll();}
        check(attempts.get()==5,"handler failures stop processing after five attempts");
        check(statuses.stream().anyMatch(t->t.contains("EXCLUDE_MISMATCH")),"handler failure shows concrete cause");
        check(!coordinator.hasActiveTransfer(),"handler pause releases lease so UI can offer continue");
        coordinator.continueListening(owner,"pair",f->{attempts.incrementAndGet();return new TransferCoordinator.Outcome(null,"resumed",false);},listener(new AtomicInteger(),new AtomicInteger()));
        clock.advance(6000);scheduler.runAll();
        check(attempts.get()==6,"explicit continue resumes paused handler processing");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void fixedClockSlotsAndPeerProtection(){
        for(boolean ack:List.of(false,true)){
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();AtomicInteger paused=new AtomicInteger(),handled=new AtomicInteger();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",f->{handled.incrementAndGet();return new TransferCoordinator.Outcome(ack?"pair ACK1":"pair DATA1","waiting",false).phase(ack?"WAIT_DATA_2":"WAIT_ACK_DATA_1");},listener(new AtomicInteger(),paused));
            clipboard.write("pair PEER");scheduler.runAll();int previous=clipboard.writes;
            int attempts=0;
            for(int second=1;second<=40;second++){
                clock.advance(1000);scheduler.runAll();boolean due=ack?(second%10==4||second%10==8):(second%10==1||second%10==6);
                check(clipboard.writes==previous+(due?1:0),"only role-specific wall-clock slots may publish: "+ack+" at "+second);
                if(due)attempts++;previous=clipboard.writes;scheduler.runAll();check(clipboard.writes==previous,"one publication per absolute second");
            }
            check(attempts==8,"eight bounded wall-clock attempts");
            clock.advance(10000);scheduler.runAll();check(paused.get()==1,"exhausted attempts preserve a response window before pausing");
            check(handled.get()==1,"refresh does not repeat business processing");coordinator.dispose();scheduler.shutdownNow();
        }
        for(boolean ack:List.of(false,true)){
            Clock clock=new Clock();clock.now=59_000;Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",f->new TransferCoordinator.Outcome(ack?"pair ACK1":"pair DATA1","waiting",false).phase(ack?"WAIT_DATA_2":"WAIT_ACK_DATA_1"),listener(new AtomicInteger(),new AtomicInteger()));
            clipboard.write("pair PEER");scheduler.runAll();int before=clipboard.writes;
            clock.now=ack?64_000:61_000;scheduler.runAll();check(clipboard.writes==before+1,"wall clock slots survive minute rollover");
            clock.now-=10_000;scheduler.runAll();check(clipboard.writes==before+1,"backward clock adjustment never repeats an older slot");
            coordinator.dispose();scheduler.shutdownNow();
        }
        for(String race:List.of("peer","slot_expired","recent")){
            Clock clock=new Clock();clock.now=500;AtomicInteger reads=new AtomicInteger();
            Clipboard clipboard=new Clipboard(){@Override public String read(){
                if(clock.now==6000&&reads.incrementAndGet()==2){if(race.equals("peer")){value="pair ACK1";revision++;}else if(race.equals("slot_expired"))clock.now=7000;}
                return value;
            }};
            ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",f->new TransferCoordinator.Outcome("pair DATA1","waiting",false).phase("WAIT_ACK_DATA_1"),listener(new AtomicInteger(),new AtomicInteger()));
            clipboard.write("pair READY");scheduler.runAll();int before=clipboard.writes;
            clock.now=1000;scheduler.runAll();check(clipboard.writes==before,"fresh publication skips adjacent slot");
            if(!race.equals("recent")){clock.now=6000;scheduler.runAll();check(clipboard.writes==before,"new peer or expired slot prevents refresh");}
            coordinator.dispose();scheduler.shutdownNow();
        }
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();AtomicInteger persisted=new AtomicInteger();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        coordinator.startReceive(new Object(),"pair",f->{persisted.incrementAndGet();return new TransferCoordinator.Outcome("pair ACK1","received",false).phase("WAIT_DATA_2");},listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair DATA1");scheduler.runAll();clock.now=2500;clipboard.write("pair DATA1");scheduler.runAll();
        check(clipboard.read().equals("pair ACK1")&&persisted.get()==1,"duplicate DATA immediately replays ACK outside timed slots");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void ownSlotWaitsWithoutRewriting(){
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();List<Boolean> keys=new ArrayList<>();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,left->{keys.add(left);return "sent";});
        Object owner=new Object();
        coordinator.startSend(owner,"pair","pair DATA",f->null,listener(new AtomicInteger(),new AtomicInteger()),
            new TransferCoordinator.LogContext(null,"TEST","host",f->new TransferCoordinator.FrameInfo("session","CONTROL",0,1),()->false,true));
        for(int i=0;i<6;i++){clock.advance(1000);scheduler.runAll();}
        check(clipboard.writes==1,"our current slot must never be periodically rewritten");
        check(keys.equals(List.of(true,false,true,false,true,false)),"waiting pulses alternate without rewriting");
        clipboard.write("foreign content");int writes=clipboard.writes;clock.advance(10000);scheduler.runAll();
        check(clipboard.writes==writes+1&&clipboard.read().equals("pair DATA"),"unprocessable foreign slot permits recovery");
        coordinator.awaitUserAction(owner,"save");clipboard.write("ordinary");clock.advance(5000);scheduler.runAll();
        check(clipboard.read().equals("ordinary"),"manual action wait never overwrites");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void onlyUnprocessableFramesCanBeRecovered(){
        for(String mode:List.of("invalid","disk","own","valid")){
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler);
            AtomicInteger handled=new AtomicInteger();
            coordinator.startSend(new Object(),"pair","pair DATA",f->{handled.incrementAndGet();
                if(mode.equals("invalid"))throw new TransferCoordinator.UnprocessableFrame("wrong session");
                if(mode.equals("own"))throw new TransferCoordinator.OwnFrame();
                if(mode.equals("disk"))throw new java.io.IOException("disk full");
                return new TransferCoordinator.Outcome("pair NEXT","next",false);
            },listener(new AtomicInteger(),new AtomicInteger()));
            clipboard.write("pair PEER");int before=clipboard.writes;clock.advance(10000);scheduler.runAll();
            if(mode.equals("invalid"))check(clipboard.read().equals("pair DATA")&&clipboard.writes==before+1,"invalid peer permits recovery");
            else if(mode.equals("valid"))check(clipboard.read().equals("pair NEXT"),"valid peer gets its response, not old DATA");
            else check(clipboard.read().equals("pair PEER")&&clipboard.writes==before,"storage failures and own echoes never authorize overwrite");
            coordinator.dispose();scheduler.shutdownNow();
        }
    }

    private static void pendingResponseRecoversFromInvalidPeer(){
        Clock clock=new Clock();FailOnceClipboard clipboard=new FailOnceClipboard();ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler);
        AtomicInteger persisted=new AtomicInteger();
        coordinator.startSend(new Object(),"pair","pair INVITE",frame->{
            if(frame.equals("pair INVALID"))throw new TransferCoordinator.UnprocessableFrame("bad frame");
            persisted.incrementAndGet();return new TransferCoordinator.Outcome("pair DATA","ready",false);
        },listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair READY");clipboard.failNext=true;scheduler.runAll();
        clipboard.write("pair INVALID");clock.advance(10000);scheduler.runAll();
        check(clipboard.read().equals("pair DATA")&&persisted.get()==1,"invalid peer cannot strand an already prepared response");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void statusHistoryCoalescesStructuredEventsAndRejectsStaleSessions() {
        TransferStatusHistory history=new TransferStatusHistory();history.begin("session-new");
        java.time.Instant time=java.time.Instant.EPOCH;
        history.update("session-new","WAIT_DATA_1","等待第 1 块",time);
        history.update("session-new","WAIT_ACK_1","等待第 1 块确认",time.plusSeconds(1));
        for(int i=0;i<100;i++)history.update("session-new","WAIT_ACK_1","等待第 1 块确认 · 重试 "+i,time.plusSeconds(2+i));
        check(history.entries().size()==2&&history.entries().get(0).text().endsWith("重试 99")
                        &&history.entries().get(1).eventKey().equals("WAIT_DATA_1"),
                "one hundred same-stage retry updates replace only the top entry");
        for(int i=2;i<8;i++)history.update("session-new","PHASE_"+i,"阶段 "+i,time.plusSeconds(200+i));
        check(history.entries().size()==TransferStatusHistory.LIMIT&&history.entries().get(0).eventKey().equals("PHASE_7"),
                "stage transitions prepend and cap the history at five");
        check(!history.update("session-old","LATE","旧会话迟到回调",time.plusSeconds(300)),"old session callbacks cannot modify active history");
        history.complete("session-new","COMPLETE","传输完成",time.plusSeconds(301));
        check(history.entries().size()==1&&history.entries().getFirst().eventKey().equals("COMPLETE"),"success leaves only the completion result");
        history.begin("failed-session");history.update("failed-session","SEND_DATA","发送第 1 块",time);
        history.update("failed-session","RETRY","等待确认 · 超时",time.plusSeconds(1));
        check(history.entries().size()==2,"failure/retry history is retained until a new explicit session");
    }

    private static void uncommittedResponseIsRetainedAcrossClipboardRace() {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        Object owner=new Object();AtomicInteger handled=new AtomicInteger();
        String invite="pair INVITE";
        coordinator.startSend(owner,"pair",invite,frame->{handled.incrementAndGet();clipboard.write(invite);return new TransferCoordinator.Outcome("pair DATA_1","data",false).retrying("WAIT_ACK_DATA_1");},listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair READY");scheduler.runAll();
        check(clipboard.read().equals("pair DATA_1"),"pending response may replace the coordinator's known previous output");
        check(handled.get()==1,"retrying publication does not reapply the incoming business operation");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void failedClipboardWriteRetainsResponseAndOrdinaryText() throws Exception {
        Clock clock=new Clock();FailOnceClipboard clipboard=new FailOnceClipboard();ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        Object owner=new Object();AtomicInteger handled=new AtomicInteger();TransferStatusHistory history=new TransferStatusHistory();String uiSession=java.util.UUID.randomUUID().toString();java.nio.file.Path logWorkspace=java.nio.file.Files.createTempDirectory("coordinator-log-");history.begin(uiSession);
        TransferCoordinator.Listener listener=new TransferCoordinator.Listener(){public void onStatus(String ignored){}public void onStatus(TransferCoordinator.StatusEvent event){history.update(event.sessionKey(),event.eventKey(),event.text(),java.time.Instant.EPOCH);}public void onStopped(TransferCoordinator.StopReason ignored){}};
        coordinator.startSend(owner,"pair","pair INVITE",frame->{handled.incrementAndGet();return new TransferCoordinator.Outcome("pair DATA","waiting block 1 confirmation",false).retrying("WAIT_ACK_DATA_1");},listener,
                new TransferCoordinator.LogContext(logWorkspace,"TEST",uiSession,frame->new TransferCoordinator.FrameInfo("00000000-0000-0000-0000-000000000001","READY",0,1)));
        clipboard.write("pair READY");clipboard.failNext=true;scheduler.runAll();
        check(clipboard.read().equals("pair READY")&&handled.get()==1,"failed response publication keeps the incoming request and does not rerun the handler");
        scheduler.runAll();
        check(clipboard.read().equals("pair DATA")&&handled.get()==1,"retained response publishes successfully on the next poll");
        check(history.entries().stream().anyMatch(entry->entry.eventKey().equals("WAIT_ACK_DATA_1")),"real coordinator status event updates the structured history");
        clipboard.write("ordinary user text");scheduler.runAll();
        check(clipboard.read().equals("ordinary user text"),"ordinary clipboard content is preserved while the transfer pauses");
        var eventLog=logWorkspace.resolve(".vmsync/transfer-logs/00000000-0000-0000-0000-000000000001.log");String logText=java.nio.file.Files.readString(eventLog);check(logText.contains("plugin_version=")&&logText.contains("message_type=READY")&&!logText.contains("pair READY")&&!logText.contains("pair DATA"),"session event log records safe frame metadata without clipboard content");
        check(logText.contains("event=CLIPBOARD_OBSERVE")&&logText.contains("route=RECOVER_UNPROCESSABLE"),"diagnostics explain observed clipboard routing");
        check(!logText.contains("ordinary user text"),"diagnostics never log ordinary clipboard text");
        coordinator.dispose();scheduler.shutdownNow();try{deleteTree(logWorkspace);}catch(Exception ignored){}
    }

    private static void retriesUseBoundedBackoffAndPauseResumably() {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        Object owner=new Object();AtomicInteger paused=new AtomicInteger();String request="pair DATA exact-ciphertext";coordinator.startSend(owner,"pair",request,frame->{throw new AssertionError("no peer frame expected");},listener(new AtomicInteger(),paused));
        int writes=clipboard.writes;scheduler.runAll();
        check(clipboard.writes==writes&&coordinator.hasActiveTransfer(),"own slot remains intact without timer retry exhaustion");
        for(long delay:new long[]{10000,10000,10000,10000,10000,10000,10000,10000}){
            clipboard.write("invalid external content");int before=clipboard.writes;
            clock.advance(delay);scheduler.runAll();check(clipboard.writes==before+1,"only invalid external slot may be recovered");
        }
        clipboard.write("invalid again");clock.advance(10000);scheduler.runAll();
        check(!coordinator.hasActiveTransfer()&&paused.get()==1,"persistent external replacement has a bounded recovery budget");
        coordinator.continueConfirmation(owner,"pair",request,frame->{throw new AssertionError("no peer frame expected");},listener(new AtomicInteger(),paused));
        check(coordinator.hasActiveTransfer()&&clipboard.read().equals(request),"explicit continue resumes the persisted pending frame");coordinator.dispose();scheduler.shutdownNow();
    }

    private static void logOptInToggleAndSafeClear() throws Exception {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard(){
            boolean detailed;
            @Override public String read(boolean enabled){detailed=enabled;return read();}
            @Override public String readDiagnostics(){return detailed?"open_error=5 open_pid=42 get_data_us=123":"";}
        };ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        java.nio.file.Path workspace=java.nio.file.Files.createTempDirectory("transfer-log-toggle-");java.nio.file.Path state=workspace.resolve(".vmsync");java.nio.file.Files.createDirectories(state.resolve("inbox"));
        java.nio.file.Path businessFile=state.resolve("inbox").resolve("preserve.dat");java.nio.file.Files.writeString(businessFile,"business-state");
        java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean(false);AtomicInteger describes=new AtomicInteger();String session="00000000-0000-0000-0000-000000000011";
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(workspace,"TEST","ui-log-session",frame->{describes.incrementAndGet();return new TransferCoordinator.FrameInfo(session,"READY",0,1);},enabled::get);
        coordinator.startSend(new Object(),"pair","pair INVITE",frame->new TransferCoordinator.Outcome(null,"phase",false),listener(new AtomicInteger(),new AtomicInteger()),context);
        clipboard.write("pair READY");scheduler.runAll();java.nio.file.Path directory=state.resolve("transfer-logs");
        check(!java.nio.file.Files.exists(directory)&&describes.get()==0,"logging defaults off without decoding frames or creating a log directory");
        enabled.set(true);clipboard.write("pair BLOCK_ACK");scheduler.runAll();java.nio.file.Path log=directory.resolve(session+".log");
        check(java.nio.file.Files.isRegularFile(log)&&java.nio.file.Files.readString(log).contains("LOG_ENABLED"),"enabling mid-session starts a marked log segment on the next event");
        check(java.nio.file.Files.readString(log).contains("event=CLIPBOARD_READ_DETAIL")&&java.nio.file.Files.readString(log).contains("open_pid=42"),"enabled read diagnostics are written to session log");
        java.nio.file.Path diagnostics=java.nio.file.Files.createTempDirectory("transfer-log-causes-");String diagnosticId="00000000-0000-0000-0000-000000000013";TransferEventLog diagnostic=TransferEventLog.open(diagnostics,diagnosticId,"TEST");Throwable manyCauses=new IllegalArgumentException("private deepest text");for(int i=0;i<7;i++)manyCauses=new IllegalStateException("private wrapped text",manyCauses);diagnostic.error("ERROR",manyCauses);diagnostic.close();String diagnosticText=java.nio.file.Files.readString(diagnostics.resolve(".vmsync/transfer-logs").resolve(diagnosticId+".log"));check(diagnosticText.contains("cause[1]=java.lang.IllegalStateException")&&diagnosticText.contains("TransferCoordinatorTest")&&diagnosticText.contains("causes_truncated=true")&&!diagnosticText.contains("cause[5]=")&&!diagnosticText.contains("private deepest text")&&!diagnosticText.contains("private wrapped text"),"diagnostic logs include bounded exception causes and stacks while omitting exception messages");deleteTree(diagnostics);
        clipboard.write("ordinary user text");scheduler.runAll();String beforeClear=java.nio.file.Files.readString(log);check(beforeClear.contains("route=RECOVER_UNPROCESSABLE"),"enabled log records only the ordinary-content change classification");
        enabled.set(false);long before=java.nio.file.Files.size(log);int describeCount=describes.get();clipboard.write("pair BUSINESS_ACK");scheduler.runAll();
        check(java.nio.file.Files.size(log)==before&&describes.get()==describeCount,"disabling during transfer stops appends and log-only frame decoding");
        java.nio.file.Files.writeString(state.resolve("transfer-logs").resolve("notes.txt"),"leave me");
        String linkedSession="00000000-0000-0000-0000-000000000012";java.nio.file.Files.createSymbolicLink(directory.resolve(linkedSession+".log"),businessFile);
        java.nio.file.Files.writeString(directory.resolve(linkedSession+".log.1"),"rotated");
        AtomicInteger deleted=new AtomicInteger(-1),skipped=new AtomicInteger(-1);
        coordinator.clearTransferLogs(workspace,result->{deleted.set(result.deletedFiles());skipped.set(result.skippedFiles());},failure->{throw new AssertionError(failure);});
        check(!java.nio.file.Files.exists(log)&&!java.nio.file.Files.exists(directory.resolve(linkedSession+".log.1"))&&java.nio.file.Files.exists(directory.resolve("notes.txt"))&&java.nio.file.Files.isSymbolicLink(directory.resolve(linkedSession+".log")),"clear removes only legal ordinary log files and preserves unknown entries and symlinks");
        check(java.nio.file.Files.readString(businessFile).equals("business-state")&&deleted.get()==2&&skipped.get()==2,"clear preserves business inbox data and reports skipped entries");
        enabled.set(true);clipboard.write("pair FINAL");scheduler.runAll();String fresh=java.nio.file.Files.readString(log);
        check(fresh.lines().filter(line->line.contains("event=LOG_ENABLED")).count()==1&&!fresh.contains("reason=NON_PROTOCOL_CHANGED"),"post-clear logging starts a clean segment with only later events");
        coordinator.dispose();scheduler.shutdownNow();deleteTree(workspace);
        java.nio.file.Path retained=java.nio.file.Files.createTempDirectory("transfer-log-retention-");String activeId="00000000-0000-0000-0000-000000000021";TransferEventLog active=TransferEventLog.open(retained,activeId,"TEST");active.event("OPEN","phase=active");for(int i=22;i<43;i++){String id=String.format(java.util.Locale.ROOT,"00000000-0000-0000-0000-%012d",i);TransferEventLog item=TransferEventLog.open(retained,id,"TEST");item.event("OPEN","");item.close();}java.nio.file.Path logDir=retained.resolve(".vmsync/transfer-logs");try(var paths=java.nio.file.Files.list(logDir)){check(paths.filter(path->path.getFileName().toString().endsWith(".log")).count()==20&&java.nio.file.Files.isRegularFile(logDir.resolve(activeId+".log")),"retention keeps at most twenty session logs without pruning an active session");}active.close();deleteTree(retained);
        java.nio.file.Path rotationWorkspace=java.nio.file.Files.createTempDirectory("transfer-log-rotation-");String rotateId="00000000-0000-0000-0000-000000000099";TransferEventLog rotating=TransferEventLog.open(rotationWorkspace,rotateId,"TEST");rotating.event("LARGE", "x".repeat(300_000));rotating.event("AFTER_ROTATION","safe");rotating.close(); logDir=rotationWorkspace.resolve(".vmsync/transfer-logs");
        check(java.nio.file.Files.isRegularFile(logDir.resolve(rotateId+".log.1"))&&java.nio.file.Files.readString(logDir.resolve(rotateId+".log")).contains("event=AFTER_ROTATION"),"log rotation keeps one prior file and appends to the current session log");
        TransferEventLog.ClearResult rotationClear=TransferEventLog.clear(rotationWorkspace);check(rotationClear.deletedFiles()==2&&!java.nio.file.Files.exists(logDir.resolve(rotateId+".log.1")),"clear removes both current and rotated files");deleteTree(rotationWorkspace);
    }

    private static void delayedRdpBridgeReproducesHandshakeInterruption() throws Exception {
        Clock clock=new Clock();DelayedBridge bridge=new DelayedBridge(clock);EndpointClipboard senderClipboard=bridge.sender(),receiverClipboard=bridge.receiver();
        ManualScheduler senderScheduler=new ManualScheduler(),receiverScheduler=new ManualScheduler();
        TransferCoordinator sender=new TransferCoordinator(senderClipboard,Runnable::run,()->clock.now,senderScheduler);
        TransferCoordinator receiver=new TransferCoordinator(receiverClipboard,Runnable::run,()->clock.now,receiverScheduler);
        PathPair paths=new PathPair(java.nio.file.Files.createTempDirectory("rdp-sender-"),java.nio.file.Files.createTempDirectory("rdp-receiver-"));
        byte[] key=new byte[32];java.util.Arrays.fill(key,(byte)23);String identity="rdp",session=java.util.UUID.randomUUID().toString();int total=3;
        ReliableTransferEngine tx=ReliableTransferEngine.sender(identity,key,new TransferSessionStore(paths.sender));
        ReliableTransferEngine rx=ReliableTransferEngine.receiver(identity,key,new TransferSessionStore(paths.receiver));
        String invite=tx.prepare(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,total,new byte[0]));
        int[] counts=new int[5];java.util.List<Integer> sent=new java.util.ArrayList<>();String[] data={null};java.util.Set<Integer> durable=new java.util.HashSet<>();
        sender.startSend(new Object(),identity,invite,frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);switch(m.type()){
            case READY->{counts[0]++;var result=tx.acceptReadyResult(frame,session);if(result.disposition()==ReliableTransferEngine.ReadyDisposition.DUPLICATE)return new TransferCoordinator.Outcome(null,"duplicate READY ignored",false).phase("WAIT_ACK_DATA_"+result.awaitingDataIndex());data[0]=tx.prepare(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,1,total,new byte[]{1}));sent.add(1);return new TransferCoordinator.Outcome(data[0],"data 1",false).retrying("WAIT_ACK_DATA_1");}
            case BLOCK_ACK->{counts[1]++;var result=tx.acceptBlockAckResult(frame);if(result.disposition()==ReliableTransferEngine.AckDisposition.DUPLICATE)return new TransferCoordinator.Outcome(null,"duplicate ACK ignored",false).phase("WAIT_ACK_DATA_"+(result.nextIndex()-1));if(result.nextIndex()<=total){int i=result.nextIndex();data[0]=tx.prepare(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,i,total,new byte[]{(byte)i}));sent.add(i);return new TransferCoordinator.Outcome(data[0],"data "+i,false).retrying("WAIT_ACK_DATA_"+i);}return new TransferCoordinator.Outcome(null,"all blocks acknowledged",false,true);}
            default->throw new java.io.IOException("unexpected sender frame "+m.type());
        }},listener(new AtomicInteger(),new AtomicInteger()));
        receiver.startReceive(new Object(),identity,frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);switch(m.type()){
            case INVITE->{counts[2]++;var result=rx.acceptInviteResult(frame);if(result.readyFrame()==null)return new TransferCoordinator.Outcome(null,"late duplicate invite ignored",false).phase("WAIT_DATA");return new TransferCoordinator.Outcome(result.readyFrame(),"ready",false).replayable("WAIT_DATA_1");}
            case DATA->{counts[3]++;String ack=rx.acceptData(frame,block->durable.add(block.index()));return new TransferCoordinator.Outcome(ack,"persisted",false).replayable("WAIT_DATA_"+(m.index()+1));}
            default->throw new java.io.IOException("unexpected receiver frame "+m.type());
        }},listener(new AtomicInteger(),new AtomicInteger()));
        for(int i=0;i<300;i++){clock.advance(400);bridge.deliver();senderScheduler.runAll();receiverScheduler.runAll();bridge.deliver();}
        check(counts[3]>=3&&durable.equals(java.util.Set.of(1,2,3)),"delayed bridge durably delivers all three stop-and-wait blocks");
        check(sent.equals(java.util.List.of(1,2,3))&&tx.nextIndex(session)==4,"sender cursor advances once per ordered ACK and never sends n+1 early");
        sender.dispose();receiver.dispose();senderScheduler.shutdownNow();receiverScheduler.shutdownNow();deleteTree(paths.sender);deleteTree(paths.receiver);
    }

    private static void terminalResponseReleasesOwnerAndReplays() throws Exception {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        Object owner=new Object();AtomicInteger successes=new AtomicInteger(),pending=new AtomicInteger();
        coordinator.startReceive(owner,"pair",frame->new TransferCoordinator.Outcome("pair FINAL_ACK","business complete",true),listener(successes,pending));
        clipboard.write("pair FINAL");scheduler.runAll();
        check(!coordinator.hasActiveTransfer()&&successes.get()==1&&pending.get()==0,"successful receiver releases UI lease immediately");
        check(clipboard.read().equals("pair FINAL_ACK"),"initial terminal response is retained on clipboard");
        clipboard.write("");clock.advance(2_100);scheduler.runAll();
        check(clipboard.read().isEmpty(),"blank clipboard is not treated as a duplicate FINAL");
        clipboard.write("pair FINAL");clock.advance(2_100);scheduler.runAll();
        check(clipboard.read().equals("pair FINAL_ACK")&&!coordinator.hasActiveTransfer(),"terminal duplicate replay does not reacquire or retain the UI owner");
        clipboard.write("user text");clock.advance(181_000);scheduler.runAll();
        check(clipboard.read().equals("user text"),"terminal expiry preserves newer user clipboard contents");
        clipboard.write("pair FINAL_ACK");
        // The first response has already expired; a fresh completion verifies owned-content cleanup.
        coordinator.startReceive(owner,"pair",frame->new TransferCoordinator.Outcome("pair SECOND_ACK","done",true),listener(successes,pending));
        clipboard.write("pair SECOND_FINAL");scheduler.runAll();clock.advance(181_000);scheduler.runAll();
        check(clipboard.read().isEmpty()&&!coordinator.hasActiveTransfer(),"expiry clears only an unchanged terminal response");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void fileConfirmsVerifiedReceptionAndRecoversLostFrames() throws Exception {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler senderScheduler=new ManualScheduler(),receiverScheduler=new ManualScheduler();
        TransferCoordinator sender=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,senderScheduler);
        TransferCoordinator receiver=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,receiverScheduler);
        Object senderOwner=new Object(),receiverOwner=new Object();AtomicInteger senderDone=new AtomicInteger(),receiverDone=new AtomicInteger(),pending=new AtomicInteger();
        java.util.Set<String> persisted=new java.util.HashSet<>();
        java.nio.file.Path vmWorkspace=java.nio.file.Files.createTempDirectory("every-sync-coordinator-sender-");
        java.nio.file.Path hostWorkspace=java.nio.file.Files.createTempDirectory("every-sync-coordinator-receiver-");
        byte[] key=new byte[32];java.util.Arrays.fill(key,(byte)17);String identity="pair",session=java.util.UUID.randomUUID().toString(),fileId="file-inner";int total=1;
        ReliableTransferEngine vm=ReliableTransferEngine.sender(identity,key,new TransferSessionStore(vmWorkspace));
        ReliableTransferEngine host=ReliableTransferEngine.receiver(identity,key,new TransferSessionStore(hostWorkspace));
        ReliableTransferEngine.Message invite=new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,total,new byte[0]);
        String inviteFrame=vm.prepare(invite);
        String[] dataFrame={null},businessAck={null};
        TransferCoordinator.FrameHandler senderHandler=frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);return switch(m.type()){
            case READY->{vm.acceptReady(frame,session);dataFrame[0]=vm.prepare(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,1,total,new byte[]{4,5,6}));yield new TransferCoordinator.Outcome(dataFrame[0],"sending final block",false);}
            case BLOCK_ACK->{vm.acceptBlockAck(frame);yield new TransferCoordinator.Outcome(null,"waiting for receiver verification",false);}
            case BUSINESS_ACK->{vm.acceptBusinessAck(frame,session,"file",fileId);yield new TransferCoordinator.Outcome(vm.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,total,new byte[0])),"receiver verified",false);}
            case FINAL_ACK->new TransferCoordinator.Outcome(null,"transfer complete",true);
            default->throw new IllegalStateException(m.type().toString());
        };};
        TransferCoordinator.FrameHandler receiverHandler=frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);return switch(m.type()){
            case INVITE->new TransferCoordinator.Outcome(host.acceptInviteAndReady(frame),"ready",false).replayable("WAIT_DATA_1");
            case DATA->{if(businessAck[0]!=null)yield new TransferCoordinator.Outcome(businessAck[0],"replayed verified reception",false).retrying("WAIT_FINAL_ACK").replayable("WAIT_FINAL_ACK");host.acceptData(frame,data->persisted.add(m.session()+":"+m.index()));businessAck[0]=host.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BUSINESS_ACK,0,total,new byte[]{1}));yield new TransferCoordinator.Outcome(businessAck[0],"verified reception",false).retrying("WAIT_FINAL_ACK").replayable("WAIT_FINAL_ACK");}
            case FINAL->{String ack=host.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL_ACK,0,total,new byte[0]));yield new TransferCoordinator.Outcome(ack,"received file complete",true);}
            default->throw new IllegalStateException(m.type().toString());
        };};
        sender.startSend(senderOwner,identity,inviteFrame,senderHandler,listener(senderDone,pending));
        receiver.startReceive(receiverOwner,identity,receiverHandler,listener(receiverDone,pending));
        receiverScheduler.runAll();senderScheduler.runAll();receiverScheduler.runAll();
        check(ReliableTransferEngine.decode(identity,key,clipboard.read()).type()==ReliableTransferEngine.Type.BUSINESS_ACK,"verified reception immediately confirms the final block");
        // Simulate that the verified-reception ACK was lost; sender repeats the same DATA.
        clipboard.write(dataFrame[0]);receiverScheduler.runAll();
        check(clipboard.read().equals(businessAck[0])&&persisted.size()==1,"last-block retry replays receive confirmation without duplicating inbox content");
        senderScheduler.runAll();receiverScheduler.runAll();senderScheduler.runAll();
        check(senderDone.get()==1&&receiverDone.get()==1&&pending.get()==0&&!sender.hasActiveTransfer()&&!receiver.hasActiveTransfer(),
                "verified file reaches FINAL/FINAL_ACK without waiting for a selected save target");
        clock.advance(181_000);senderScheduler.runAll();receiverScheduler.runAll();
        check(clipboard.read().isEmpty(),"fully completed file transfer releases its final clipboard response");
        sender.dispose();receiver.dispose();senderScheduler.shutdownNow();receiverScheduler.shutdownNow();
        deleteTree(vmWorkspace);deleteTree(hostWorkspace);
    }

    private static void clipboardStartupFailureReleasesLeaseAndCanRetry() {
        FailOnceClipboard clipboard=new FailOnceClipboard();clipboard.failNext=true;ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->0L,scheduler);AtomicInteger stopped=new AtomicInteger();java.util.List<String> statuses=new ArrayList<>();Object owner=new Object();
        TransferCoordinator.Listener listener=new TransferCoordinator.Listener(){public void onStatus(String status){statuses.add(status);}public void onStopped(TransferCoordinator.StopReason reason){if(reason==TransferCoordinator.StopReason.USER_PAUSED)stopped.incrementAndGet();}};
        coordinator.startSend(owner,"pair","pair INVITE",frame->new TransferCoordinator.Outcome(null,"unused",false),listener);
        check(!coordinator.hasActiveTransfer()&&stopped.get()==1&&scheduler.tasks.isEmpty(),"initial native clipboard write failure releases the lease and starts no polling");
        check(clipboard.writes==0&&statuses.stream().anyMatch(status->status.contains("已停止监听，可点击继续确认重试"))&&statuses.stream().noneMatch(status->status.contains("等待接收端准备")),"startup failure leaves the frame unpublished, avoids a false wait state, and reports a retry path");
        coordinator.startSend(owner,"pair","pair INVITE",frame->new TransferCoordinator.Outcome(null,"unused",false),listener);
        check(coordinator.hasActiveTransfer()&&clipboard.read().equals("pair INVITE"),"a fresh retry can start after the native adapter recovers");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void receiveInitializationFailureStopsButBusyClipboardRetries() {
        ManualScheduler initScheduler=new ManualScheduler();InitFailClipboard initClipboard=new InitFailClipboard();TransferCoordinator initCoordinator=new TransferCoordinator(initClipboard,Runnable::run,()->0L,initScheduler);
        AtomicInteger stopped=new AtomicInteger();java.util.List<String> initStatuses=new ArrayList<>();Object initOwner=new Object();
        initCoordinator.startReceive(initOwner,"pair",frame->new TransferCoordinator.Outcome(null,"unused",false),new TransferCoordinator.Listener(){public void onStatus(String status){initStatuses.add(status);}public void onStopped(TransferCoordinator.StopReason reason){if(reason==TransferCoordinator.StopReason.USER_PAUSED)stopped.incrementAndGet();}});
        initScheduler.runAll();
        check(!initCoordinator.hasActiveTransfer()&&stopped.get()==1&&initScheduler.tasks.stream().allMatch(task->task.cancelled),"fatal native read initialization failure releases receive lease and cancels polling");
        check(initStatuses.stream().anyMatch(status->status.contains("原生剪贴板初始化失败")),"fatal native initialization error is reported to the receiver");
        initCoordinator.dispose();initScheduler.shutdownNow();

        ManualScheduler busyScheduler=new ManualScheduler();BusyOnceClipboard busyClipboard=new BusyOnceClipboard();TransferCoordinator busyCoordinator=new TransferCoordinator(busyClipboard,Runnable::run,()->0L,busyScheduler);AtomicInteger busyStops=new AtomicInteger();
        busyCoordinator.startReceive(new Object(),"pair",frame->new TransferCoordinator.Outcome(null,"unused",false),listener(new AtomicInteger(),busyStops));
        busyScheduler.runAll();
        check(busyCoordinator.hasActiveTransfer()&&busyStops.get()==0,"temporary clipboard busy error does not terminate receive polling");
        busyScheduler.runAll();
        check(busyCoordinator.hasActiveTransfer()&&busyClipboard.readAttempts==2,"a later successful read resumes the receive listener");
        busyCoordinator.dispose();busyScheduler.shutdownNow();
    }

    private static void windowsPartialStartupStopsReceiveAndFreshBindingCanRetry() throws Exception {
        WindowsStartupBinding first=new WindowsStartupBinding(true),second=new WindowsStartupBinding(false);AtomicInteger created=new AtomicInteger();
        WindowsClipboardBackend backend=new WindowsClipboardBackend(()->created.getAndIncrement()==0?first:second);
        NativeClipboardAccess access=new NativeClipboardAccess(backend);ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(access,Runnable::run,()->0L,scheduler);
        AtomicInteger stopped=new AtomicInteger();List<String> statuses=new ArrayList<>();Object owner=new Object();
        coordinator.startReceive(owner,"pair",frame->new TransferCoordinator.Outcome(null,"unused",false),new TransferCoordinator.Listener(){public void onStatus(String status){statuses.add(status);}public void onStopped(TransferCoordinator.StopReason reason){if(reason==TransferCoordinator.StopReason.USER_PAUSED)stopped.incrementAndGet();}});
        scheduler.runAll();
        check(!coordinator.hasActiveTransfer()&&stopped.get()==1&&scheduler.tasks.stream().allMatch(task->task.cancelled),"Windows native startup error stops receive polling and releases its lease");
        check(first.starts==1&&first.stops==1&&statuses.stream().anyMatch(status->status.contains("CREATE_WINDOW")&&status.contains("Win32 1407")),"Windows failure status contains safe stage and Win32 code and partial startup is cleaned");
        check(backend.read()==null&&first.starts==1&&second.starts==1,"retry gets a fresh binding rather than restarting the failed message-loop thread");
        coordinator.dispose();scheduler.shutdownNow();backend.close();check(second.stops==1,"disposal closes the fresh message-loop binding");
    }

    private static void restoredFinalAckPublishesAsTerminalCompletion() throws Exception {
        Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->0L,scheduler);
        java.nio.file.Path workspace=java.nio.file.Files.createTempDirectory("restore-final-ack-");String session="00000000-0000-0000-0000-000000000031";List<TransferCoordinator.StatusEvent> statuses=new ArrayList<>();
        TransferCoordinator.Listener listener=new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStatus(TransferCoordinator.StatusEvent event){statuses.add(event);}public void onStopped(TransferCoordinator.StopReason reason){}};
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(workspace,"FILE_RECEIVER","restore-final-ack",frame->new TransferCoordinator.FrameInfo(session,"FINAL_ACK",0,3));
        coordinator.continueConfirmation(new Object(),"pair","pair FINAL_ACK",frame->new TransferCoordinator.Outcome(null,"unused",false),listener,context);
        check(clipboard.read().equals("pair FINAL_ACK"),"restored final acknowledgement remains published");
        check(!coordinator.hasActiveTransfer()&&statuses.stream().anyMatch(event->event.complete()&&event.eventKey().equals("FINAL_ACK_REPUBLISHED")&&event.text().contains("重发最终确认")),"restored final acknowledgement preserves terminal completion semantics and status");
        String log=java.nio.file.Files.readString(workspace.resolve(".vmsync/transfer-logs").resolve(session+".log"));
        check(log.indexOf("event=START")>=0&&log.indexOf("event=START")<log.indexOf("event=SEND")&&log.lines().filter(line->line.contains("event=SEND")).count()==1,"publication is logged as START before the successful SEND event");
        coordinator.dispose();scheduler.shutdownNow();deleteTree(workspace);
    }

    private static void restoredPublishFailureKeepsNativeStartupDetails() throws Exception {
        InitWriteFailureClipboard clipboard=new InitWriteFailureClipboard();ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->0L,scheduler);
        java.nio.file.Path workspace=java.nio.file.Files.createTempDirectory("restore-publish-failure-");String session="00000000-0000-0000-0000-000000000032";List<TransferCoordinator.StatusEvent> statuses=new ArrayList<>();
        TransferCoordinator.Listener listener=new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStatus(TransferCoordinator.StatusEvent event){statuses.add(event);}public void onStopped(TransferCoordinator.StopReason reason){}};
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(workspace,"FILE_RECEIVER","restore-failed-final-ack",frame->new TransferCoordinator.FrameInfo(session,"FINAL_ACK",0,3));
        coordinator.continueConfirmation(new Object(),"pair","pair FINAL_ACK",frame->new TransferCoordinator.Outcome(null,"unused",false),listener,context);
        check(!coordinator.hasActiveTransfer()&&statuses.stream().anyMatch(event->!event.complete()&&event.text().contains("CREATE_WINDOW")&&event.text().contains("CreateWindowExW")&&event.text().contains("Win32 1400")),"failed restore stops with actionable Windows startup stage, API, and code");
        String log=java.nio.file.Files.readString(workspace.resolve(".vmsync/transfer-logs").resolve(session+".log"));
        check(log.contains("startup_stage=CREATE_WINDOW")&&log.contains("startup_api=CreateWindowExW")&&log.contains("win32_error=1400")&&!log.contains("event=SEND")&&log.contains("event=START"),"failed initial publication has safe diagnostics and is never logged as SEND");
        coordinator.dispose();scheduler.shutdownNow();deleteTree(workspace);
    }

    private static void restoredFramesKeepTheirProtocolPhaseStatus() {
        assertRestoredStatus("pair DATA_2","DATA",2,"WAIT_ACK_DATA_2","等待第 2 块确认");
        assertRestoredStatus("pair BLOCK_ACK_3","BLOCK_ACK",3,"WAIT_DATA_COMPLETION","已接收 3 / 3 块");
        assertRestoredStatus("pair BLOCK_ACK_2","BLOCK_ACK",2,"WAIT_DATA_3","等待第 3 块");
        assertRestoredStatus("pair FINAL","FINAL",0,"WAIT_FINAL_ACK","等待最终确认");
    }
    private static void assertRestoredStatus(String frame,String type,int index,String expectedKey,String expectedText) {
        Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->0L,scheduler);List<TransferCoordinator.StatusEvent> statuses=new ArrayList<>();
        TransferCoordinator.Listener listener=new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStatus(TransferCoordinator.StatusEvent event){statuses.add(event);}public void onStopped(TransferCoordinator.StopReason reason){}};
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(null,"FILE_RECEIVER","restore-phase",ignored->new TransferCoordinator.FrameInfo(null,type,index,3));
        coordinator.continueConfirmation(new Object(),"pair",frame,ignored->new TransferCoordinator.Outcome(null,"unused",false),listener,context);
        check(statuses.stream().anyMatch(event->event.eventKey().equals(expectedKey)&&event.text().contains(expectedText)),"restored "+type+" frame reports its protocol phase");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void queuedUiCallbacksDoNotBlockProtocolProgress() throws Exception {
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        List<Runnable> uiCallbacks=new ArrayList<>();TransferCoordinator coordinator=new TransferCoordinator(clipboard,uiCallbacks::add,()->clock.now,scheduler);
        Object owner=new Object();AtomicInteger handled=new AtomicInteger(),stopped=new AtomicInteger();
        coordinator.startSend(owner,"pair","pair INVITE",frame->{handled.incrementAndGet();return new TransferCoordinator.Outcome("pair DATA_1","data",false).retrying("WAIT_ACK_DATA_1");},listener(new AtomicInteger(),stopped));
        clipboard.write("pair READY");scheduler.runAll();
        check(handled.get()==1&&clipboard.read().equals("pair DATA_1"),"protocol advances while the UI dispatcher retains every callback");
        check(!uiCallbacks.isEmpty(),"status callbacks were queued for the UI dispatcher");
        coordinator.dispose();scheduler.shutdownNow();
        uiCallbacks.clear();
        check(!coordinator.hasActiveTransfer(),"queued callback test releases coordinator ownership and clears retained callbacks");
    }

    private static TransferCoordinator.Listener listener(AtomicInteger success,AtomicInteger pending){
        return new TransferCoordinator.Listener(){public void onStatus(String ignored){}public void onStopped(TransferCoordinator.StopReason reason){if(reason==TransferCoordinator.StopReason.COMPLETED)success.incrementAndGet();else pending.incrementAndGet();}};
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void deleteTree(java.nio.file.Path root)throws Exception{try(var paths=java.nio.file.Files.walk(root)){for(java.nio.file.Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
    private static final class Clock {long now;void advance(long millis){now+=millis;}}
    private static void hostInputPulseIsOncePerFrameAndStopsWithSession(){
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();AtomicInteger pulses=new AtomicInteger();List<Boolean> directions=new ArrayList<>();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,left->{directions.add(left);pulses.incrementAndGet();return "sent";});
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(null,"FILE_SENDER","ui",f->new TransferCoordinator.FrameInfo("session","INVITE",0,21),()->false,true);
        Object owner=new Object();
        coordinator.startSend(owner,"pair","pair INVITE",frame->new TransferCoordinator.Outcome("pair DATA","next",false).retrying("WAIT_ACK_DATA_1"),new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStopped(TransferCoordinator.StopReason reason){}},context);
        clock.advance(900);scheduler.runAll();check(pulses.get()==0,"no early keys");
        clock.advance(100);scheduler.runAll();check(pulses.get()==1,"host pulses with logging off and UI dispatcher idle");
        clock.advance(2000);scheduler.runAll();scheduler.runAll();check(pulses.get()==2,"own frame wait continues keyboard assistance");
        clock.advance(1000);scheduler.runAll();check(pulses.get()==3,"next interval alternates keyboard without rewrite");
        clipboard.write("pair READY");scheduler.runAll();clock.advance(1100);scheduler.runAll();check(pulses.get()==4,"new frame can pulse");
        check(directions.equals(List.of(true,false,true,false)),"single keys alternate across retries and next frames");
        coordinator.endSession(owner);clock.advance(5000);scheduler.runAll();check(pulses.get()==4,"no keys after end");
        coordinator.dispose();scheduler.shutdownNow();
        ManualScheduler vmScheduler=new ManualScheduler();
        TransferCoordinator vm=new TransferCoordinator(new Clipboard(),r->{},()->clock.now,vmScheduler,left->{throw new AssertionError("VM must not inject keys");});
        vm.startSend(new Object(),"pair","pair INVITE",frame->null,new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStopped(TransferCoordinator.StopReason reason){}},new TransferCoordinator.LogContext(null,"FILE_SENDER","vm",f->null,()->false,false));
        clock.advance(3000);vmScheduler.runAll();vm.dispose();vmScheduler.shutdownNow();
    }

    private static void busyReadStillAllowsInputAssist(){
        Clock clock=new Clock();ManualScheduler scheduler=new ManualScheduler();AtomicInteger calls=new AtomicInteger();
        boolean[] busy={true};AtomicInteger writes=new AtomicInteger();
        TransferCoordinator.ClipboardAccess clipboard=new TransferCoordinator.ClipboardAccess(){
            public String read()throws Exception{if(busy[0])throw new java.io.IOException("clipboard busy");return "";}
            public void write(String value){writes.incrementAndGet();}
        };
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,left->{if(calls.get()<2)check(left,"skipped key keeps left direction");return calls.incrementAndGet()==1?"not_rdp":"sent";});
        Object owner=new Object();
        coordinator.startReceive(owner,"pair",frame->null,listener(new AtomicInteger(),new AtomicInteger()),
            new TransferCoordinator.LogContext(null,"FILE_RECEIVER","host",f->null,()->false,true));
        clock.advance(2000);scheduler.runAll();check(calls.get()==1,"busy reads must not starve input assistance");
        clock.advance(400);scheduler.runAll();check(calls.get()==1,"skipped input checks are throttled");
        clock.advance(1600);scheduler.runAll();check(calls.get()==2,"focus skip must preserve the key opportunity");
        clock.advance(3000);scheduler.runAll();check(calls.get()==3&&writes.get()==0,"busy reads permit spaced keys but never blindly publish");
        busy[0]=false;scheduler.runAll();check(coordinator.hasActiveTransfer(),"transient busy keeps listening");
        coordinator.endSession(owner);coordinator.dispose();scheduler.shutdownNow();
    }

    private static void retryRechecksClipboardAfterInput(){
        for(String pending:List.of("pair DATA_11","pair BUSINESS_ACK")){
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
            String peer=pending.contains("DATA")?"pair BLOCK_ACK_11":"pair FINAL";
            AtomicInteger handled=new AtomicInteger();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,left->{clipboard.write(peer);return "sent";});
            Object owner=new Object();
            coordinator.startSend(owner,"pair",pending,frame->{check(frame.equals(peer),"new peer must be processed");handled.incrementAndGet();return new TransferCoordinator.Outcome(null,"done",true);},
                listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"TEST","host",f->new TransferCoordinator.FrameInfo("session","CONTROL",0,1),()->false,true));
            clock.advance(3000);scheduler.runAll();
            check(clipboard.read().equals(peer),"retry must preserve peer frame arriving during key injection");
            scheduler.runAll();check(handled.get()==1,"preserved peer is processed on next poll");
            coordinator.dispose();scheduler.shutdownNow();
        }
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler);
        coordinator.startSend(new Object(),"pair","pair DATA",f->null,listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("");int writes=clipboard.writes;
        clock.advance(10000);scheduler.runAll();check(clipboard.writes==writes+1,"empty foreign slot can be recovered");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void retryUsesRevisionWithoutRereadingPayload(){
        Clock clock=new Clock();ManualScheduler scheduler=new ManualScheduler();AtomicInteger reads=new AtomicInteger();
        Clipboard clipboard=new Clipboard(){@Override public String read(){reads.incrementAndGet();return super.read();}};
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler);
        coordinator.startSend(new Object(),"pair","pair DATA",f->null,listener(new AtomicInteger(),new AtomicInteger()));
        clock.advance(3000);scheduler.runAll();
        check(reads.get()==1,"retry precheck must use revision rather than read the payload again");
        check(clipboard.writes==1,"own revision waits without rewriting");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private record PathPair(java.nio.file.Path sender,java.nio.file.Path receiver){}
    private static final class DelayedBridge {
        private final Clock clock;private final List<Delivery> deliveries=new ArrayList<>();private final EndpointClipboard sender=new EndpointClipboard("sender"),receiver=new EndpointClipboard("receiver");
        DelayedBridge(Clock clock){this.clock=clock;sender.bridge=this;receiver.bridge=this;}
        EndpointClipboard sender(){return sender;}EndpointClipboard receiver(){return receiver;}
        synchronized void publish(EndpointClipboard from,String value){long delay=from.name.equals("sender")?3_000:5_000;deliveries.add(new Delivery(clock.now+delay,from==sender?receiver:sender,value));}
        synchronized void deliver(){deliveries.sort(java.util.Comparator.comparingLong(Delivery::at));while(!deliveries.isEmpty()&&deliveries.get(0).at<=clock.now){Delivery d=deliveries.remove(0);d.to.value=d.value;d.to.revision++;}}
        synchronized int pending(){return deliveries.size();}
        private record Delivery(long at,EndpointClipboard to,String value){}
    }
    private static final class EndpointClipboard implements TransferCoordinator.ClipboardAccess {
        final String name;String value="";long revision,writeRevision;public long revision(){return revision;}public long lastWriteRevision(){return writeRevision;}DelayedBridge bridge;EndpointClipboard(String name){this.name=name;}
        public String read(){return value;}public void write(String next){value=next;revision++;writeRevision=revision;bridge.publish(this,next);}
    }
    private static class Clipboard implements TransferCoordinator.ClipboardAccess {
        String value="";int writes;long revision;public long revision(){return revision;}public long lastWriteRevision(){return revision;}public String read(){return value;}public void write(String next){value=next;writes++;revision++;}
    }
    private static final class FailOnceClipboard extends Clipboard {
        boolean failNext;
        @Override public void write(String next){if(failNext){failNext=false;throw new IllegalStateException("simulated clipboard failure");}super.write(next);}
    }
    private static final class InitFailClipboard implements TransferCoordinator.ClipboardAccess {
        public String read()throws Exception{throw new NativeClipboardAccess.InitializationFailure("native symbol missing");}
        public void write(String value){}
    }
    private static final class WindowsStartupBinding implements WindowsClipboardBackend.Bindings {
        final boolean fail;int starts,stops;WindowsStartupBinding(boolean fail){this.fail=fail;}
        public void start()throws Exception{starts++;if(fail)throw new WindowsClipboardBackend.StartupFailure(WindowsClipboardBackend.StartupStage.CREATE_WINDOW,"CreateWindowExW",1407,new IllegalStateException("sensitive failure message"));}
        public void stop(){stops++;}public boolean open(){return true;}public void closeClipboard(){}public Object allocate(String text){return text;}public void free(Object memory){}public void empty(){}public void set(Object memory){}public String read(long maxBytes){return null;}public String backend(){return "Windows";}
    }
    private static final class InitWriteFailureClipboard implements TransferCoordinator.ClipboardAccess {
        public String read(){return "";}
        public void write(String value){throw new IllegalStateException("private clipboard detail",new NativeClipboardAccess.InitializationFailure("native init",new WindowsClipboardBackend.StartupFailure(WindowsClipboardBackend.StartupStage.CREATE_WINDOW,"CreateWindowExW",1400,new IllegalStateException("private native detail"))));}
    }
    private static final class BusyOnceClipboard implements TransferCoordinator.ClipboardAccess {
        int readAttempts;public String read()throws Exception{if(++readAttempts==1)throw new java.io.IOException("clipboard busy");return "";}public void write(String value){}
    }
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<Tick> tasks=new ArrayList<>();
        ManualScheduler(){super(1);setRemoveOnCancelPolicy(true);}
        @Override public void execute(Runnable command){command.run();}
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,long initial,long delay,TimeUnit unit){Tick task=new Tick(command);tasks.add(task);return task;}
        void runAll(){for(Tick task:List.copyOf(tasks))if(!task.cancelled)task.command.run();}
        @Override public List<Runnable> shutdownNow(){tasks.clear();return super.shutdownNow();}
    }
    private static final class Tick implements ScheduledFuture<Object> {
        final Runnable command;boolean cancelled;Tick(Runnable command){this.command=command;}
        public long getDelay(TimeUnit unit){return 0;}public int compareTo(Delayed other){return 0;}
        public boolean cancel(boolean mayInterruptIfRunning){cancelled=true;return true;}
        public boolean isCancelled(){return cancelled;}public boolean isDone(){return cancelled;}
        public Object get(){return null;}public Object get(long timeout,TimeUnit unit){return null;}
    }
}
