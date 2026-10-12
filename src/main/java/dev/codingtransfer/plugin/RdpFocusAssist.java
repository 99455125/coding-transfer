package dev.codingtransfer.plugin;

import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import java.util.Locale;

/** Focus recovery only; never sends keyboard/mouse input or touches the clipboard. */
final class RdpFocusAssist {
    private RdpFocusAssist(){}
    interface Platform {
        Object foreground() throws Exception;
        Object host() throws Exception;
        boolean supported(Object target) throws Exception;
        boolean idle() throws Exception;
        long inputStamp() throws Exception;
        boolean activate(Object target,Object expected,long inputStamp) throws Exception;
        void settle() throws Exception;
    }
    static String pulse() throws Exception {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        if(os.contains("mac"))return cycle(new Mac());
        if(os.contains("win"))return cycle(new Windows());
        return "unsupported";
    }
    static String cycle(Platform platform)throws Exception {
        long stamp=platform.inputStamp();
        if(!platform.idle())return "user_active";
        Object target=platform.foreground();if(target==null)return "no_window";
        if(!platform.supported(target))return "not_rdp";
        Object host=platform.host();if(host==null||host.equals(target))return "no_window";
        if(!target.equals(platform.foreground()))return "focus_changed";
        if(!platform.idle()||platform.inputStamp()!=stamp)return "user_active";
        boolean restored=false;
        try{
            if(!platform.activate(host,target,stamp))return "os_error";
            platform.settle();
            if(!host.equals(platform.foreground()))return "focus_interrupted";
            if(!platform.idle()||platform.inputStamp()!=stamp)return "focus_interrupted";
            if(!platform.activate(target,host,stamp))return "os_error";
            restored=true;
            return target.equals(platform.foreground())?"switched":"focus_interrupted";
        }finally{
            // Restore our own temporary activation after an error/interruption, but never steal
            // focus from a user-selected app or interrupt fresh user input.
            if(!restored&&host.equals(platform.foreground())&&platform.idle()&&platform.inputStamp()==stamp){
                if(!platform.activate(target,host,stamp))throw new IllegalStateException("Cannot restore remote session focus");
            }
        }
    }
    private static final class Mac implements Platform {
        private final NativeLibrary graphics=NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
        private final NativeLibrary appKit=NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit");
        private final NativeLibrary accessibility=NativeLibrary.getInstance("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices");
        private final NativeLibrary foundation=NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
        private final NativeLibrary objc=NativeLibrary.getInstance("objc");
        private final Function send=objc.getFunction("objc_msgSend"),selector=objc.getFunction("sel_registerName"),getClass=objc.getFunction("objc_getClass");
        private final MacMainQueue queue=new MacMainQueue();
        private Object[] args(String name,Pointer receiver,Object...values){
            Object[] args=new Object[values.length+2];args[0]=receiver;args[1]=selector.invokePointer(new Object[]{name});System.arraycopy(values,0,args,2,values.length);return args;
        }
        private Pointer object(String name,Pointer receiver,Object...values){return send.invokePointer(args(name,receiver,values));}
        private Pointer type(String name){return getClass.invokePointer(new Object[]{name});}
        private Pointer application(Object pid){return object("runningApplicationWithProcessIdentifier:",type("NSRunningApplication"),pid);}
        private <T>T call(MacMainQueue.Work<T> work)throws Exception {
            return queue.call(()->{
                Pointer pool=object("init",object("alloc",type("NSAutoreleasePool")));
                try{return work.run();}finally{send.invokeVoid(args("release",pool));}
            });
        }
        public Object foreground()throws Exception{return call(()->{
            Pointer app=object("frontmostApplication",object("sharedWorkspace",type("NSWorkspace")));
            return app==null?null:send.invokeInt(args("processIdentifier",app));
        });}
        public Object host(){return (int)ProcessHandle.current().pid();}
        public boolean supported(Object target)throws Exception{return call(()->{
            Pointer app=application(target);if(app==null)return false;
            Pointer identifier=object("bundleIdentifier",app);if(identifier==null)return false;
            Pointer utf8=object("UTF8String",identifier);if(utf8==null)return false;
            String id=utf8.getString(0,"UTF-8");return id.equals("com.p5sys.jump.mac.viewer")||id.equals("com.microsoft.rdc.macos");
        });}
        public boolean idle(){
            if(graphics.getFunction("CGEventSourceSecondsSinceLastEventType").invokeDouble(new Object[]{1,-1})<1.0)return false;
            for(short key:new short[]{54,55,56,58,59,60,61,62})if((graphics.getFunction("CGEventSourceKeyState").invokeInt(new Object[]{1,key})&0xff)!=0)return false;
            for(int button=0;button<3;button++)if((graphics.getFunction("CGEventSourceButtonState").invokeInt(new Object[]{1,button})&0xff)!=0)return false;
            return true;
        }
        public long inputStamp(){
            long count=0;for(int type:new int[]{1,2,3,4,5,6,7,10,11,12,22,25,26,27})
                count+=Integer.toUnsignedLong(graphics.getFunction("CGEventSourceCounterForEventType").invokeInt(new Object[]{1,type}));
            return count;
        }
        public boolean activate(Object target,Object expected,long stamp)throws Exception {
            // No queued activation: check after any main-queue delay, then make a bounded AX call
            // from the worker so activating IDEA cannot block its own accessibility main thread.
            if(!expected.equals(foreground())||!idle()||inputStamp()!=stamp)return false;
            if((accessibility.getFunction("AXIsProcessTrusted").invokeInt(new Object[]{})&0xff)==0)
                throw new IllegalStateException("IDEA requires macOS Accessibility permission for focus recovery");
            Pointer app=accessibility.getFunction("AXUIElementCreateApplication").invokePointer(new Object[]{target});
            Pointer attribute=foundation.getFunction("CFStringCreateWithCString").invokePointer(new Object[]{null,"AXFrontmost",0x08000100});
            try{
                if(app==null||attribute==null)throw new IllegalStateException("AX focus interface initialization failed");
                int timeout=accessibility.getFunction("AXUIElementSetMessagingTimeout").invokeInt(new Object[]{app,1.0f});
                if(timeout!=0)throw new IllegalStateException("AX focus timeout setup failed: "+timeout);
                if(!expected.equals(foreground())||inputStamp()!=stamp||!idle())return false;
                int error=accessibility.getFunction("AXUIElementSetAttributeValue").invokeInt(new Object[]{app,attribute,foundation.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)});
                if(error!=0)throw new IllegalStateException("AX focus activation failed: "+error);
                return true;
            }finally{
                if(attribute!=null)foundation.getFunction("CFRelease").invokeVoid(new Object[]{attribute});
                if(app!=null)foundation.getFunction("CFRelease").invokeVoid(new Object[]{app});
            }
        }
        public void settle()throws Exception{Thread.sleep(150);}
    }
    private static final class Windows implements Platform {
        private final User32 user=User32.INSTANCE;private final Kernel32 kernel=Kernel32.INSTANCE;
        public Object foreground(){return user.GetForegroundWindow();}
        public Object host(){
            WinDef.HWND[] found={null};int self=(int)ProcessHandle.current().pid();
            user.EnumWindows((window,data)->{IntByReference pid=new IntByReference();user.GetWindowThreadProcessId(window,pid);
                if(pid.getValue()==self&&user.IsWindowVisible(window)){found[0]=window;return false;}return true;},null);
            return found[0];
        }
        public boolean supported(Object target){
            IntByReference pid=new IntByReference();user.GetWindowThreadProcessId((WinDef.HWND)target,pid);
            WinNT.HANDLE process=kernel.OpenProcess(0x1000,false,pid.getValue());if(process==null)return false;
            char[] path=new char[32768];IntByReference length=new IntByReference(path.length);String name;
            try{if(!kernel.QueryFullProcessImageName(process,0,path,length))return false;name=new String(path,0,length.getValue()).toLowerCase(Locale.ROOT);}
            finally{kernel.CloseHandle(process);}
            name=name.substring(Math.max(name.lastIndexOf('\\'),name.lastIndexOf('/'))+1);
            return name.equals("mstsc.exe")||name.equals("jumpdesktop.exe");
        }
        public boolean idle(){
            WinUser.LASTINPUTINFO input=new WinUser.LASTINPUTINFO();input.cbSize=input.size();
            if(!user.GetLastInputInfo(input)||Integer.toUnsignedLong(kernel.GetTickCount()-input.dwTime)<1000)return false;
            for(int key:new int[]{1,2,4,0x10,0x11,0x12,0x5b,0x5c})if((user.GetAsyncKeyState(key)&0x8000)!=0)return false;
            return true;
        }
        public long inputStamp(){WinUser.LASTINPUTINFO input=new WinUser.LASTINPUTINFO();input.cbSize=input.size();
            if(!user.GetLastInputInfo(input))throw new IllegalStateException("Cannot read Windows last-input timestamp");return Integer.toUnsignedLong(input.dwTime);}
        public boolean activate(Object target,Object expected,long stamp){
            if(!expected.equals(foreground())||!idle()||inputStamp()!=stamp)return false;
            return user.SetForegroundWindow((WinDef.HWND)target);
        }
        public void settle()throws Exception{Thread.sleep(150);}
    }
}
