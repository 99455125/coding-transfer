package dev.codingtransfer.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import dev.codingtransfer.core.TransferSessionLease;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import java.nio.file.Path;

/** One application-level clipboard listener and lease shared by all Every Sync panels. */
public final class TransferCoordinator implements Disposable {
    private static final long POLL_MILLIS = 400;
    private static final long TERMINAL_RETENTION_MILLIS = 180_000;
    private static final int MAX_RETRIES = 8;
    private static final long RETRY_INTERVAL_MILLIS = 10_000;
    private static final long FINAL_RETRY_WAIT_MILLIS = 10_000;
    private final ScheduledThreadPoolExecutor worker;
    private final ClipboardAccess clipboard;
    @FunctionalInterface interface InputPulse {String send(boolean left) throws Exception;}
    private final InputPulse inputPulse;
    private final Consumer<Runnable> dispatcher;
    private final LongSupplier clock;
    private final Map<String,TerminalResponse> terminalResponses=new HashMap<>();
    private ScheduledFuture<?> terminalPollTask;
    private Active active;
    private boolean disposed;

    public record Outcome(String responseFrame, String status, boolean complete, boolean awaitUserAction,
                          String eventKey, boolean retryResponse, boolean replayResponseOnDuplicate) {
        public Outcome(String responseFrame,String status,boolean complete){this(responseFrame,status,complete,false,null,false,false);}
        public Outcome(String responseFrame,String status,boolean complete,boolean awaitUserAction){this(responseFrame,status,complete,awaitUserAction,null,false,false);}
        public Outcome phase(String key){return new Outcome(responseFrame,status,complete,awaitUserAction,key,retryResponse,replayResponseOnDuplicate);}
        public Outcome retrying(String key){return new Outcome(responseFrame,status,complete,awaitUserAction,key,true,replayResponseOnDuplicate);}
        public Outcome replayable(String key){return new Outcome(responseFrame,status,complete,awaitUserAction,key,retryResponse,true);}
    }
    public record StatusEvent(String sessionKey,String eventKey,String text,boolean complete) {}
    public record FrameInfo(String sessionId,String type,int index,int total) {}
    @FunctionalInterface public interface FrameDescriber { FrameInfo describe(String frame) throws Exception; }
    public record LogContext(Path workspace,String endpoint,String uiSessionKey,FrameDescriber describer,BooleanSupplier enabled,boolean hostInputAssist) {
        public LogContext(Path workspace,String endpoint,String uiSessionKey,FrameDescriber describer,BooleanSupplier enabled){this(workspace,endpoint,uiSessionKey,describer,enabled,false);}
        public LogContext(Path workspace,String endpoint,String uiSessionKey,FrameDescriber describer){this(workspace,endpoint,uiSessionKey,describer,()->true);}
    }
    public enum StopReason { COMPLETED, RETRY_EXHAUSTED, USER_PAUSED, USER_ENDED, DISPOSED }
    public static final class OwnFrame extends java.io.IOException {public OwnFrame(){super("本端帧回显");}}
    public static final class UnprocessableFrame extends java.io.IOException {public UnprocessableFrame(String message){super(message);}}
    public static dev.codingtransfer.core.ReliableTransferEngine.Message decodePeerFrame(String identity,byte[] key,String frame)throws UnprocessableFrame{
        try{return dev.codingtransfer.core.ReliableTransferEngine.decode(identity,key,frame);}
        catch(java.io.IOException|IllegalArgumentException invalid){throw new UnprocessableFrame("无法解码当前帧");}
    }
    @FunctionalInterface public interface FrameHandler { Outcome accept(String frame) throws Exception; }
    public interface Listener {
        void onStatus(String status);
        default void onStatus(StatusEvent status){onStatus(status.text());}
        void onStopped(StopReason reason);
    }
    public interface ClipboardAccess { String read() throws Exception; default String read(boolean diagnostics)throws Exception{return read();} default String readDiagnostics(){return "";} void write(String value) throws Exception; default String backendName(){return "custom";} default long revision()throws Exception{return -1;} default long lastWriteRevision(){return -1;} }
    private static final class TerminalResponse {
        final String identity,peerFrame,responseFrame;final long expiresAt;String lastObservedText;
        TerminalResponse(String identity,String peerFrame,String responseFrame,long now){this.identity=identity;this.peerFrame=peerFrame;this.responseFrame=responseFrame;lastObservedText=responseFrame;expiresAt=now+TERMINAL_RETENTION_MILLIS;}
    }

    private static final class Active {
        final TransferSessionLease lease = new TransferSessionLease();
        final TransferInputAssist inputAssist = new TransferInputAssist();
        boolean inputAssistFailed;
        String diagnosticText,diagnosticRoute;
        long diagnosticAt;
        final String identity;
        String currentFrame;
        String ownEchoFrame;
        long localWriteRevision=-1;
        String lastPeerFrame;
        long lastSentAt;
        int retries;
        long lastRetrySlot=Long.MIN_VALUE;
        boolean paused;
        boolean stopped;
        boolean awaitingUserAction;
        final String statusSessionKey;
        final LogContext logContext;
        TransferEventLog eventLog;
        String logSession;
        boolean loggingActive;
        String eventKey="WAIT_RESPONSE";
        boolean retryEnabled;
        boolean currentPublished;
        String pendingPeerFrame;
        Outcome pendingOutcome;
        String pendingResponse;
        int handlerFailures;
        long nextHandlerAttemptAt;
        String failedPeerFrame;
        String lastPeerResponse;
        boolean replayLastPeerResponse;
        long lastPollNanos,ioSummaryNanos,readCount,readTotalNanos,readMaxNanos,writeCount,writeTotalNanos,writeMaxNanos,pollGapTotalNanos,pollGapMaxNanos,pollGapCount;
        String lastObservedText,lastLocalOutput;
        String completionEventKey="COMPLETE";
        final java.util.ArrayDeque<String> deferredPeerFrames=new java.util.ArrayDeque<>();
        Active(String identity,String initial,LogContext logContext){
            this.identity=identity;currentFrame=initial;this.logContext=logContext;
            statusSessionKey=logContext==null||logContext.uiSessionKey()==null?java.util.UUID.randomUUID().toString():logContext.uiSessionKey();
            retryEnabled=initial!=null;
        }
    }

    public TransferCoordinator(){
        this(NativeClipboardAccess.create(),
                runnable->ApplicationManager.getApplication().invokeLater(runnable),System::currentTimeMillis,
                new ScheduledThreadPoolExecutor(1,r->{Thread thread=new Thread(r,"every-sync-transfer");thread.setDaemon(true);return thread;}));
    }

    public java.util.concurrent.CompletableFuture<String> readClipboardTextAsync(){
        java.util.concurrent.CompletableFuture<String> result=new java.util.concurrent.CompletableFuture<>();
        try{worker.execute(()->{try{result.complete(clipboard.read());}catch(Throwable error){result.completeExceptionally(error);}});}catch(RuntimeException error){result.completeExceptionally(error);}return result;
    }
    public java.util.concurrent.CompletableFuture<Void> writeClipboardTextAsync(String value){
        java.util.concurrent.CompletableFuture<Void> result=new java.util.concurrent.CompletableFuture<>();
        try{worker.execute(()->{try{clipboard.write(value);result.complete(null);}catch(Throwable error){result.completeExceptionally(error);}});}catch(RuntimeException error){result.completeExceptionally(error);}return result;
    }
    String clipboardBackendName(){return clipboard.backendName();}

    TransferCoordinator(ClipboardAccess clipboard,Consumer<Runnable> dispatcher,LongSupplier clock,ScheduledThreadPoolExecutor worker){
        this(clipboard,dispatcher,clock,worker,RdpKeyboardAssist::pulse);
    }
    TransferCoordinator(ClipboardAccess clipboard,Consumer<Runnable> dispatcher,LongSupplier clock,ScheduledThreadPoolExecutor worker,InputPulse inputPulse){
        this.inputPulse=inputPulse;this.clipboard=clipboard;this.dispatcher=dispatcher;this.clock=clock;this.worker=worker;
        worker.setRemoveOnCancelPolicy(true);
    }

    /** Explicit send start may replace the clipboard with the first INVITE frame. */
    public synchronized void startSend(Object owner,String identity,String initialFrame,FrameHandler handler,Listener listener){
        start(owner,identity,initialFrame,handler,listener,true,false,null);
    }
    public synchronized void startSend(Object owner,String identity,String initialFrame,FrameHandler handler,Listener listener,LogContext logContext){
        start(owner,identity,initialFrame,handler,listener,true,false,logContext);
    }

    /** Explicit receive start listens for the sender's already-published INVITE and replies READY. */
    public synchronized void startReceive(Object owner,String identity,FrameHandler handler,Listener listener){
        start(owner,identity,null,handler,listener,false,false,null);
    }
    public synchronized void startReceive(Object owner,String identity,FrameHandler handler,Listener listener,LogContext logContext){
        start(owner,identity,null,handler,listener,false,false,logContext);
    }

    private void start(Object owner,String identity,String initial,FrameHandler handler,Listener listener,boolean publish,boolean restoring,LogContext logContext){
        if(disposed)throw new IllegalStateException("传输协调器已关闭");
        if(active!=null&&!active.stopped)throw new IllegalStateException("已有一个项目/文件传输正在运行");
        if(owner==null||identity==null||!identity.matches("[A-Za-z0-9_-]{1,128}")||handler==null||listener==null)
            throw new IllegalArgumentException("传输会话参数无效");
        Active session=new Active(identity,initial,logContext);session.lease.bind(owner,handler,listener);active=session;session.inputAssist.arm(null,clock.getAsLong());
        InitialPresentation presentation=publish?initialPresentation(session,initial,restoring):new InitialPresentation("等待发送端开始","WAIT_INVITE","等待发送端开始",false,"COMPLETE");
        session.eventKey=presentation.waitEventKey();session.completionEventKey=presentation.completeEventKey();
        if(publish){
            post(session,"START_PUBLISH",presentation.startText());
            log(session,"START",initial,"state="+presentation.waitEventKey()+" publication=starting",null);
            worker.execute(()->{try{
                writeForSession(session,initial);session.lastSentAt=clock.getAsLong();session.currentPublished=true;
                log(session,"SEND",initial,"publication=complete",null);
                if(presentation.terminal())stopInternal(session,StopReason.COMPLETED,presentation.sentText(),true);
                else post(session,presentation.waitEventKey(),assistHint(session,presentation.sentText()));
            }catch(Throwable error){
                session.lastSentAt=clock.getAsLong();String startup=WindowsClipboardBackend.startupLogFields(error);
                log(session,"ERROR",initial,"classification=CLIPBOARD_WRITE"+(startup.isBlank()?"":" "+startup),error);
                WindowsClipboardBackend.StartupFailure windowsStartup=WindowsClipboardBackend.startupFailure(error);
                String status=windowsStartup==null?"无法启动传输："+message(error)+"；已停止监听，可点击继续确认重试":windowsStartup.safeSummary()+"；已停止监听，可点击继续确认重试";
                stopInternal(session,StopReason.USER_PAUSED,status);
            }});
        }else{post(session,"WAIT_INVITE",assistHint(session,restoring?"继续监听待确认消息":"等待发送端开始"));log(session,restoring?"LISTEN_RESTORE":"LISTEN",null,"state=WAIT_INVITE",null);}
        schedule(session);
    }

    private record InitialPresentation(String startText,String waitEventKey,String sentText,boolean terminal,String completeEventKey){}
    private InitialPresentation initialPresentation(Active session,String initial,boolean restoring){
        if(!restoring)return new InitialPresentation("正在发布传输邀请","WAIT_READY","等待接收端准备",false,"COMPLETE");
        FrameInfo info=describe(session,initial);if(info==null)return new InitialPresentation("正在重发待确认帧","WAIT_RESPONSE","继续确认中",false,"COMPLETE");
        return switch(info.type()){
            case "INVITE"->new InitialPresentation("正在重发传输邀请","WAIT_READY","等待接收端准备",false,"COMPLETE");
            case "DATA"->new InitialPresentation("正在重发第 "+info.index()+" 块","WAIT_ACK_DATA_"+info.index(),"继续确认中 · 等待第 "+info.index()+" 块确认",false,"COMPLETE");
            case "FINAL"->new InitialPresentation("正在重发最终帧","WAIT_FINAL_ACK","继续确认中 · 等待最终确认",false,"COMPLETE");
            case "FINAL_ACK"->new InitialPresentation("正在重发最终确认","FINAL_ACK_REPUBLISHED",TransferCompletionText.format("重发最终确认已发布，传输已完成",info.total()),true,"FINAL_ACK_REPUBLISHED");
            case "BLOCK_ACK"->info.index()==info.total()
                    ?new InitialPresentation("正在重发最后一块确认","WAIT_DATA_COMPLETION","已接收 "+info.total()+" / "+info.total()+" 块 · 继续完成校验、应用或确认",false,"COMPLETE")
                    :new InitialPresentation("正在重发第 "+info.index()+" 块确认","WAIT_DATA_"+(info.index()+1),"继续确认中 · 等待第 "+(info.index()+1)+" 块",false,"COMPLETE");
            case "READY"->new InitialPresentation("正在重发接收端就绪确认","WAIT_DATA_1","继续确认中 · 等待第 1 块",false,"COMPLETE");
            case "BUSINESS_ACK","RECEIVED"->new InitialPresentation("正在重发业务确认","WAIT_FINAL","继续确认中 · 等待最终帧",false,"COMPLETE");
            default->new InitialPresentation("正在重发待确认帧","WAIT_RESPONSE","继续确认中",false,"COMPLETE");
        };
    }

    private void schedule(Active session){
        if(session.stopped)return;
        ScheduledFuture<?> task=worker.scheduleWithFixedDelay(()->poll(session),POLL_MILLIS,POLL_MILLIS,TimeUnit.MILLISECONDS);
        session.lease.setPollTask(task);
    }

    /** Hold the lease without retrying while the receiver performs an explicit local action. */
    public synchronized void awaitUserAction(Object owner,String status){
        Active session=active;
        if(session==null||!session.lease.owns(owner)||session.stopped)throw new IllegalStateException("没有活动的传输");
        session.awaitingUserAction=true;post(session,status);
    }

    /** Explicit user action resumes bounded retries after the automatic grace period. */
    public synchronized void continueConfirmation(Object owner){
        Active session=active;
        if(session==null||!session.lease.owns(owner)||session.stopped)throw new IllegalStateException("没有待继续的传输");
        session.paused=false;session.retries=0;session.handlerFailures=0;session.nextHandlerAttemptAt=0;
        if(session.currentFrame!=null)worker.execute(()->{try{writeForSession(session,session.currentFrame);session.lastSentAt=clock.getAsLong();session.currentPublished=true;}catch(Throwable error){session.lastSentAt=clock.getAsLong();log(session,"ERROR",session.currentFrame,"classification=CONTINUE_WRITE",error);post(session,"CLIPBOARD_ERROR","继续确认失败：剪贴板写入失败");}});
        post(session,"继续确认中");
    }

    public synchronized void continueConfirmation(Object owner,String identity,String persistedFrame,
                                                  FrameHandler handler,Listener listener){
        continueConfirmation(owner,identity,persistedFrame,handler,listener,null);
    }
    public synchronized void continueConfirmation(Object owner,String identity,String persistedFrame,
                                                  FrameHandler handler,Listener listener,LogContext logContext){
        if(active!=null&&!active.stopped)throw new IllegalStateException("已有一个传输仍在运行");
        start(owner,identity,persistedFrame,handler,listener,true,true,logContext);
    }

    /** Reopen a persisted session whose sender has no frame pending while awaiting a business response. */
    public synchronized void continueListening(Object owner,String identity,FrameHandler handler,Listener listener){
        continueListening(owner,identity,handler,listener,null);
    }
    public synchronized void continueListening(Object owner,String identity,FrameHandler handler,Listener listener,LogContext logContext){
        if(active!=null&&!active.stopped)throw new IllegalStateException("已有一个传输仍在运行");
        start(owner,identity,null,handler,listener,false,true,logContext);post(active,"WAIT_INVITE","继续监听待确认消息");
    }

    public synchronized void stop(Object owner){
        Active session=active;
        if(session==null||!session.lease.owns(owner))return;
        stopInternal(session,StopReason.USER_PAUSED,"传输已暂停；可继续确认或结束本次会话");
    }
    public synchronized void endSession(Object owner){
        Active session=active;
        if(session==null||!session.lease.owns(owner))return;
        stopInternal(session,StopReason.USER_ENDED,"自动监听已结束；必要恢复数据仍保留");
    }

    /** Publish an explicit user-authorized business response for the active session. */
    public synchronized void sendResponse(Object owner,String responseFrame,String status){
        Active session=active;
        if(session==null||!session.lease.owns(owner)||session.stopped)throw new IllegalStateException("没有活动的传输");
        if(responseFrame==null||!responseFrame.startsWith(session.identity+" "))throw new IllegalArgumentException("响应帧身份不匹配");
        session.currentFrame=responseFrame;session.lastPeerFrame=null;session.retries=0;session.retryEnabled=true;session.currentPublished=false;session.paused=false;session.awaitingUserAction=false;session.eventKey="WAIT_CONFIRMATION";
        schedule(session);
        worker.execute(()->{try{writeForSession(session,responseFrame);session.lastSentAt=clock.getAsLong();session.currentPublished=true;log(session,"SEND",responseFrame,"state=WAIT_CONFIRMATION",null);}catch(Throwable e){session.lastSentAt=clock.getAsLong();log(session,"ERROR",responseFrame,"classification=CLIPBOARD_WRITE",e);post(session,"ERROR",status+" · 剪贴板发布失败");}});post(session,"WAIT_CONFIRMATION",status);
    }

    private void poll(Active session){
        synchronized(this){if(disposed||session.stopped||active!=session||(session.paused&&session.handlerFailures>=5))return;}
        recordPollStart(session);
        String text;
        try{text=readClipboard(session);}
        catch(Throwable e){
            String startupFields=WindowsClipboardBackend.startupLogFields(e);
            log(session,"ERROR",null,"classification="+(NativeClipboardAccess.isTerminalFailure(e)?"CLIPBOARD_NATIVE_TERMINAL":"CLIPBOARD_READ")+(startupFields.isBlank()?"":" "+startupFields),e);
            if(NativeClipboardAccess.isTerminalFailure(e)){
                String retry;
                WindowsClipboardBackend.StartupFailure windowsStartup=WindowsClipboardBackend.startupFailure(e);
                if(windowsStartup!=null)retry=windowsStartup.safeSummary()+"；监听已停止，可重试";
                else if(NativeClipboardAccess.isInitializationFailure(e))retry=session.currentFrame==null?"原生剪贴板初始化失败；监听已停止，可重新点击接收":"原生剪贴板初始化失败；监听已停止，可继续确认重试";
                else retry="原生剪贴板操作超时且结果不确定；监听已停止，确认本机剪贴板恢复后可继续确认";
                stopInternal(session,StopReason.USER_PAUSED,retry);
            }else {
                post(session,"CLIPBOARD_ERROR","读取剪贴板失败："+message(e));
                // A transient read failure must not starve input assistance. Do not publish
                // a retry without observing the clipboard: it may contain a peer response.
                maybeAssistInput(session,clock.getAsLong());
            }
            return;
        }
        if(text==null)text="";
        long now=clock.getAsLong();
        observeClipboard(session,text,now);
        if(session.pendingPeerFrame!=null){
            if(sameFrame(text,session.pendingPeerFrame)||sameFrame(text,session.lastLocalOutput))publishPendingResponse(session);
            else if(text.startsWith(session.identity+" "))processPeerFrame(session,text,now);
            else recoverSlot(session,text,now,false);
            session.lastObservedText=text;
            return;
        }
        if(!session.deferredPeerFrames.isEmpty()){
            String deferred=session.deferredPeerFrames.removeFirst();
            if(!deferred.equals(session.lastPeerFrame))processPeerFrame(session,deferred,now);
            session.lastObservedText=readClipboardQuietly(session,text);return;
        }
        if((session.currentFrame!=null&&sameFrame(text,session.currentFrame))||sameFrame(text,session.ownEchoFrame)){recoverSlot(session,text,now,true);maybeAssistInput(session,now);return;}
        if(sameFrame(text,session.lastPeerFrame)){
            if(session.replayLastPeerResponse&&session.lastPeerResponse!=null)replayResponse(session,text);
            session.lastObservedText=text;
            return;
        }
        if(text.startsWith(session.identity+" ")){processPeerFrame(session,text,now);session.lastObservedText=readClipboardQuietly(session,text);return;}
        recoverSlot(session,text,now,false);
        maybeAssistInput(session,now);
        session.lastObservedText=text;
    }

    /** Metadata-only snapshots; diagnostic state must never influence protocol decisions. */
    private void observeClipboard(Active session,String text,long now){
        if(session.logContext==null||!logsEnabled(session.logContext)){
            session.diagnosticText=null;session.diagnosticRoute=null;return;
        }
        String route;
        if(session.pendingPeerFrame!=null)
            route=sameFrame(text,session.pendingPeerFrame)||sameFrame(text,session.lastLocalOutput)?"PUBLISH_PENDING_RESPONSE":
                text.startsWith(session.identity+" ")?"PROCESS_PEER_PENDING":!text.isEmpty()?"RECOVER_UNPROCESSABLE":"PENDING_RESPONSE_EMPTY";
        else if(!session.deferredPeerFrames.isEmpty())route="PROCESS_DEFERRED";
        else if(sameFrame(text,session.currentFrame))route="WAIT_ON_LOCAL_FRAME";
        else if(sameFrame(text,session.lastPeerFrame))route=session.replayLastPeerResponse&&session.lastPeerResponse!=null?
            "REPLAY_DUPLICATE_ACK":"IGNORE_DUPLICATE_PEER";
        else if(text.startsWith(session.identity+" "))route=text.equals(session.failedPeerFrame)&&now<session.nextHandlerAttemptAt?"HANDLER_BACKOFF":"PROCESS_PEER";
        else route=text.isEmpty()?"WAIT_EMPTY":"RECOVER_UNPROCESSABLE";
        boolean changed=!sameFrame(text,session.diagnosticText);
        if(!changed&&route.equals(session.diagnosticRoute)&&now-session.diagnosticAt<10_000)return;
        session.diagnosticText=text;session.diagnosticRoute=route;session.diagnosticAt=now;
        String detail="changed="+changed+" route="+route+" content_kind="+(text.isEmpty()?"EMPTY":text.startsWith(session.identity+" ")?"PROTOCOL":"OTHER")
            +" equals_current="+sameFrame(text,session.currentFrame)+" equals_last_peer="+sameFrame(text,session.lastPeerFrame)
            +" equals_last_observed="+sameFrame(text,session.lastObservedText)+" replay_enabled="+session.replayLastPeerResponse
            +" retry_enabled="+session.retryEnabled+" retries="+session.retries+" paused="+session.paused
            +" awaiting_user="+session.awaitingUserAction+" response_pending="+(session.pendingResponse!=null);
        log(session,"CLIPBOARD_OBSERVE",text.startsWith(session.identity+" ")?text:null,detail,null);
    }

    private void processPeerFrame(Active session,String text,long now){
        if(text.equals(session.failedPeerFrame)&&now<session.nextHandlerAttemptAt)return;
        try{
            FrameHandler handler=(FrameHandler)session.lease.handler();if(handler==null)return;
            log(session,"RECEIVE",text,"",null);
            Outcome outcome=handler.accept(text);
            session.handlerFailures=0;session.failedPeerFrame=null;session.paused=false;
            if(outcome.responseFrame()!=null){session.pendingPeerFrame=text;session.pendingResponse=outcome.responseFrame();session.pendingOutcome=outcome;publishPendingResponse(session);}
            else{commitPeerFrame(session,text,outcome);applyOutcomeWithoutResponse(session,outcome);}
        }catch(OwnFrame own){
            session.ownEchoFrame=text;log(session,"IGNORE",null,"reason=own_frame_echo",null);maybeAssistInput(session,now);
        }catch(dev.codingtransfer.core.ReliableTransferEngine.RejectedFrame|UnprocessableFrame invalid){
            log(session,"IGNORE",null,"reason=unprocessable_peer",null);
            recoverSlot(session,text,now,false);
        }catch(Throwable e){
            session.failedPeerFrame=text;session.handlerFailures++;
            long delay=Math.min(5_000L,400L<<(Math.min(session.handlerFailures-1,3)));
            session.nextHandlerAttemptAt=now+delay;
            log(session,"ERROR",text,"classification=HANDLER_ATTEMPT_"+session.handlerFailures,e);
            if(session.handlerFailures>=5){session.paused=true;log(session,"PAUSE",text,"reason=HANDLER_FAILURES",null);stopInternal(session,StopReason.USER_PAUSED,"处理失败，已暂停："+failureSummary(e)+"；已接收数据保留，处理原因后点击继续确认",true);}
            else post(session,"HANDLER_RETRY","处理失败："+failureSummary(e)+"；将在 "+delay/1000.0+" 秒后重试");
        }
    }

    private void publishPendingResponse(Active session){publishPendingResponse(session,null);}
    private void publishPendingResponse(Active session,String foreign){
        String peer=session.pendingPeerFrame,response=session.pendingResponse;Outcome outcome=session.pendingOutcome;
        try{
            String latest=readClipboard(session);
            if(!sameFrame(peer,latest)&&!sameFrame(session.lastLocalOutput,latest)&&!(foreign!=null&&sameFrame(foreign,latest))){log(session,"IGNORE",peer,"reason=clipboard_changed_before_response_publication",null);return;}
            writeForSession(session,response);commitPeerFrame(session,peer,outcome);
            session.currentFrame=response;session.currentPublished=true;session.lastSentAt=clock.getAsLong();session.retries=0;
            session.retryEnabled=outcome.retryResponse()&&!outcome.awaitUserAction();
            session.awaitingUserAction=outcome.awaitUserAction();session.paused=false;
            session.eventKey=outcome.eventKey()==null?keyFor(response,session,"WAIT_CONFIRMATION"):outcome.eventKey();
            if(frameType(session,response,"SEND").equals("ACK"))log(session,"PERSIST",peer,"result=handler_completed",null);
            log(session,frameType(session,response,"SEND"),response,"publication=complete",null);
            session.pendingPeerFrame=null;session.pendingResponse=null;session.pendingOutcome=null;
            if(outcome.status()!=null)post(session,session.eventKey,outcome.status());
            if(outcome.complete()){
                retainTerminalResponse(session.identity,peer,response);log(session,"COMPLETE",response,"",null);
                stopInternal(session,StopReason.COMPLETED,outcome.status()==null?"业务操作已完成":outcome.status(),true);
            }
        }catch(Throwable e){
            session.lastSentAt=clock.getAsLong();log(session,"ERROR",response,"classification=RESPONSE_PUBLICATION_PENDING",e);
            post(session,"RESPONSE_PENDING","响应未发布，保留原处理结果并等待重试");
        }
    }

    private void commitPeerFrame(Active session,String peer,Outcome outcome){
        session.lastPeerFrame=peer;session.lastPeerResponse=outcome.responseFrame();
        session.replayLastPeerResponse=outcome.responseFrame()!=null;
        log(session,"RECEIVE_COMMIT",peer,"duplicate_response="+(session.replayLastPeerResponse?"replay":"ignore"),null);
    }

    private void applyOutcomeWithoutResponse(Active session,Outcome outcome){
        session.paused=false;session.awaitingUserAction=outcome.awaitUserAction();if(outcome.awaitUserAction())session.retryEnabled=false;
        session.eventKey=outcome.eventKey()==null?(outcome.awaitUserAction()?"WAIT_USER":"PHASE"):outcome.eventKey();
        if(outcome.status()!=null)post(session,session.eventKey,outcome.status());
        if(outcome.complete()){log(session,"COMPLETE",null,"",null);stopInternal(session,StopReason.COMPLETED,outcome.status()==null?"传输已完成":outcome.status());}
    }

    private void replayResponse(Active session,String peer){
        try{
            if(!sameFrame(peer,readClipboard(session)))return;
            writeForSession(session,session.lastPeerResponse);session.currentFrame=session.lastPeerResponse;session.currentPublished=true;
            session.lastSentAt=clock.getAsLong();session.retryEnabled=false;session.retries=0;
            log(session,"ACK",session.lastPeerResponse,"reason=duplicate_peer_frame",null);
        }catch(Throwable e){log(session,"ERROR",session.lastPeerResponse,"classification=ACK_REPLAY",e);}
    }

    private static String assistHint(Active session,String text){return session.logContext!=null&&session.logContext.hostInputAssist()?text+"；请保持远端 VM 窗口在前台且勿操作，等待时会辅助发送一个方向键，←、→ 交替":text;}

    private void maybeAssistInput(Active session,long now){
        if(session.logContext==null||!session.logContext.hostInputAssist()||session.inputAssistFailed)return;
        synchronized(this){
            if(active!=session||session.stopped||session.paused||session.awaitingUserAction)return;
            if(!session.inputAssist.claim(now,true))return;
            try{
                boolean left=session.inputAssist.nextLeft();
                String result=inputPulse.send(left);
                if(result.equals("sent")){session.inputAssist.sent();session.inputAssist.defer(clock.getAsLong());}
                log(session,"INPUT_ASSIST",null,"result="+result+" direction="+(left?"LEFT":"RIGHT"),null);
                if(result.equals("user_active")||result.equals("not_rdp")||result.equals("no_window")||result.equals("focus_changed"))
                    session.inputAssist.defer(clock.getAsLong());
                if(result.equals("permission_or_os_error")||result.equals("timeout")||result.equals("os_error")){
                    session.inputAssistFailed=true;
                    post(session,"INPUT_ASSIST_UNAVAILABLE","方向键辅助不可用；请检查系统自动化/辅助功能权限，本次会话不再尝试");
                }
            }catch(Exception|LinkageError failure){session.inputAssistFailed=true;log(session,"INPUT_ASSIST",null,"result=unavailable",failure);post(session,"INPUT_ASSIST_UNAVAILABLE","方向键辅助不可用，本次会话不再尝试");}
        }
    }

    /** DATA and BLOCK_ACK use disjoint wall-clock slots; normal responses remain immediate. */
    private void recoverSlot(Active session,String observed,long now,boolean own){
        if(session.paused||session.stopped||session.awaitingUserAction||session.currentFrame==null)return;
        boolean data=session.eventKey.startsWith("WAIT_ACK_DATA_");
        // WAIT_DATA_1 denotes READY. BLOCK_ACK handlers and restoration use WAIT_DATA_(index+1).
        boolean ack=session.eventKey.startsWith("WAIT_DATA_")&&!session.eventKey.equals("WAIT_DATA_1");
        if(own){
            if(!session.currentPublished||!(data||ack)||!sameFrame(observed,session.currentFrame))return;
        }else if(sameFrame(observed,session.currentFrame)||sameFrame(observed,session.ownEchoFrame))return;
        boolean scheduled=data||ack;
        long slot=Math.floorDiv(now,1000L);int second=(int)Math.floorMod(slot,10L);
        if(session.retries>=MAX_RETRIES){
            if(now-session.lastSentAt<FINAL_RETRY_WAIT_MILLIS)return;
        }else if(scheduled){
            if(!(data?(second==1||second==6):(second==4||second==8))||slot<=session.lastRetrySlot)return;
            // Consume this slot even if publication must be skipped. Never catch up a missed slot.
            session.lastRetrySlot=slot;
            if(now-session.lastSentAt<1_000L)return;
        }else if(now-session.lastSentAt<RETRY_INTERVAL_MILLIS)return;
        try{
            long revision=clipboard.revision();
            String latest=readClipboard(session);if(latest==null)latest="";
            if(!sameFrame(latest,observed)||(revision>=0&&clipboard.revision()!=revision))return;
            if(session.retries>=MAX_RETRIES){
                stopInternal(session,StopReason.RETRY_EXHAUSTED,"重试后仍未收到有效响应；已暂停，可继续确认",true);return;
            }
            // Reading a delayed RDP clipboard can block beyond the permitted second.
            if(scheduled&&Math.floorDiv(clock.getAsLong(),1000L)!=slot)return;
            session.retries++;session.lastSentAt=clock.getAsLong();
            if(session.pendingResponse!=null){publishPendingResponse(session,latest);return;}
            writeForSession(session,session.currentFrame);session.lastSentAt=clock.getAsLong();
            log(session,own?(ack?"ACK_REFRESH":"DATA_REFRESH"):"RECOVER_SLOT",session.currentFrame,
                "reason="+(own?"wall_clock_slot":"foreign_unprocessable")+" attempts="+session.retries+(scheduled?" slot_epoch_second="+slot:""),null);
        }catch(Throwable error){session.lastSentAt=clock.getAsLong();log(session,"ERROR",session.currentFrame,"classification=SLOT_RECOVERY",error);}
    }

    // The AES-GCM nonce is the frame identity. Compare a fixed-size token, not the DATA body.
    private static boolean sameFrame(String a,String b){
        if(a==b)return true;if(a==null||b==null||a.length()!=b.length())return false;
        int separator=a.indexOf(' ');
        if(separator>0&&separator<=128&&b.indexOf(' ')==separator&&a.length()>separator+40)
            return a.regionMatches(0,b,0,separator+17)&&a.regionMatches(a.length()-24,b,b.length()-24,24);
        return a.equals(b); // Empty/short non-protocol controls.
    }

    private String readClipboard(Active session)throws Exception{
        boolean logging=session!=null&&session.logContext!=null&&logsEnabled(session.logContext);long started=logging?System.nanoTime():0;
        try{return clipboard.read(logging);}catch(Exception error){if(logging){long elapsed=System.nanoTime()-started;log(session,"ERROR",null,"classification=CLIPBOARD_READ backend="+clipboard.backendName()+" duration_ms="+(elapsed/1_000_000L),error);}throw error;}finally{if(logging){long elapsed=System.nanoTime()-started;recordIo(session,false,elapsed);String fields=clipboard.readDiagnostics();if(!fields.isBlank())log(session,"CLIPBOARD_READ_DETAIL",null,"duration_ms="+(elapsed/1_000_000L)+" "+fields,null);}}
    }
    private void writeClipboard(Active session,String text){
        if(text==null)return;boolean logging=session!=null&&session.logContext!=null&&logsEnabled(session.logContext);long started=logging?System.nanoTime():0;
        try{clipboard.write(text);}catch(Throwable e){if(logging)log(session,"ERROR",null,"classification=CLIPBOARD_WRITE backend="+clipboard.backendName()+" duration_ms="+((System.nanoTime()-started)/1_000_000L),e);throw new IllegalStateException("无法写入剪贴板："+message(e),e);}finally{if(logging)recordIo(session,true,System.nanoTime()-started);}
    }
    private void writeClipboard(String text){writeClipboard(null,text);}
    private void recordPollStart(Active session){
        if(session.logContext==null)return;
        if(!logsEnabled(session.logContext)){session.lastPollNanos=0;session.ioSummaryNanos=0;resetIoMetrics(session);return;}
        long now=System.nanoTime();
        if(session.lastPollNanos!=0){long gap=now-session.lastPollNanos;session.pollGapTotalNanos+=gap;session.pollGapMaxNanos=Math.max(session.pollGapMaxNanos,gap);session.pollGapCount++;}
        session.lastPollNanos=now;if(session.ioSummaryNanos==0)session.ioSummaryNanos=now;
        if(now-session.ioSummaryNanos>=10_000_000_000L)flushIoSummary(session,now);
    }
    private void flushIoSummary(Active session,long now){
        if(session.ioSummaryNanos==0||session.logContext==null||!logsEnabled(session.logContext))return;
        long elapsed=now-session.ioSummaryNanos;
        String detail="backend="+clipboard.backendName()+" period_ms="+(elapsed/1_000_000L)
                +" poll_gap_avg_ms="+(session.pollGapCount==0?0:session.pollGapTotalNanos/session.pollGapCount/1_000_000L)
                +" poll_gap_max_ms="+(session.pollGapMaxNanos/1_000_000L)
                +" read_count="+session.readCount+" read_avg_ms="+(session.readCount==0?0:session.readTotalNanos/session.readCount/1_000_000L)
                +" read_max_ms="+(session.readMaxNanos/1_000_000L)
                +" write_count="+session.writeCount+" write_avg_ms="+(session.writeCount==0?0:session.writeTotalNanos/session.writeCount/1_000_000L)
                +" write_max_ms="+(session.writeMaxNanos/1_000_000L);
        log(session,"CLIPBOARD_IO_SUMMARY",null,detail,null);session.ioSummaryNanos=now;resetIoMetrics(session);
    }
    private static void resetIoMetrics(Active session){session.readCount=session.readTotalNanos=session.readMaxNanos=session.writeCount=session.writeTotalNanos=session.writeMaxNanos=session.pollGapTotalNanos=session.pollGapMaxNanos=session.pollGapCount=0;}
    private void recordIo(Active session,boolean write,long nanos){if(write){session.writeCount++;session.writeTotalNanos+=nanos;session.writeMaxNanos=Math.max(session.writeMaxNanos,nanos);}else{session.readCount++;session.readTotalNanos+=nanos;session.readMaxNanos=Math.max(session.readMaxNanos,nanos);}}

    private void writeForSession(Active session,String text){writeClipboard(session,text);if(!sameFrame(text,session.lastLocalOutput)){session.retries=0;}session.localWriteRevision=clipboard.lastWriteRevision();session.lastLocalOutput=text;session.lastObservedText=text;session.inputAssist.arm(text,clock.getAsLong());}
    private void deferPeerFrame(Active session,String text){
        if(session.deferredPeerFrames.contains(text))return;
        if(session.deferredPeerFrames.size()>=8){session.paused=true;log(session,"PAUSE",null,"reason=DEFERRED_FRAME_LIMIT",null);post(session,"DEFERRED_LIMIT","待处理协议帧过多，已暂停以避免丢帧");return;}
        session.deferredPeerFrames.addLast(text);log(session,"RECEIVE_DEFER",text,"reason=pending_response",null);
    }
    private String readClipboardQuietly(Active session,String fallback){try{String value=readClipboard(session);return value==null?"":value;}catch(Exception ignored){return fallback;}}

    private synchronized void retainTerminalResponse(String identity,String peerFrame,String responseFrame){
        long now=clock.getAsLong();terminalResponses.put(peerFrame,new TerminalResponse(identity,peerFrame,responseFrame,now));
        while(terminalResponses.size()>32)terminalResponses.remove(terminalResponses.keySet().iterator().next());
        if(terminalPollTask==null||terminalPollTask.isDone())terminalPollTask=worker.scheduleWithFixedDelay(this::pollTerminalResponses,POLL_MILLIS,POLL_MILLIS,TimeUnit.MILLISECONDS);
    }

    private void pollTerminalResponses(){
        String text;try{text=readClipboard(null);}catch(Throwable ignored){return;}if(text==null)text="";
        synchronized(this){
            if(disposed){if(terminalPollTask!=null)terminalPollTask.cancel(false);terminalPollTask=null;terminalResponses.clear();return;}
            long now=clock.getAsLong();
            var terminalIterator=terminalResponses.values().iterator();
            while(terminalIterator.hasNext()){
                TerminalResponse item=terminalIterator.next();
                if(now>=item.expiresAt){
                    if(active==null&&text.equals(item.responseFrame))try{writeClipboard(null,"");}catch(Throwable ignored){}
                    terminalIterator.remove();
                }
            }
            if(active==null){
                for(TerminalResponse item:terminalResponses.values()){
                    if(text.equals(item.peerFrame)&&!text.equals(item.lastObservedText)){
                        try{writeClipboard(null,item.responseFrame);item.lastObservedText=item.responseFrame;}catch(Throwable ignored){}
                        break;
                    }
                }
            }
            if(terminalResponses.isEmpty()&&terminalPollTask!=null){terminalPollTask.cancel(false);terminalPollTask=null;}
        }
    }

    private void stopInternal(Active session,StopReason reason,String status){stopInternal(session,reason,status,false);}
    private void stopInternal(Active session,StopReason reason,String status,boolean preserveResponse){
        if(session.stopped)return;session.stopped=true;
        if(session.logContext!=null&&logsEnabled(session.logContext))flushIoSummary(session,System.nanoTime());
        log(session,reason==StopReason.COMPLETED?"COMPLETE":"PAUSE",null,"reason="+reason,null);
        Listener listener=(Listener)session.lease.listener();
        session.lease.release();
        synchronized(this){if(active==session)active=null;}
        worker.execute(()->{
            try{
                String current=readClipboard(session);
                if(!preserveResponse&&session.currentFrame!=null&&session.currentFrame.equals(current))writeClipboard(session,"");
            }catch(Throwable ignored){}
            if(session.eventLog!=null)session.eventLog.close();
            if(listener!=null)dispatcher.accept(()->{listener.onStatus(new StatusEvent(session.statusSessionKey,reason==StopReason.COMPLETED?session.completionEventKey:"STOP_"+reason,status,reason==StopReason.COMPLETED));listener.onStopped(reason);});
        });
    }

    private void post(Active session,String status){post(session,session.eventKey,status);}
    private void post(Active session,String eventKey,String status){
        if(status==null)return;
        Listener listener=(Listener)session.lease.listener();
        if(listener!=null){StatusEvent event=new StatusEvent(session.statusSessionKey,eventKey,status,false);dispatcher.accept(()->listener.onStatus(event));}
    }
    private FrameInfo describe(Active session,String frame){if(frame==null||session.logContext==null||session.logContext.describer()==null)return null;try{return session.logContext.describer().describe(frame);}catch(Exception ignored){return null;}}
    private void log(Active session,String event,String frame,String detail,Throwable error){
        if(session.logContext==null)return;
        if(!logsEnabled(session.logContext)){try{worker.execute(()->session.loggingActive=false);}catch(RuntimeException ignored){}return;}
        try{worker.execute(()->{
            if(!logsEnabled(session.logContext)){session.loggingActive=false;return;}
            FrameInfo info=describe(session,frame);
            if(session.eventLog==null&&info!=null){session.logSession=info.sessionId();session.eventLog=TransferEventLog.open(session.logContext.workspace(),info.sessionId(),session.logContext.endpoint());}
            TransferEventLog eventLog=session.eventLog;if(eventLog==null)return;
            String fields=fields(info,detail);
            if(!session.loggingActive){eventLog.event("LOG_ENABLED",fields+(fields.isBlank()?"":" ")+"segment="+(session.currentFrame!=null||session.lastPeerFrame!=null?"enabled_mid_session":"enabled_at_start"));session.loggingActive=true;}
            if(error==null)eventLog.event(event,fields);else eventLog.error(event+(fields.isBlank()?"":" "+fields),error);
        });}catch(RuntimeException ignored){/* Logging is diagnostic only. */}
    }
    private static boolean logsEnabled(LogContext context){try{return context.enabled()!=null&&context.enabled().getAsBoolean();}catch(RuntimeException ignored){return false;}}
    private static String fields(FrameInfo info,String detail){StringBuilder fields=new StringBuilder();if(info!=null){if(info.sessionId()!=null)fields.append("session=").append(info.sessionId()).append(' ');fields.append("message_type=").append(info.type());if(info.index()>0)fields.append(" block=").append(info.index());if(info.total()>0)fields.append(" total=").append(info.total());}if(detail!=null&&!detail.isBlank()){if(!fields.isEmpty())fields.append(' ');fields.append(detail);}return fields.toString();}

    /** Clears only transfer logs on the coordinator queue, ordered against pending appends and rotations. */
    public void clearTransferLogs(Path workspace,Consumer<TransferEventLog.ClearResult> completed,Consumer<Throwable> failed){
        try{worker.execute(()->{try{
            TransferEventLog.ClearResult result=TransferEventLog.clear(workspace);
            Active current; synchronized(this){current=active;}
            if(current!=null&&current.logContext!=null&&current.logContext.workspace()!=null
                    &&current.logContext.workspace().toAbsolutePath().normalize().equals(workspace.toAbsolutePath().normalize())){
                if(current.eventLog!=null)current.eventLog.resetAfterClear();current.loggingActive=false;
            }
            if(completed!=null)dispatcher.accept(()->completed.accept(result));
        }catch(Throwable error){if(failed!=null)dispatcher.accept(()->failed.accept(error));}});}catch(RuntimeException error){if(failed!=null)dispatcher.accept(()->failed.accept(error));}
    }
    private String keyFor(String frame,Active session,String fallback){FrameInfo info=describe(session,frame);if(info==null)return fallback;return switch(info.type()){
        case "INVITE"->"WAIT_READY";case "DATA"->"WAIT_ACK_DATA_"+info.index();case "FINAL"->"WAIT_FINAL_ACK";
        case "BLOCK_ACK"->"WAIT_DATA_"+(info.index()+1);case "READY"->"WAIT_DATA_1";case "BUSINESS_ACK","RECEIVED"->"WAIT_FINAL";default->fallback;};}
    private String frameType(Active session,String frame,String fallback){FrameInfo info=describe(session,frame);if(info==null)return fallback;return info.type().equals("BLOCK_ACK")?"ACK":"SEND";}
    synchronized boolean hasActiveTransfer(){return active!=null&&!active.stopped;}
    private static String failureSummary(Throwable error){
        String text=message(error).replaceAll("[\\r\\n\\t]+"," ");
        return text.length()>500?text.substring(0,500)+"…":text;
    }
    private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}

    @Override public synchronized void dispose(){
        disposed=true;if(active!=null)stopInternal(active,StopReason.DISPOSED,"IDEA 正在关闭");if(terminalPollTask!=null)terminalPollTask.cancel(false);terminalPollTask=null;terminalResponses.clear();if(clipboard instanceof AutoCloseable closeable)try{worker.execute(()->{try{closeable.close();}catch(Exception ignored){}});}catch(RuntimeException ignored){}worker.shutdown();
    }
}
