package dev.codingtransfer.plugin;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef;

/** Fake-binding checks for native adapter ownership, bounds, text decoding and disposal. */
public final class NativeClipboardAccessTest {
    private NativeClipboardAccessTest() {}
    public static void main(String[] args) throws Exception {
        diagnosticReadIsOptInAndSurvivesFailure();
        writeRevisionSurvivesExternalChanges();
        NativeClipboardAccessTest.windowsCopiesUnicodeAndTransfersMemoryOnlyAfterSet();
        NativeClipboardAccessTest.windowsBusyAndFailedSetAreBoundedAndFreeMemory();
        NativeClipboardAccessTest.windowsNoTextClearAndDispose();
        NativeClipboardAccessTest.partialStartupIsCleanedAndRetryGetsFreshBinding();
        NativeClipboardAccessTest.messageOnlyWindowParentIsSignExtendedAndPassedToCreator();
        System.out.println("PASS native clipboard fake binding checks");
    }

    private static void diagnosticReadIsOptInAndSurvivesFailure()throws Exception {
        FakeBindings bindings=new FakeBindings();
        try(NativeClipboardAccess access=new NativeClipboardAccess(new WindowsClipboardBackend(bindings))){
            access.read(false);check(access.readDiagnostics().isEmpty()&&bindings.diagnosticStarts==0,"disabled logging collects no read diagnostics");
            access.read(true);String detail=access.readDiagnostics();
            check(detail.contains("open_wait_us=")&&detail.contains("get_data_us=")&&detail.contains("close_us="),"successful read exposes stage metrics through native adapter");
            check(!detail.contains(bindings.text)&&!bindings.diagnostic,"diagnostics omit payload and turn off after read");
            bindings.busyOpens=99;expect(IOException.class,()->access.read(true),"busy read fails normally");
            check(access.readDiagnostics().contains("open_error=5")&&access.readDiagnostics().contains("open_pid=42"),"open failure retains holder diagnostic fields");
            bindings.busyOpens=0;access.read(false);check(access.readDiagnostics().isEmpty(),"later logging-off read clears stale fields");
        }
    }

    private static void windowsCopiesUnicodeAndTransfersMemoryOnlyAfterSet() throws Exception {
        FakeBindings bindings=new FakeBindings();
        try(WindowsClipboardBackend backend=new WindowsClipboardBackend(bindings)) {
            backend.write("你好 🌍");
            check(bindings.events.indexOf("allocate")<bindings.events.indexOf("empty"),"global memory is allocated before existing clipboard contents are cleared");
            check(bindings.events.indexOf("empty")<bindings.events.indexOf("set"),"clipboard is emptied before publishing prepared memory");
            check("你好 🌍".equals(backend.read()),"UTF-16 clipboard content round-trips Unicode");
            check(bindings.frees==0,"successful SetClipboardData transfers memory ownership to Windows");
        }
    }
    private static void windowsBusyAndFailedSetAreBoundedAndFreeMemory() throws Exception {
        FakeBindings busy=new FakeBindings();busy.busyOpens=99;
        try(WindowsClipboardBackend backend=new WindowsClipboardBackend(busy)) {
            expect(IOException.class,backend::read,"busy clipboard eventually fails");
            check(busy.openAttempts==WindowsClipboardBackend.MAX_OPEN_ATTEMPTS,"clipboard busy retry count is bounded");
        }
        FakeBindings failed=new FakeBindings();failed.failSet=true;
        try(WindowsClipboardBackend backend=new WindowsClipboardBackend(failed)) {
            expect(IOException.class,()->backend.write("value"),"SetClipboardData failure is reported");
            check(failed.frees==1,"memory ownership stays local and is released when publication fails");
            check(failed.closeCalls==1,"OpenClipboard is paired with CloseClipboard on failure");
        }
    }
    private static void windowsNoTextClearAndDispose() throws Exception {
        FakeBindings bindings=new FakeBindings();bindings.text=null;
        WindowsClipboardBackend backend=new WindowsClipboardBackend(bindings);
        check(backend.read()==null,"no string format is represented as no text");
        backend.write("");check(bindings.lastText.isEmpty(),"clear publishes an empty Unicode string");
        backend.close();check(bindings.destroyed==1,"dispose releases the hidden window and message loop");
    }
    private static void partialStartupIsCleanedAndRetryGetsFreshBinding() throws Exception {
        FakeBindings partial=new FakeBindings();partial.failStart=true;FakeBindings recovered=new FakeBindings();recovered.text=null;
        AtomicInteger created=new AtomicInteger();
        WindowsClipboardBackend backend=new WindowsClipboardBackend(()->created.getAndIncrement()==0?partial:recovered);
        try {
            try { backend.read(); throw new AssertionError("failed window startup must fail the read"); }
            catch(NativeClipboardAccess.InitializationFailure expected) {
                check(WindowsClipboardBackend.startupFailure(expected)!=null,"startup error retains safe stage metadata");
            }
            check(partial.starts==1&&partial.destroyed==1,"partial message-loop startup is stopped exactly once");
            check(backend.read()==null,"explicit retry creates a fresh clipboard binding");
            check(partial.starts==1&&recovered.starts==1,"retry never calls Thread.start on the old binding");
            backend.close();
            check(recovered.destroyed==1,"closing a recovered backend also stops its message loop");
        } finally {backend.close();}
    }
    private static void messageOnlyWindowParentIsSignExtendedAndPassedToCreator() throws Exception {
        check(Native.POINTER_SIZE==8,"message-only HWND regression check runs on 64-bit");
        Pointer parent=WindowsClipboardBackend.messageOnlyParent();
        check(Pointer.nativeValue(parent)==-3L,"message-only HWND parent is sign-extended to 64 bits");
        check(Pointer.nativeValue(parent)!=Pointer.nativeValue(Pointer.createConstant(-3)),"parent helper avoids the int overload's zero extension");
        AtomicReference<Long> received=new AtomicReference<>();
        WindowsClipboardBackend.createMessageOnlyWindow(hwnd->{received.set(Pointer.nativeValue(hwnd.getPointer()));return new WinDef.HWND(Pointer.createConstant(1));});
        check(received.get()!=null&&received.get()==-3L,"create-window adapter passes the production message-only parent handle");
    }
    private static void expect(Class<? extends Throwable> type,Throwing action,String message)throws Exception {try{action.run();throw new AssertionError(message);}catch(Throwable failure){if(!type.isInstance(failure))throw failure;}}
    @FunctionalInterface private interface Throwing {void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void writeRevisionSurvivesExternalChanges()throws Exception {
        FakeBindings binding=new FakeBindings();
        try(WindowsClipboardBackend backend=new WindowsClipboardBackend(binding)){
            backend.write("test");
            check(backend.lastWriteRevision()==1,"write revision captured before clipboard closes");
            binding.sequence=2;
            check(backend.revision()==2&&backend.lastWriteRevision()==1,"external changes preserve prior write revision");
        }
    }
    private static final class FakeBindings implements WindowsClipboardBackend.Bindings {
        boolean diagnostic;int diagnosticStarts;
        public void diagnostics(boolean enabled){diagnostic=enabled;if(enabled)diagnosticStarts++;}
        public String readDiagnostics(){return busyOpens>0?"open_error=5 open_hwnd=0x123 open_pid=42 open_process=rdpclip.exe":"get_data_us=123 copy_decode_us=12";}
        final java.util.List<String> events=new java.util.ArrayList<>();String text="你好 🌍",lastText;int busyOpens,openAttempts,closeCalls,frees,destroyed,starts;boolean failSet,failStart;long sequence;public long revision(){return sequence;}
        public void start()throws Exception{starts++;if(failStart)throw new WindowsClipboardBackend.StartupFailure(WindowsClipboardBackend.StartupStage.CREATE_WINDOW,"CreateWindowExW",1407,new IllegalStateException("sensitive message must not be logged"));} public void stop(){destroyed++;}
        public boolean open(){openAttempts++;events.add("open");if(busyOpens>0){busyOpens--;return false;}return true;}
        public void closeClipboard(){closeCalls++;events.add("close");}
        public Object allocate(String value){events.add("allocate");lastText=value;return value;}
        public void free(Object memory){frees++;events.add("free");}
        public void empty(){events.add("empty");}
        public void set(Object memory)throws IOException{events.add("set");if(failSet)throw new IOException("simulated SetClipboardData failure");sequence++;}
        public String read(long maxBytes){events.add("read");return text;}
        public String backend(){return "Windows";}
    }
}
