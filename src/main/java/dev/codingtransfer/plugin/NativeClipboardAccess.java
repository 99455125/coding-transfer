package dev.codingtransfer.plugin;

import java.io.IOException;
import java.util.Locale;

/** Selects exactly one native OS clipboard implementation; there is no UI-toolkit fallback. */
final class NativeClipboardAccess implements TransferCoordinator.ClipboardAccess,AutoCloseable {
    interface Backend extends AutoCloseable {String backendName();default long revision()throws Exception{return -1;}default long lastWriteRevision(){return -1;}String read()throws Exception;default String read(boolean diagnostics)throws Exception{return read();}default String readDiagnostics(){return "";}void write(String value)throws Exception;@Override void close();}
    private final Backend backend;
    NativeClipboardAccess(Backend backend){this.backend=backend;}
    static NativeClipboardAccess create(){
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        if(os.contains("mac"))return new NativeClipboardAccess(new LazyBackend(MacClipboardBackend::new));
        if(os.contains("win"))return new NativeClipboardAccess(new WindowsClipboardBackend());
        return new NativeClipboardAccess(new Backend(){public String backendName(){return "unsupported";}public String read()throws IOException{throw unsupported();}public void write(String value)throws IOException{throw unsupported();}public void close(){}private InitializationFailure unsupported(){return new InitializationFailure("当前系统不支持原生剪贴板传输："+System.getProperty("os.name"));}});
    }
    @Override public long revision()throws Exception{return backend.revision();}
    @Override public long lastWriteRevision(){return backend.lastWriteRevision();}
    @Override public String backendName(){return backend.backendName();}
    @Override public String readDiagnostics(){return backend.readDiagnostics();}
    @Override public String read()throws Exception{return read(false);}
    @Override public String read(boolean diagnostics)throws Exception{try{return backend.read(diagnostics);}catch(Throwable failure){throwForNativeFailure(failure);return null;}}
    @Override public void write(String value)throws Exception{try{backend.write(value);}catch(Throwable failure){throwForNativeFailure(failure);}}
    @Override public void close(){backend.close();}
    private static void throwForNativeFailure(Throwable failure)throws Exception {
        if(isInitializationFailure(failure))throw new InitializationFailure("原生剪贴板接口初始化失败",failure);
        if(failure instanceof Exception exception)throw exception;
        if(failure instanceof Error error)throw error;
        throw new IOException("原生剪贴板操作失败",failure);
    }
    static boolean isInitializationFailure(Throwable failure){
        for(Throwable item=failure;item!=null;item=item.getCause())if(item instanceof InitializationFailure||item instanceof LinkageError)return true;
        return false;
    }
    static boolean isTerminalFailure(Throwable failure){
        if(isInitializationFailure(failure))return true;
        for(Throwable item=failure;item!=null;item=item.getCause())if(item instanceof MacMainQueue.UncertainOperationException)return true;
        return false;
    }
    static final class InitializationFailure extends IOException {
        InitializationFailure(String message){super(message);}
        InitializationFailure(String message,Throwable cause){super(message,cause);}
    }
    private static final class LazyBackend implements Backend {
        private final java.util.function.Supplier<Backend> factory;private Backend delegate;private boolean closed;
        LazyBackend(java.util.function.Supplier<Backend> factory){this.factory=factory;}
        private synchronized Backend get()throws IOException{if(closed)throw new IOException("原生剪贴板后端已关闭");if(delegate==null)try{delegate=factory.get();}catch(RuntimeException|LinkageError error){throw new InitializationFailure("无法初始化原生剪贴板后端",error);}return delegate;}
        @Override public String backendName(){try{return get().backendName();}catch(IOException e){return "unavailable";}}
        @Override public long revision()throws Exception{return get().revision();}
        @Override public long lastWriteRevision(){return delegate==null?-1:delegate.lastWriteRevision();}
        @Override public String read()throws Exception{return get().read();}
        @Override public void write(String value)throws Exception{get().write(value);}
        @Override public synchronized void close(){closed=true;if(delegate!=null)delegate.close();}
    }
}
