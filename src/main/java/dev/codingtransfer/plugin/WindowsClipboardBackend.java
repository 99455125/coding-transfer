package dev.codingtransfer.plugin;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32Util;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.Native;

import java.io.IOException;
import dev.codingtransfer.core.EncryptedFrameCodec;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Windows CF_UNICODETEXT access serialized on a dedicated message-loop thread. */
final class WindowsClipboardBackend implements NativeClipboardAccess.Backend {
    static final int MAX_OPEN_ATTEMPTS=8;
    private static final int CF_UNICODETEXT=13;
    private static final int GMEM_MOVEABLE=0x0002;
    static Pointer messageOnlyParent(){return Pointer.createConstant(-3L);}
    @FunctionalInterface interface MessageWindowCreator {WinDef.HWND create(WinDef.HWND parent)throws Exception;}
    static WinDef.HWND createMessageOnlyWindow(MessageWindowCreator creator)throws Exception{return creator.create(new WinDef.HWND(messageOnlyParent()));}
    interface Bindings {
        default void diagnostics(boolean enabled){} default String readDiagnostics(){return "";}
        void start() throws Exception; void stop(); boolean open() throws Exception; void closeClipboard() throws Exception;
        Object allocate(String text) throws Exception; void free(Object memory) throws Exception; void empty() throws Exception;
        void set(Object memory) throws Exception; String read(long maxBytes) throws Exception; String backend();default long revision()throws Exception{return -1;}
    }
    private Bindings bindings;
    private final Supplier<Bindings> bindingFactory;
    private volatile boolean closed;
    private long writeRevision=-1;
    private boolean diagnosticRead;
    private String readDetails="";
    private long openWaitNanos,closeNanos;
    @Override public synchronized String readDiagnostics(){return readDetails;}
    @Override public synchronized long revision()throws Exception{ensureOpen();return bindings.revision();}
    @Override public synchronized long lastWriteRevision(){return writeRevision;}
    WindowsClipboardBackend(){this(WinBindings::new);}
    WindowsClipboardBackend(Bindings bindings){this.bindingFactory=()->bindings;this.bindings=bindings;}
    WindowsClipboardBackend(Supplier<Bindings> bindingFactory){this.bindingFactory=java.util.Objects.requireNonNull(bindingFactory);}
    @Override public String backendName(){return bindings==null?"Windows":bindings.backend();}
    @Override public synchronized String read() throws Exception {return read(false);}
    @Override public synchronized String read(boolean diagnostics) throws Exception {
        readDetails="";ensureOpen();diagnosticRead=diagnostics;openWaitNanos=closeNanos=0;
        bindings.diagnostics(diagnostics);
        try{final String[] value=new String[1];withClipboard(()->value[0]=bindings.read((EncryptedFrameCodec.maxEncodedFrameChars()+1L)*2L));return value[0];}
        finally{
            if(diagnostics)readDetails="open_wait_us="+openWaitNanos/1000+" close_us="+closeNanos/1000+" "+bindings.readDiagnostics();
            diagnosticRead=false;bindings.diagnostics(false);
        }
    }
    @Override public synchronized void write(String text) throws Exception {
        ensureOpen();if(text==null)throw new IOException("不能写入空引用文本");if(text.indexOf('\0')>=0)throw new IOException("剪贴板文本不能包含 NUL 字符");if(text.length()>EncryptedFrameCodec.maxEncodedFrameChars())throw new IOException("剪贴板文本超过传输上限");
        long bytes=(text.length()+1L)*2L;
        if(bytes>(EncryptedFrameCodec.maxEncodedFrameChars()+1L)*2L)throw new IOException("剪贴板文本超过传输上限");
        writeRevision=-1;Object memory=bindings.allocate(text);boolean[] transferred={false};
        try{withClipboard(()->{bindings.empty();bindings.set(memory);transferred[0]=true;writeRevision=bindings.revision();});}
        finally{if(!transferred[0])bindings.free(memory);}
    }
    private void withClipboard(Checked operation)throws Exception {
        boolean opened=false;long openStarted=diagnosticRead?System.nanoTime():0;
        try{
            for(int attempt=0;attempt<MAX_OPEN_ATTEMPTS;attempt++){
                if(bindings.open()){opened=true;break;}
                if(attempt+1<MAX_OPEN_ATTEMPTS)Thread.sleep(25L);
            }
            if(diagnosticRead)openWaitNanos=System.nanoTime()-openStarted;
            if(!opened)throw new IOException("Windows 剪贴板正被占用");
            operation.run();
        }finally{if(opened){long closeStarted=diagnosticRead?System.nanoTime():0;try{bindings.closeClipboard();}finally{if(diagnosticRead)closeNanos=System.nanoTime()-closeStarted;}}}
    }
    private synchronized void ensureOpen()throws Exception{
        if(closed)throw new IOException("Windows 剪贴板后端已关闭");
        if(bindings==null){
            try{bindings=bindingFactory.get();}
            catch(Throwable error){throw new NativeClipboardAccess.InitializationFailure("Windows 剪贴板启动失败",new StartupFailure(StartupStage.LOAD_LIBRARY,"LoadLibrary",null,error));}
            if(bindings==null)throw new NativeClipboardAccess.InitializationFailure("Windows 剪贴板启动失败",new StartupFailure(StartupStage.LOAD_LIBRARY,"LoadLibrary",null,new IllegalStateException()));
        }
        if(!started){
            try{bindings.start();started=true;}
            catch(Throwable error){
                Bindings failed=bindings;bindings=null;started=false;
                try{failed.stop();}catch(Throwable cleanup){error.addSuppressed(cleanup);}
                StartupFailure startup=findStartupFailure(error);
                if(startup==null)startup=new StartupFailure(StartupStage.START_LOOP,"MessageLoopThread.start",null,error);
                throw new NativeClipboardAccess.InitializationFailure("Windows 剪贴板启动失败",startup);
            }
        }
    }
    private boolean started;
    @Override public synchronized void close(){if(!closed){closed=true;Bindings current=bindings;bindings=null;started=false;if(current!=null)try{current.stop();}catch(Throwable ignored){}}}
    @FunctionalInterface private interface Checked{void run()throws Exception;}

    enum StartupStage{LOAD_LIBRARY,START_LOOP,CREATE_WINDOW}
    static final class StartupFailure extends Exception{
        private final StartupStage stage;private final String api;private final Integer win32Error;
        StartupFailure(StartupStage stage,String api,Integer win32Error,Throwable cause){super("Windows clipboard startup failure",cause);this.stage=stage;this.api=api;this.win32Error=win32Error;}
        StartupStage stage(){return stage;}String api(){return api;}Integer win32Error(){return win32Error;}
        String safeSummary(){return "Windows 剪贴板启动失败（"+stage+" / "+api+(win32Error==null?"":" / Win32 "+win32Error)+"）";}
        String logFields(){return "startup_stage="+stage+" startup_api="+api+(win32Error==null?"":" win32_error="+win32Error);}
    }
    static StartupFailure startupFailure(Throwable failure){return findStartupFailure(failure);}
    static String startupLogFields(Throwable failure){StartupFailure startup=findStartupFailure(failure);return startup==null?"":startup.logFields();}
    private static StartupFailure findStartupFailure(Throwable failure){
        java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.add(current);current=current.getCause())if(current instanceof StartupFailure startup)return startup;
        return null;
    }

    private static final class WinBindings implements Bindings {
        private final User32Util.MessageLoopThread loop=new User32Util.MessageLoopThread();
        private final User32Api user=Native.load("user32",User32Api.class,W32APIOptions.UNICODE_OPTIONS);
        private final Kernel32Api kernel=Native.load("kernel32",Kernel32Api.class,W32APIOptions.UNICODE_OPTIONS);
        private volatile boolean diagnostic;
        private final StringBuilder timings=new StringBuilder();
        private String openFailure="";
        @Override public void diagnostics(boolean enabled){diagnostic=enabled;if(enabled){timings.setLength(0);openFailure="";}}
        @Override public String readDiagnostics(){return timings.toString()+openFailure;}
        private <T>T timed(String stage,java.util.concurrent.Callable<T> action)throws Exception{
            if(!diagnostic)return action.call();long start=System.nanoTime();
            try{return action.call();}finally{timings.append(stage).append("_us=").append((System.nanoTime()-start)/1000).append(' ');}
        }
        private String openFailureFields(int error){
            try{
                WinDef.HWND opened=user.GetOpenClipboardWindow();
                if(opened==null||Pointer.nativeValue(opened.getPointer())==0)return "open_error="+error+" open_hwnd=unknown open_pid=unknown open_process=unknown";
                com.sun.jna.ptr.IntByReference pid=new com.sun.jna.ptr.IntByReference();int tid=user.GetWindowThreadProcessId(opened,pid);
                long processId=Integer.toUnsignedLong(pid.getValue());String name="unknown";
                try{if(processId>0)name=ProcessHandle.of(processId).flatMap(h->h.info().command()).map(c->java.nio.file.Path.of(c).getFileName().toString()).orElse("unknown");}catch(RuntimeException ignored){}
                name=name.replaceAll("[^A-Za-z0-9_.-]","_");if(name.length()>96)name=name.substring(0,96);
                return "open_error="+error+" open_hwnd=0x"+Long.toHexString(Pointer.nativeValue(opened.getPointer()))+" open_pid="+processId+" open_tid="+Integer.toUnsignedLong(tid)+" open_process="+name;
            }catch(RuntimeException|LinkageError unavailable){return "open_error="+error+" open_holder=unavailable";}
        }
        private WinDef.HWND hwnd;
        @Override public void start()throws Exception{
            try{loop.start();}catch(Throwable error){throw new StartupFailure(StartupStage.START_LOOP,"MessageLoopThread.start",null,error);}
            try{invoke(()->{
                    hwnd=createMessageOnlyWindow(parent->user.CreateWindowExW(0,"STATIC","Every Sync Clipboard",0,0,0,0,0,parent,null,kernel.GetModuleHandleW(null),null));
                    if(hwnd==null||Pointer.nativeValue(hwnd.getPointer())==0){int lastError=kernel.GetLastError();throw new StartupFailure(StartupStage.CREATE_WINDOW,"CreateWindowExW",lastError,null);}
                    return null;
                });
            }catch(StartupFailure failure){throw failure;}
            catch(Throwable error){throw new StartupFailure(StartupStage.CREATE_WINDOW,"CreateWindowExW",null,error);}
        }
        @Override public long revision()throws Exception{return invoke(()->{long value=Integer.toUnsignedLong(user.GetClipboardSequenceNumber());return value==0?-1:value;});}
        @Override public boolean open()throws Exception{return invoke(()->{
            boolean ok=user.OpenClipboard(hwnd);int error=ok?0:kernel.GetLastError();
            if(diagnostic&&!ok)openFailure=openFailureFields(error);return ok;
        });}
        @Override public void closeClipboard()throws Exception{invoke(()->{user.CloseClipboard();return null;});}
        @Override public Object allocate(String text)throws Exception{return invoke(()->{
            long byteCount=(text.length()+1L)*2L;Pointer memory=kernel.GlobalAlloc(GMEM_MOVEABLE,byteCount);
            if(memory==null||Pointer.nativeValue(memory)==0)throw new IllegalStateException("GlobalAlloc failed");
            Pointer locked=kernel.GlobalLock(memory);if(locked==null||Pointer.nativeValue(locked)==0){kernel.GlobalFree(memory);throw new IllegalStateException("GlobalLock failed");}
            try{byte[] bytes=new byte[64*1024];long offset=0;int charOffset=0;
                while(charOffset<text.length()){
                    int chars=Math.min(bytes.length/2,text.length()-charOffset);
                    if(charOffset+chars<text.length()&&Character.isHighSurrogate(text.charAt(charOffset+chars-1))&&Character.isLowSurrogate(text.charAt(charOffset+chars)))chars--;
                    for(int i=0;i<chars;i++){char value=text.charAt(charOffset+i);bytes[i*2]=(byte)value;bytes[i*2+1]=(byte)(value>>>8);}
                    int count=chars*2;locked.write(offset,bytes,0,count);offset+=count;charOffset+=chars;
                }
                locked.setShort(offset,(short)0);
            }finally{kernel.GlobalUnlock(memory);}
            return memory;
        });}
        @Override public void free(Object memory)throws Exception{if(memory instanceof Pointer pointer)invoke(()->{kernel.GlobalFree(pointer);return null;});}
        @Override public void empty()throws Exception{if(!invoke(user::EmptyClipboard))throw new IOException("EmptyClipboard failed");}
        @Override public void set(Object memory)throws Exception{Pointer result=invoke(()->user.SetClipboardData(CF_UNICODETEXT,(Pointer)memory));if(result==null||Pointer.nativeValue(result)==0)throw new IOException("SetClipboardData failed");}
        @Override public String read(long maxBytes)throws Exception{long queued=diagnostic?System.nanoTime():0;return invoke(()->{
            if(diagnostic)timings.append("read_queue_us=").append((System.nanoTime()-queued)/1000).append(' ');
            if(!timed("format_check",()->user.IsClipboardFormatAvailable(CF_UNICODETEXT)))return null;
            Pointer data=timed("get_data",()->user.GetClipboardData(CF_UNICODETEXT));
            if(data==null||Pointer.nativeValue(data)==0)throw new IOException("GetClipboardData failed");long size=timed("global_size",()->kernel.GlobalSize(data));
            if(size<2)throw new IOException("Windows 剪贴板文本长度无效");if(size>maxBytes)throw new IOException("Windows 剪贴板文本超过传输上限");if((size&1L)!=0)throw new IOException("Windows 剪贴板 UTF-16 数据长度无效");
            Pointer locked=timed("global_lock",()->kernel.GlobalLock(data));if(locked==null||Pointer.nativeValue(locked)==0)throw new IOException("GlobalLock clipboard data failed");
            long copyStarted=diagnostic?System.nanoTime():0;
            try{StringBuilder text=new StringBuilder();byte[] chunk=new byte[64*1024];long offset=0;long maxChars=EncryptedFrameCodec.maxEncodedFrameChars();
                while(offset+1<size){int count=(int)Math.min(chunk.length,size-offset);count&=~1;locked.read(offset,chunk,0,count);
                    for(int i=0;i<count;i+=2){int low=chunk[i]&0xff,high=chunk[i+1]&0xff;if(low==0&&high==0)return text.toString();text.append((char)(low|(high<<8)));if(text.length()>maxChars)throw new IOException("Windows 剪贴板文本超过传输上限");}
                    offset+=count;
                }
                return text.toString();
            }finally{if(diagnostic)timings.append("copy_decode_us=").append((System.nanoTime()-copyStarted)/1000).append(' ');timed("global_unlock",()->kernel.GlobalUnlock(data));}
        });}
        @Override public String backend(){return "Windows";}
        @Override public void stop(){
            try{if(loop.isAlive())invoke(()->{if(hwnd!=null)user.DestroyWindow(hwnd);hwnd=null;return null;});}catch(Throwable ignored){}
            try{loop.exit();}catch(Throwable ignored){}
            try{loop.join(2_000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        }
        private <T>T invoke(java.util.concurrent.Callable<T> call)throws Exception{try{return loop.runOnThread(call);}catch(ExecutionException wrapper){Throwable cause=wrapper.getCause();if(cause instanceof Exception exception)throw exception;if(cause instanceof Error error)throw error;throw wrapper;}}
    }
    private interface User32Api extends StdCallLibrary {
        WinDef.HWND CreateWindowExW(int exStyle,String className,String title,int style,int x,int y,int width,int height,WinDef.HWND parent,WinDef.HMENU menu,WinDef.HINSTANCE instance,Pointer param);
        int GetClipboardSequenceNumber();WinDef.HWND GetOpenClipboardWindow();int GetWindowThreadProcessId(WinDef.HWND hwnd,com.sun.jna.ptr.IntByReference pid);
        boolean DestroyWindow(WinDef.HWND hwnd);boolean IsClipboardFormatAvailable(int format);boolean OpenClipboard(WinDef.HWND hwnd);boolean CloseClipboard();boolean EmptyClipboard();Pointer SetClipboardData(int format,Pointer memory);Pointer GetClipboardData(int format);
    }
    private interface Kernel32Api extends StdCallLibrary {
        WinDef.HINSTANCE GetModuleHandleW(String name);int GetLastError();Pointer GlobalAlloc(int flags,long bytes);Pointer GlobalFree(Pointer memory);Pointer GlobalLock(Pointer memory);boolean GlobalUnlock(Pointer memory);long GlobalSize(Pointer memory);
    }
}
