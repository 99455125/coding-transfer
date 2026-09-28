package dev.codingtransfer.plugin;

import com.sun.jna.Pointer;
import java.io.IOException;

/** Fake Objective-C bridge tests typed BOOL failure without accessing AppKit or the user pasteboard. */
public final class MacClipboardBackendTest {
    private MacClipboardBackendTest() {}
    public static void main(String[] args)throws Exception {
        falseBoolFromSetStringIsAnErrorAndPoolStillReleases();
        writeRevisionSurvivesExternalChanges();
        System.out.println("PASS macOS Objective-C typed return checks");
    }
    private static void falseBoolFromSetStringIsAnErrorAndPoolStillReleases()throws Exception {
        FakeObjc bridge=new FakeObjc();MacClipboardBackend backend=new MacClipboardBackend(bridge,new MacMainQueue(new InlineDispatch(),20));
        try{backend.write("test");throw new AssertionError("BOOL false from setString:forType: must fail publication");}
        catch(IOException expected){check(expected.getMessage().contains("setString:forType: failed"),"false Objective-C BOOL is reported as a write failure");}
        check(bridge.clearCalls==1&&bridge.boolCalls==1,"write used clearContents NSInteger and setString BOOL paths");
        check(bridge.releaseCalls==1,"autorelease pool release runs even when setString returns false");
    }
    private static void writeRevisionSurvivesExternalChanges()throws Exception {
        FakeObjc bridge=new FakeObjc();bridge.success=true;
        MacClipboardBackend backend=new MacClipboardBackend(bridge,new MacMainQueue(new InlineDispatch(),20));
        backend.write("test");check(backend.lastWriteRevision()==1&&backend.revision()==1,"own clear revision is recorded");
        bridge.revision=2;check(backend.lastWriteRevision()==1&&backend.revision()==2,"external change cannot become our write revision");
        bridge.changeDuringSet=true;backend.write("next");check(backend.lastWriteRevision()==-1,"concurrent owner invalidates write revision");
        backend.close();
    }
    private static final class FakeObjc implements MacClipboardBackend.ObjcBridge {
        final Pointer pasteboard=Pointer.createConstant(11),pool=Pointer.createConstant(12),string=Pointer.createConstant(13);
        int clearCalls,boolCalls,releaseCalls;long revision;boolean success,changeDuringSet;
        public Pointer classNamed(String name){return switch(name){case "NSPasteboard","NSAutoreleasePool","NSString"->Pointer.createConstant(10);default->null;};}
        public Pointer object(String selector,Pointer receiver,Pointer...args){return switch(selector){case "generalPasteboard"->pasteboard;case "alloc","init"->pool;case "stringWithUTF8String:"->string;default->throw new AssertionError("unexpected object selector "+selector);};}
        public boolean bool(String selector,Pointer receiver,Pointer...args){check(selector.equals("setString:forType:"),"only setString has a BOOL result");boolCalls++;if(changeDuringSet)revision++;return success;}
        public long integer(String selector,Pointer receiver,Pointer...args){if(selector.equals("changeCount"))return revision;check(selector.equals("clearContents"),"clearContents has an NSInteger result");clearCalls++;return ++revision;}
        public void voidMessage(String selector,Pointer receiver,Pointer...args){check(selector.equals("release"),"release has a void result");releaseCalls++;}
    }
    private static final class InlineDispatch implements MacMainQueue.NativeDispatch {
        public boolean isMainThread(){return true;}
        public void enqueue(long id,MacMainQueue.DispatchWork work){throw new AssertionError("main-thread calls must not enqueue");}
        public void enqueueCleanup(long id,MacMainQueue.DispatchWork cleanup){throw new AssertionError("inline call has no native callback");}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
