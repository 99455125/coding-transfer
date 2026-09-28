package dev.codingtransfer.plugin;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fake dispatcher checks queued callback lifetime and cancellation semantics without AppKit. */
public final class MacMainQueueTest {
    private MacMainQueueTest() {}
    public static void main(String[] args)throws Exception {
        interruptedQueuedWriteIsRetainedThenSkipped();
        timedOutQueuedWriteIsCanceledAndReleasedOnLateCallback();
        runningTimeoutPausesFurtherCallsUntilCompletion();
        mainThreadExecutesInline();
        System.out.println("PASS macOS main-queue callback lifecycle checks");
    }
    private static void interruptedQueuedWriteIsRetainedThenSkipped()throws Exception {
        FakeDispatch dispatch=new FakeDispatch();MacMainQueue queue=new MacMainQueue(dispatch);AtomicInteger writes=new AtomicInteger();AtomicReference<Throwable> result=new AtomicReference<>();
        int callbacksBefore=MacMainQueue.pendingCallbacksForTesting();
        Thread caller=new Thread(()->{try{queue.call(()->{writes.incrementAndGet();return null;});}catch(Throwable error){result.set(error);}},"mac-main-queue-caller");caller.start();
        check(dispatch.enqueued.await(2,TimeUnit.SECONDS),"callback was enqueued");caller.interrupt();caller.join(2_000);
        check(!caller.isAlive()&&result.get() instanceof IOException,"interrupted caller returns a clear error");
        check(MacMainQueue.pendingCallbacksForTesting()==callbacksBefore+1,"native callback remains strongly held until dispatch invokes it");
        dispatch.runPending();
        check(writes.get()==0,"an interrupted queued write is skipped when it later reaches the main queue");
        check(MacMainQueue.pendingCallbacksForTesting()==callbacksBefore,"callback reference is released after native callback completion");
    }
    private static void timedOutQueuedWriteIsCanceledAndReleasedOnLateCallback()throws Exception {
        FakeDispatch dispatch=new FakeDispatch();MacMainQueue queue=new MacMainQueue(dispatch,30);AtomicInteger writes=new AtomicInteger();AtomicReference<Throwable> result=new AtomicReference<>();
        int callbacksBefore=MacMainQueue.pendingCallbacksForTesting();Thread caller=new Thread(()->{try{queue.call(()->{writes.incrementAndGet();return null;});}catch(Throwable error){result.set(error);}},"mac-main-queue-timeout");caller.start();
        check(dispatch.enqueued.await(2,TimeUnit.SECONDS),"timeout callback was enqueued");caller.join(2_000);
        check(!caller.isAlive()&&result.get() instanceof IOException&&result.get().getMessage().contains("尚未开始的操作已取消"),"pending queue timeout returns a cancellation result");
        check(MacMainQueue.pendingCallbacksForTesting()==callbacksBefore+1,"timed out callback remains held for the native callback trampoline");
        dispatch.runPending();check(writes.get()==0,"late native delivery skips the canceled write");
        check(MacMainQueue.pendingCallbacksForTesting()==callbacksBefore,"cleanup callback releases the original callback only after it returns");
    }
    private static void runningTimeoutPausesFurtherCallsUntilCompletion()throws Exception {
        FakeDispatch dispatch=new FakeDispatch();MacMainQueue queue=new MacMainQueue(dispatch,30);CountDownLatch actionStarted=new CountDownLatch(1),releaseAction=new CountDownLatch(1);AtomicReference<Throwable> result=new AtomicReference<>();
        Thread caller=new Thread(()->{try{queue.call(()->{actionStarted.countDown();releaseAction.await();return null;});}catch(Throwable error){result.set(error);}},"mac-main-queue-running-timeout");caller.start();
        check(dispatch.enqueued.await(2,TimeUnit.SECONDS),"running callback was enqueued");Thread callback=new Thread(dispatch::runPending,"fake-cocoa-main");callback.start();
        check(actionStarted.await(2,TimeUnit.SECONDS),"native callback entered its operation");caller.join(2_000);
        check(result.get() instanceof MacMainQueue.UncertainOperationException,"running timeout reports an uncertain operation result");
        int enqueues=dispatch.enqueueCount;try{queue.call(()->null);throw new AssertionError("operation should remain serialized while the timed-out callback is running");}catch(MacMainQueue.UncertainOperationException expected){}
        check(dispatch.enqueueCount==enqueues,"a second operation cannot race the still-running write");releaseAction.countDown();callback.join(2_000);
        check(!callback.isAlive()&&MacMainQueue.pendingCallbacksForTesting()==0,"callback completion clears the uncertain state and its retained reference");
        dispatch.mainThread=true;queue.call(()->null);
    }
    private static void mainThreadExecutesInline()throws Exception {
        FakeDispatch dispatch=new FakeDispatch();dispatch.mainThread=true;MacMainQueue queue=new MacMainQueue(dispatch);AtomicInteger actions=new AtomicInteger();
        queue.call(()->{actions.incrementAndGet();return null;});
        check(actions.get()==1&&dispatch.enqueueCount==0,"main-thread caller executes directly without waiting on its own queue");
    }
    private static final class FakeDispatch implements MacMainQueue.NativeDispatch {
        final CountDownLatch enqueued=new CountDownLatch(1);MacMainQueue.DispatchWork pending,cleanup;long pendingId;boolean mainThread;int enqueueCount;
        public boolean isMainThread(){return mainThread;}
        public synchronized void enqueue(long id,MacMainQueue.DispatchWork work){pending=work;pendingId=id;enqueueCount++;enqueued.countDown();}
        public synchronized void enqueueCleanup(long id,MacMainQueue.DispatchWork work){cleanup=work;}
        void runPending(){MacMainQueue.DispatchWork callback;long id;synchronized(this){callback=pending;id=pendingId;pending=null;}callback.invoke(null);MacMainQueue.DispatchWork cleanupCallback;synchronized(this){cleanupCallback=cleanup;cleanup=null;}if(cleanupCallback!=null)cleanupCallback.invoke(com.sun.jna.Pointer.createConstant(id));}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
