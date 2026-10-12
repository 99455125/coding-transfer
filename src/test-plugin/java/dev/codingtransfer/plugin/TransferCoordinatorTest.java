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
        terminalRecoversAuthenticatedOldSessionFrame();
        receiverOwnsRetries();
        handlerFailureActuallyPauses();
        fixedClockSlotsAndPeerProtection();
        delayedOwnEchoDoesNotStrandCurrentBlock();
        ownSlotWaitsWithoutRewriting();
        onlyUnprocessableFramesCanBeRecovered();
        pendingResponseRecoversFromInvalidPeer();
        configuredFocusDelayStartsAfterWrite();
        hostFocusPulseIsOncePerFrameAndStopsWithSession();
        busyReadStillAllowsFocusAssist();
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

    private static void terminalRecoversAuthenticatedOldSessionFrame()throws Exception {
        for(String staleType:List.of("DATA","REQUEST","OTHER_SESSION","USER_TEXT")) {
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
            byte[] key=new byte[32];String session=java.util.UUID.randomUUID().toString();
            String fin=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","one",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,1,new byte[0]));
            String ack=ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(session,"file","one",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL_ACK,0,1,new byte[0]));
            TransferCoordinator.FrameDescriber describe=frame->{var m=ReliableTransferEngine.decode("pair",key,frame);return new TransferCoordinator.FrameInfo(m.session(),m.type().name(),m.index(),m.total());};
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",frame->new TransferCoordinator.Outcome(ack,"done",true),listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"FILE_RECEIVER","terminal",describe));
            clipboard.write(fin);scheduler.runAll();
            String stale=staleType.equals("USER_TEXT")?"ordinary text":ReliableTransferEngine.encode("pair",key,new ReliableTransferEngine.Message(staleType.equals("OTHER_SESSION")?java.util.UUID.randomUUID().toString():session,"file","one",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,staleType.equals("REQUEST")?ReliableTransferEngine.Type.REQUEST:ReliableTransferEngine.Type.DATA,1,1,staleType.equals("REQUEST")?new byte[0]:new byte[]{1}));
            clipboard.write(stale);clock.advance(10000);scheduler.runAll();
            check(clipboard.read().equals(staleType.equals("DATA")||staleType.equals("REQUEST")?ack:stale),"terminal retry recovers old session frames and preserves foreign content: "+staleType);
            coordinator.dispose();scheduler.shutdownNow();
        }
    }

    private static void receiverOwnsRetries() {
        for(String phase:List.of("WAIT_BUSINESS_CONFIRMATION","WAIT_FILE_VERIFIED")) {
            Clock legacyClock=new Clock();Clipboard legacyClipboard=new Clipboard();ManualScheduler legacyScheduler=new ManualScheduler();
            TransferCoordinator legacy=new TransferCoordinator(legacyClipboard,Runnable::run,()->legacyClock.now,legacyScheduler);
            legacy.startSend(new Object(),"pair","pair INVITE",frame->frame.equals("pair FINAL_BLOCK_ACK")?new TransferCoordinator.Outcome(null,"waiting receipt",false).phase(phase):new TransferCoordinator.Outcome("pair DATA_LAST","data",false).retrying("WAIT_ACK_DATA_3"),listener(new AtomicInteger(),new AtomicInteger()));
            legacyClipboard.write("pair READY");legacyScheduler.runAll();legacyClipboard.write("pair FINAL_BLOCK_ACK");legacyScheduler.runAll();legacyClipboard.write("pair DATA_LAST");int legacyWrites=legacyClipboard.writes;
            legacyClock.advance(10000);legacyScheduler.runAll();
            check(legacyClipboard.writes==legacyWrites,"legacy final ACK transitions to passive receipt wait: "+phase);
            legacy.dispose();legacyScheduler.shutdownNow();
        }
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
        final Clock senderClock=clock;
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->senderClock.now,scheduler);
        coordinator.startReceive(new Object(),"pair",frame->new TransferCoordinator.Outcome("pair DATA1","response",false).retrying("WAIT_ACK_DATA_1"),listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair REQUEST1");scheduler.runAll();int writes=clipboard.writes;
        for(int i=0;i<30;i++){clock.advance(1000);scheduler.runAll();}
        check(clipboard.writes==writes,"DATA must never retry autonomously even when a legacy outcome enables retry");
        clipboard.write("foreign");writes=clipboard.writes;clock.advance(10000);scheduler.runAll();
        check(clipboard.writes==writes,"foreign clipboard content cannot trigger autonomous DATA publication");
        coordinator.dispose();scheduler.shutdownNow();
        clock=new Clock();clipboard=new Clipboard();scheduler=new ManualScheduler();final Clock receiverClock=clock;
        coordinator=new TransferCoordinator(clipboard,Runnable::run,()->receiverClock.now,scheduler);
        coordinator.startReceive(new Object(),"pair",frame->new TransferCoordinator.Outcome("pair REQUEST1","request",false).retrying("WAIT_DATA_1"),listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair INVITE");scheduler.runAll();writes=clipboard.writes;
        clock.advance(10000);scheduler.runAll();check(clipboard.writes==writes+1,"receiver retries compact first request on a relative timeout");
        clipboard.write("pair INVITE");scheduler.runAll();writes=clipboard.writes;
        clock.advance(10000);scheduler.runAll();check(clipboard.writes==writes+1,"duplicate invite replay must not disable request retries");
        coordinator.dispose();scheduler.shutdownNow();
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
        for(long offset:List.of(0L,59_000L,1_234_567L)){
            Clock clock=new Clock();clock.now=offset;Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();AtomicInteger paused=new AtomicInteger(),handled=new AtomicInteger();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",f->{handled.incrementAndGet();return new TransferCoordinator.Outcome("pair REQUEST2","waiting",false).retrying("WAIT_DATA_2");},listener(new AtomicInteger(),paused));
            clipboard.write("pair DATA1");scheduler.runAll();int previous=clipboard.writes;
            for(int second=1;second<=80;second++){
                clock.advance(1000);scheduler.runAll();boolean due=second%10==0;
                check(clipboard.writes==previous+(due?1:0),"only receiver timeout may publish at "+second+" offset "+offset);
                previous=clipboard.writes;scheduler.runAll();check(clipboard.writes==previous,"no duplicate timeout publication");
            }
            clock.advance(10000);scheduler.runAll();check(paused.get()==1,"eight request retries preserve final response window before pause");
            check(handled.get()==1,"request retries do not repeat business processing");coordinator.dispose();scheduler.shutdownNow();
        }
        for(String race:List.of("peer","revision")){
            Clock clock=new Clock();AtomicInteger reads=new AtomicInteger();
            Clipboard clipboard=new Clipboard(){@Override public String read(){
                if(clock.now==10000&&reads.incrementAndGet()==2){if(race.equals("peer"))value="pair DATA2";revision++;}
                return value;
            }};
            ManualScheduler scheduler=new ManualScheduler();TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.startReceive(new Object(),"pair",f->new TransferCoordinator.Outcome("pair REQUEST2","waiting",false).retrying("WAIT_DATA_2"),listener(new AtomicInteger(),new AtomicInteger()));
            clipboard.write("pair DATA1");scheduler.runAll();int before=clipboard.writes;
            clock.now=10000;scheduler.runAll();check(clipboard.writes==before,"new peer or revision change prevents request retry: "+race);
            coordinator.dispose();scheduler.shutdownNow();
        }
    }

    private static void ownSlotWaitsWithoutRewriting(){
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();List<Boolean> keys=new ArrayList<>();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,()->{keys.add(true);return "switched";});
        Object owner=new Object();
        coordinator.startSend(owner,"pair","pair DATA",f->null,listener(new AtomicInteger(),new AtomicInteger()),
            new TransferCoordinator.LogContext(null,"TEST","host",f->new TransferCoordinator.FrameInfo("session","CONTROL",0,1),()->false,true,1000));
        for(int i=0;i<6;i++){clock.advance(1000);scheduler.runAll();}
        check(clipboard.writes==1,"our current slot must never be periodically rewritten");
        check(keys.size()==2,"one-second first switch and three-second observation do not rewrite clipboard");
        clipboard.write("foreign content");int writes=clipboard.writes;clock.advance(10000);scheduler.runAll();
        check(clipboard.writes==writes+1&&clipboard.read().equals("pair DATA"),"bootstrap recovery handles an unprocessable slot");
        coordinator.awaitUserAction(owner,"save");clipboard.write("ordinary");clock.advance(5000);scheduler.runAll();
        check(clipboard.read().equals("ordinary"),"manual action wait never overwrites");
        coordinator.dispose();scheduler.shutdownNow();
    }

    private static void delayedOwnEchoDoesNotStrandCurrentBlock(){
        Clock clock=new Clock();AtomicInteger arrivalRead=new AtomicInteger(-1);
        Clipboard clipboard=new Clipboard(){@Override public String read(){if(arrivalRead.get()>0&&arrivalRead.decrementAndGet()==0){value="pair NEW_PEER";revision++;}return super.read();}};
        ManualScheduler scheduler=new ManualScheduler();AtomicInteger handled=new AtomicInteger();
        String stale="pair REQUEST1",current="pair REQUEST2";
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
        coordinator.startReceive(new Object(),"pair",frame->{if(frame.equals(stale))throw new TransferCoordinator.OwnFrame();handled.incrementAndGet();return new TransferCoordinator.Outcome(current,"waiting",false).retrying("WAIT_DATA_2");},listener(new AtomicInteger(),new AtomicInteger()));
        clipboard.write("pair PEER");scheduler.runAll();clock.now=500;clipboard.write(stale);scheduler.runAll();int writes=clipboard.writes;
        clock.now=10000;scheduler.runAll();check(clipboard.writes==writes+1&&clipboard.read().equals(current),"delayed request echo recovers the current request");
        check(handled.get()==1,"recovering an old request never repeats business processing");
        clipboard.write(stale);writes=clipboard.writes;arrivalRead.set(2);clock.now=20000;scheduler.runAll();
        check(clipboard.writes==writes&&clipboard.read().equals("pair NEW_PEER"),"newer DATA arriving during reread survives request retry");
        scheduler.runAll();check(handled.get()==2,"new peer processed next poll");coordinator.dispose();scheduler.shutdownNow();
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
        for(long[] delays:new long[][]{{3000,5000},{1000,2000},{5000,3000},{7000,7000}})
            delayedRdpBridge(delays[0],delays[1]);
    }

    private static void delayedRdpBridge(long senderDelay,long receiverDelay) throws Exception {
        Clock clock=new Clock();DelayedBridge bridge=new DelayedBridge(clock);EndpointClipboard senderClipboard=bridge.sender(),receiverClipboard=bridge.receiver();
        bridge.senderDelay=senderDelay;bridge.receiverDelay=receiverDelay;
        ManualScheduler senderScheduler=new ManualScheduler(),receiverScheduler=new ManualScheduler();
        TransferCoordinator sender=new TransferCoordinator(senderClipboard,Runnable::run,()->clock.now,senderScheduler);
        TransferCoordinator receiver=new TransferCoordinator(receiverClipboard,Runnable::run,()->clock.now,receiverScheduler);
        PathPair paths=new PathPair(java.nio.file.Files.createTempDirectory("rdp-sender-"),java.nio.file.Files.createTempDirectory("rdp-receiver-"));
        byte[] key=new byte[32];java.util.Arrays.fill(key,(byte)23);String identity="rdp",session=java.util.UUID.randomUUID().toString();int total=3;
        java.util.Set<String> dropped=new java.util.HashSet<>();
        bridge.drop=frame->{try{return dropped.add(ReliableTransferEngine.decode(identity,key,frame).type().name());}catch(Exception invalid){return false;}};
        ReliableTransferEngine tx=ReliableTransferEngine.sender(identity,key,new TransferSessionStore(paths.sender));
        ReliableTransferEngine rx=ReliableTransferEngine.receiver(identity,key,new TransferSessionStore(paths.receiver));
        String invite=tx.prepare(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,total,new byte[0]));
        int[] counts=new int[5];java.util.List<Integer> sent=new java.util.ArrayList<>();String[] data={null};java.util.Set<Integer> durable=new java.util.HashSet<>();
        TransferCoordinator.FrameDescriber describe=frame->{var m=ReliableTransferEngine.decode(identity,key,frame);return new TransferCoordinator.FrameInfo(m.session(),m.type().name(),m.index(),m.total());};
        sender.startSend(new Object(),identity,invite,frame->{var m=ReliableTransferEngine.decode(identity,key,frame);switch(m.type()){
            case REQUEST->{int i=tx.acceptRequest(frame);data[0]=tx.prepare(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,i,total,new byte[]{(byte)i}));if(!sent.contains(i))sent.add(i);return new TransferCoordinator.Outcome(data[0],"data "+i,false).phase("WAIT_ACK_DATA_"+i);}
            case BUSINESS_ACK->{tx.acceptBusinessAck(frame,session,"file","one-file");return new TransferCoordinator.Outcome(tx.prepareCompletion(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,total,new byte[0])),"final",false).phase("WAIT_FINAL_ACK");}
            case FINAL_ACK-> {return new TransferCoordinator.Outcome(null,"done",true);}
            case DATA,INVITE,FINAL->throw new TransferCoordinator.OwnFrame();
            default->throw new TransferCoordinator.UnprocessableFrame("unexpected sender frame "+m.type());
        }},listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"FILE_SENDER","send",describe));
        String[] completion={null};
        receiver.startReceive(new Object(),identity,frame->{var m=ReliableTransferEngine.decode(identity,key,frame);switch(m.type()){
            case INVITE->{counts[2]++;var result=rx.acceptPullInviteResult(frame);if(result.readyFrame()==null)return new TransferCoordinator.Outcome(null,"late invite",false);return new TransferCoordinator.Outcome(result.readyFrame(),"request",false).retrying("WAIT_DATA_"+ReliableTransferEngine.decode(identity,key,result.readyFrame()).index());}
            case DATA->{counts[3]++;if(completion[0]!=null)return new TransferCoordinator.Outcome(completion[0],"receipt",false).retrying("WAIT_FINAL");String response=rx.acceptRequestedData(frame,block->durable.add(block.index()));if(durable.size()==total){completion[0]=rx.prepareCompletion(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BUSINESS_ACK,0,total,new byte[0]));return new TransferCoordinator.Outcome(completion[0],"receipt",false).retrying("WAIT_FINAL");}return new TransferCoordinator.Outcome(response,"request",false).retrying("WAIT_DATA_"+ReliableTransferEngine.decode(identity,key,response).index());}
            case FINAL->{return new TransferCoordinator.Outcome(rx.prepareCompletion(new ReliableTransferEngine.Message(session,"file","one-file",ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL_ACK,0,total,new byte[0])),"done",true);}
            case REQUEST,BUSINESS_ACK,BLOCK_ACK,FINAL_ACK->throw new TransferCoordinator.OwnFrame();
            default->throw new TransferCoordinator.UnprocessableFrame("unexpected receiver frame "+m.type());
        }},listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"FILE_RECEIVER","receive",describe));
        for(int i=0;i<900&&(sender.hasActiveTransfer()||receiver.hasActiveTransfer());i++){clock.advance(400);bridge.deliver();senderScheduler.runAll();receiverScheduler.runAll();bridge.deliver();}
        check(dropped.containsAll(java.util.Set.of("INVITE","REQUEST","DATA","BUSINESS_ACK","FINAL","FINAL_ACK")),"lost-frame probe covers bootstrap, block and every completion stage");
        check(counts[3]>=3&&durable.equals(java.util.Set.of(1,2,3)),"delayed bridge durably delivers all blocks at "+senderDelay+"/"+receiverDelay+" ms");
        check(sent.equals(java.util.List.of(1,2,3))&&tx.nextIndex(session)==4,"sender cursor advances once per receiver request at "+senderDelay+"/"+receiverDelay+" ms");
        check(!sender.hasActiveTransfer()&&!receiver.hasActiveTransfer(),"pull completion survives delayed transport at "+senderDelay+"/"+receiverDelay);
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
        int terminalWrites=clipboard.writes;clock.advance(10000);scheduler.runAll();
        check(clipboard.writes==terminalWrites+1&&!coordinator.hasActiveTransfer(),"receiver retries lost final confirmation without retaining UI ownership");
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
            case REQUEST->{vm.acceptRequest(frame);dataFrame[0]=vm.prepare(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,1,total,new byte[]{4,5,6}));yield new TransferCoordinator.Outcome(dataFrame[0],"sending final block",false);}
            case BLOCK_ACK->{vm.acceptBlockAck(frame);yield new TransferCoordinator.Outcome(null,"waiting for receiver verification",false);}
            case BUSINESS_ACK->{vm.acceptBusinessAck(frame,session,"file",fileId);yield new TransferCoordinator.Outcome(vm.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,total,new byte[0])),"receiver verified",false);}
            case FINAL_ACK->new TransferCoordinator.Outcome(null,"transfer complete",true);
            default->throw new IllegalStateException(m.type().toString());
        };};
        TransferCoordinator.FrameHandler receiverHandler=frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);return switch(m.type()){
            case INVITE->new TransferCoordinator.Outcome(host.acceptPullInviteResult(frame).readyFrame(),"request",false).retrying("WAIT_DATA_1");
            case DATA->{if(businessAck[0]!=null)yield new TransferCoordinator.Outcome(businessAck[0],"replayed verified reception",false).retrying("WAIT_FINAL").replayable("WAIT_FINAL");host.acceptRequestedData(frame,data->persisted.add(m.session()+":"+m.index()));businessAck[0]=host.prepareCompletion(new ReliableTransferEngine.Message(session,"file",fileId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.BUSINESS_ACK,0,total,new byte[]{1}));yield new TransferCoordinator.Outcome(businessAck[0],"verified reception",false).retrying("WAIT_FINAL").replayable("WAIT_FINAL");}
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
        for(String type:List.of("DATA","FINAL")) {
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();clipboard.write("user text");ManualScheduler scheduler=new ManualScheduler();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,Runnable::run,()->clock.now,scheduler);
            coordinator.continueConfirmation(new Object(),"pair","pair "+type,frame->new TransferCoordinator.Outcome(null,"unused",false),listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"FILE_SENDER","restore",frame->new TransferCoordinator.FrameInfo(null,type,1,3)));
            int writes=clipboard.writes;clock.advance(120000);scheduler.runAll();check(clipboard.writes==writes&&clipboard.read().equals("user text"),"restored sender waits passively for receiver: "+type);
            coordinator.dispose();scheduler.shutdownNow();
        }
        assertRestoredStatus("pair REQUEST_2","REQUEST",2,"WAIT_DATA_2","等待第 2 块");
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
    private static void configuredFocusDelayStartsAfterWrite(){
        for(int delay:new int[]{300,750}){
            Clock clock=new Clock();ManualScheduler scheduler=new ManualScheduler();AtomicInteger pulses=new AtomicInteger();
            Clipboard clipboard=new Clipboard(){@Override public void write(String value){super.write(value);clock.advance(25);}};
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,()->{pulses.incrementAndGet();return "switched";});
            Object owner=new Object();coordinator.startSend(owner,"pair","pair DATA",f->null,listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"TEST","configured",f->null,()->false,true,delay));
            clock.advance(delay-1);scheduler.runAll();check(pulses.get()==0,"configured delay starts after successful write returns");clock.advance(1);scheduler.runAll();check(pulses.get()==1,"configured focus delay reaches coordinator");
            coordinator.endSession(owner);coordinator.dispose();scheduler.shutdownNow();
        }
    }
    private static void hostFocusPulseIsOncePerFrameAndStopsWithSession(){
        Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();AtomicInteger pulses=new AtomicInteger();
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,()->{pulses.incrementAndGet();return "switched";});
        TransferCoordinator.LogContext context=new TransferCoordinator.LogContext(null,"FILE_SENDER","ui",f->new TransferCoordinator.FrameInfo("session","INVITE",0,21),()->false,true,1000);
        Object owner=new Object();
        coordinator.startSend(owner,"pair","pair INVITE",frame->new TransferCoordinator.Outcome("pair DATA","next",false).retrying("WAIT_ACK_DATA_1"),new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStopped(TransferCoordinator.StopReason reason){}},context);
        clock.advance(999);scheduler.runAll();check(pulses.get()==0,"no focus switch before first publication settles");
        clock.advance(1);scheduler.runAll();check(pulses.get()==1,"host switches after one second with logging off and UI dispatcher idle");
        clock.advance(2999);scheduler.runAll();check(pulses.get()==1,"switch leaves three seconds for clipboard propagation");
        clock.advance(1);scheduler.runAll();scheduler.runAll();check(pulses.get()==2,"second switch after observation window");
        clock.advance(3000);scheduler.runAll();check(pulses.get()==3,"third switch without rewriting data");
        clock.advance(3000);scheduler.runAll();check(pulses.get()==3,"same frame recovery bounded to three");
        clipboard.write("pair READY");scheduler.runAll();clock.advance(999);scheduler.runAll();check(pulses.get()==3,"next data starts its own one-second delay");clock.advance(1);scheduler.runAll();check(pulses.get()==4,"new data can switch after one second");

        coordinator.endSession(owner);clock.advance(5000);scheduler.runAll();check(pulses.get()==4,"no focus switching after end");
        coordinator.dispose();scheduler.shutdownNow();
        ManualScheduler vmScheduler=new ManualScheduler();
        TransferCoordinator vm=new TransferCoordinator(new Clipboard(),r->{},()->clock.now,vmScheduler,()->{throw new AssertionError("VM must not switch focus");});
        vm.startSend(new Object(),"pair","pair INVITE",frame->null,new TransferCoordinator.Listener(){public void onStatus(String text){}public void onStopped(TransferCoordinator.StopReason reason){}},new TransferCoordinator.LogContext(null,"FILE_SENDER","vm",f->null,()->false,false));
        clock.advance(3000);vmScheduler.runAll();vm.dispose();vmScheduler.shutdownNow();
    }

    private static void busyReadStillAllowsFocusAssist(){
        Clock clock=new Clock();ManualScheduler scheduler=new ManualScheduler();AtomicInteger calls=new AtomicInteger();
        boolean[] busy={true};AtomicInteger writes=new AtomicInteger();
        TransferCoordinator.ClipboardAccess clipboard=new TransferCoordinator.ClipboardAccess(){
            public String read()throws Exception{if(busy[0])throw new java.io.IOException("clipboard busy");return "";}
            public void write(String value){writes.incrementAndGet();}
        };
        TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,()->{return calls.incrementAndGet()==1?"not_rdp":"switched";});
        Object owner=new Object();
        coordinator.startReceive(owner,"pair",frame->null,listener(new AtomicInteger(),new AtomicInteger()),
            new TransferCoordinator.LogContext(null,"FILE_RECEIVER","host",f->null,()->false,true,1000));
        clock.advance(1000);scheduler.runAll();check(calls.get()==1,"busy reads must not starve first focus recovery");
        clock.advance(400);scheduler.runAll();check(calls.get()==1,"skipped focus checks are throttled");
        clock.advance(600);scheduler.runAll();check(calls.get()==2,"focus skip preserves opportunity after one second");
        clock.advance(2999);scheduler.runAll();check(calls.get()==2,"busy reads preserve observation window after successful switch");
        clock.advance(1);scheduler.runAll();check(calls.get()==3&&writes.get()==0,"busy reads permit spaced focus recovery but never blindly publish");
        busy[0]=false;scheduler.runAll();check(coordinator.hasActiveTransfer(),"transient busy keeps listening");
        coordinator.endSession(owner);coordinator.dispose();scheduler.shutdownNow();
    }

    private static void retryRechecksClipboardAfterInput(){
        for(String pending:List.of("pair DATA_11","pair BUSINESS_ACK")){
            Clock clock=new Clock();Clipboard clipboard=new Clipboard();ManualScheduler scheduler=new ManualScheduler();
            String peer=pending.contains("DATA")?"pair BLOCK_ACK_11":"pair FINAL";
            AtomicInteger handled=new AtomicInteger();
            TransferCoordinator coordinator=new TransferCoordinator(clipboard,r->{},()->clock.now,scheduler,()->{clipboard.write(peer);return "switched";});
            Object owner=new Object();
            coordinator.startSend(owner,"pair",pending,frame->{check(frame.equals(peer),"new peer must be processed");handled.incrementAndGet();return new TransferCoordinator.Outcome(null,"done",true);},
                listener(new AtomicInteger(),new AtomicInteger()),new TransferCoordinator.LogContext(null,"TEST","host",f->new TransferCoordinator.FrameInfo("session","CONTROL",0,1),()->false,true,1000));
            clock.advance(11000);scheduler.runAll();
            check(clipboard.read().equals(peer),"retry must preserve peer frame arriving during focus switching");
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
        long senderDelay=3000,receiverDelay=5000;
        private final Clock clock;private final List<Delivery> deliveries=new ArrayList<>();private final EndpointClipboard sender=new EndpointClipboard("sender"),receiver=new EndpointClipboard("receiver");
        DelayedBridge(Clock clock){this.clock=clock;sender.bridge=this;receiver.bridge=this;}
        EndpointClipboard sender(){return sender;}EndpointClipboard receiver(){return receiver;}
        java.util.function.Predicate<String> drop=frame->false;
        synchronized void publish(EndpointClipboard from,String value){if(drop.test(value))return;long delay=from.name.equals("sender")?senderDelay:receiverDelay;deliveries.add(new Delivery(clock.now+delay,from==sender?receiver:sender,value));}
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
