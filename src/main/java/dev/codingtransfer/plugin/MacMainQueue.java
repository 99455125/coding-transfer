package dev.codingtransfer.plugin;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Dispatches AppKit work to the Cocoa main queue without tying it to Swing's EDT. */
final class MacMainQueue {
    static final long DEFAULT_WAIT_MILLIS=10_000;
    private static final AtomicLong IDS=new AtomicLong();
    private static final ConcurrentHashMap<Long,DispatchWork> LIVE_CALLBACKS=new ConcurrentHashMap<>();
    private static final DispatchWork RELEASE_CALLBACK=context->LIVE_CALLBACKS.remove(Pointer.nativeValue(context));
    private final NativeDispatch dispatch;
    private final long waitMillis;
    private final Object serializationLock=new Object();
    private boolean runningTimedOut;

    MacMainQueue(){this(new SystemDispatch(),DEFAULT_WAIT_MILLIS);}
    MacMainQueue(NativeDispatch dispatch){this(dispatch,DEFAULT_WAIT_MILLIS);}
    MacMainQueue(NativeDispatch dispatch,long waitMillis){this.dispatch=dispatch;this.waitMillis=waitMillis;}

    <T>T call(Work<T> action)throws Exception {
        synchronized(serializationLock){if(runningTimedOut)throw uncertainFailure();}
        if(dispatch.isMainThread())return action.run();
        long id=IDS.incrementAndGet();CountDownLatch done=new CountDownLatch(1);
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();
        AtomicReference<CallbackState> state=new AtomicReference<>(CallbackState.PENDING);
        DispatchWork work=context->{
            try{
                if(state.compareAndSet(CallbackState.PENDING,CallbackState.RUNNING)){
                    try{result.set(action.run());}catch(Throwable error){failure.set(error);}
                }
            }finally{
                CallbackState previous=state.getAndSet(CallbackState.FINISHED);
                try{dispatch.enqueueCleanup(id,RELEASE_CALLBACK);}catch(Throwable ignored){/* Keep the callback strongly held if native cleanup could not be queued. */}
                synchronized(serializationLock){if(previous==CallbackState.RUNNING_TIMED_OUT)runningTimedOut=false;}
                done.countDown();
            }
        };
        synchronized(serializationLock){
            if(runningTimedOut)throw uncertainFailure();
            LIVE_CALLBACKS.put(id,work);
            try{dispatch.enqueue(id,work);}catch(RuntimeException|Error enqueueFailure){
                if(state.compareAndSet(CallbackState.PENDING,CallbackState.CANCELLED))LIVE_CALLBACKS.remove(id);
                throw enqueueFailure;
            }
        }
        try{
            if(!done.await(waitMillis,TimeUnit.MILLISECONDS))return expire(id,state,done,result,failure);
        }catch(InterruptedException interrupted){
            IOException cancelled=expirePending(state);
            Thread.currentThread().interrupt();
            if(cancelled!=null)throw cancelled;
            synchronized(serializationLock){
                if(state.compareAndSet(CallbackState.RUNNING,CallbackState.RUNNING_TIMED_OUT))runningTimedOut=true;
                if(state.get()==CallbackState.FINISHED)return completed(result,failure);
            }
            throw uncertainFailure(interrupted);
        }
        return completed(result,failure);
    }

    private <T>T expire(long id,AtomicReference<CallbackState> state,CountDownLatch done,
                        AtomicReference<T> result,AtomicReference<Throwable> failure)throws Exception {
        synchronized(serializationLock){
            if(state.compareAndSet(CallbackState.PENDING,CallbackState.CANCELLED))
                throw new IOException("Cocoa 主队列未及时调度剪贴板操作；尚未开始的操作已取消");
            if(state.compareAndSet(CallbackState.RUNNING,CallbackState.RUNNING_TIMED_OUT)){
                runningTimedOut=true;
                throw uncertainFailure();
            }
            if(state.get()==CallbackState.FINISHED)return completed(result,failure);
            if(state.get()==CallbackState.CANCELLED)throw new IOException("Cocoa 主队列剪贴板操作已取消");
            throw uncertainFailure();
        }
    }

    private IOException expirePending(AtomicReference<CallbackState> state){
        synchronized(serializationLock){
            if(state.compareAndSet(CallbackState.PENDING,CallbackState.CANCELLED))return new IOException("等待 Cocoa 主线程剪贴板操作时被中断；尚未开始的操作已取消");
            return null;
        }
    }
    private static IOException uncertainFailure(){return uncertainFailure(null);}
    private static IOException uncertainFailure(Throwable cause){return new UncertainOperationException("Cocoa 主线程剪贴板操作已开始但未及时完成；结果不确定，待原操作结束前已暂停后续操作",cause);}
    private static <T>T completed(AtomicReference<T> result,AtomicReference<Throwable> failure)throws Exception{
        Throwable error=failure.get();if(error instanceof Exception exception)throw exception;if(error instanceof Error fatal)throw fatal;return result.get();
    }

    static int pendingCallbacksForTesting(){return LIVE_CALLBACKS.size();}
    static void verifyNativeSymbols(){new SystemDispatch();}
    static final class UncertainOperationException extends IOException {UncertainOperationException(String message,Throwable cause){super(message,cause);}}
    private enum CallbackState{PENDING,RUNNING,RUNNING_TIMED_OUT,CANCELLED,FINISHED}
    @FunctionalInterface interface Work<T>{T run()throws Exception;}
    interface NativeDispatch{boolean isMainThread();void enqueue(long id,DispatchWork work);void enqueueCleanup(long id,DispatchWork cleanup);}
    interface DispatchWork extends Callback{void invoke(Pointer context);}

    private static final class SystemDispatch implements NativeDispatch {
        private static final NativeLibrary SYSTEM=NativeLibrary.getInstance("System");
        private final Pointer mainQueue=SYSTEM.getGlobalVariableAddress("_dispatch_main_q");
        private final Function async=SYSTEM.getFunction("dispatch_async_f");
        private final Function isMain=SYSTEM.getFunction("pthread_main_np");
        SystemDispatch(){if(mainQueue==null||Pointer.nativeValue(mainQueue)==0)throw new UnsatisfiedLinkError("libSystem has no _dispatch_main_q global");}
        @Override public boolean isMainThread(){return isMain.invokeInt(new Object[0])!=0;}
        @Override public void enqueue(long id,DispatchWork work){async.invokeVoid(new Object[]{mainQueue,Pointer.createConstant(id),work});}
        @Override public void enqueueCleanup(long id,DispatchWork cleanup){async.invokeVoid(new Object[]{mainQueue,Pointer.createConstant(id),cleanup});}
    }
}
