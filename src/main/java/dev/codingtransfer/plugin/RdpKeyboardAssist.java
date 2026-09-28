package dev.codingtransfer.plugin;

import com.sun.jna.platform.win32.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.NativeLibrary;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Best-effort HOST input pulse. Never activates a window or retries a key sequence. */
final class RdpKeyboardAssist {
    private RdpKeyboardAssist(){}
    static String pulse(boolean left) throws Exception {
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        if(os.contains("mac"))return mac(left);
        if(os.contains("win"))return windows(left);
        return "unsupported";
    }
    private static String mac(boolean left)throws Exception {
        // Use the hardware event source, so synthetic events do not masquerade as user activity.
        double idle=NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
                .getFunction("CGEventSourceSecondsSinceLastEventType").invokeDouble(new Object[]{1,-1});
        if(idle<1.0)return "user_active";
        var graphics=NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics");
        for(short key:new short[]{54,55,56,58,59,60,61,62,123,124})
            if((graphics.getFunction("CGEventSourceKeyState").invokeInt(new Object[]{1,key})&0xff)!=0)return "user_active";

        // System Events targets the already-frontmost process. Never activate Jump or change focus.
        String script="""
            tell application "System Events"
                set frontProcess to first application process whose frontmost is true
                set appId to bundle identifier of frontProcess
                if appId is not "com.p5sys.jump.mac.viewer" and appId is not "com.microsoft.rdc.macos" then return "not_rdp"
                tell frontProcess
                    if (count of windows) is 0 then return "no_window"
                    if not frontmost then return "focus_changed"
                    key code %d
                end tell
                return "sent"
            end tell
            """.formatted(left?123:124);
        Process process=new ProcessBuilder("/usr/bin/osascript","-e",script).redirectErrorStream(true).start();
        if(!process.waitFor(3,TimeUnit.SECONDS)){process.destroyForcibly();return "timeout";}
        String result=new String(process.getInputStream().readNBytes(256),StandardCharsets.UTF_8).strip();
        if(process.exitValue()!=0)return "permission_or_os_error";
        return switch(result){case "sent","not_rdp","no_window","focus_changed"->result;default->"os_error";};
    }
    private static String windows(boolean left)throws Exception {
        User32 user=User32.INSTANCE;Kernel32 kernel=Kernel32.INSTANCE;
        WinUser.LASTINPUTINFO idle=new WinUser.LASTINPUTINFO();idle.cbSize=idle.size();
        if(!user.GetLastInputInfo(idle))return "os_error";
        if(Integer.toUnsignedLong(kernel.GetTickCount()-idle.dwTime)<1000)return "user_active";
        WinDef.HWND window=user.GetForegroundWindow();if(window==null)return "no_window";
        IntByReference pid=new IntByReference();user.GetWindowThreadProcessId(window,pid);
        WinNT.HANDLE process=kernel.OpenProcess(0x1000,false,pid.getValue());
        if(process==null)return "os_error";
        char[] path=new char[32768];IntByReference size=new IntByReference(path.length);String name;
        try{if(!kernel.QueryFullProcessImageName(process,0,path,size))return "os_error";name=new String(path,0,size.getValue()).toLowerCase(Locale.ROOT);}
        finally{kernel.CloseHandle(process);}
        name=name.substring(Math.max(name.lastIndexOf('\\'),name.lastIndexOf('/'))+1);
        if(!name.equals("mstsc.exe")&&!name.equals("jumpdesktop.exe"))return "not_rdp";
        for(int key:new int[]{0x10,0x11,0x12,0x5b,0x5c,0x25,0x27})if((user.GetAsyncKeyState(key)&0x8000)!=0)return "user_active";
        WinUser.INPUT[] inputs=(WinUser.INPUT[])new WinUser.INPUT().toArray(2);
        for(int i=0;i<2;i++){
            inputs[i].type=new WinDef.DWORD(1);inputs[i].input.setType(WinUser.KEYBDINPUT.class);
            inputs[i].input.ki.wVk=new WinDef.WORD(left?0x25:0x27);
            inputs[i].input.ki.wScan=new WinDef.WORD(0);
            inputs[i].input.ki.dwFlags=new WinDef.DWORD(1|((i%2==1)?2:0));
            inputs[i].input.ki.time=new WinDef.DWORD(0);inputs[i].input.ki.dwExtraInfo=new BaseTSD.ULONG_PTR(0);
            inputs[i].write();
        }
        if(!window.equals(user.GetForegroundWindow()))return "focus_changed";
        int sent=user.SendInput(new WinDef.DWORD(2),inputs,inputs[0].size()).intValue();
        if(sent>0&&sent<2){
            // Release a possibly pressed key only; never retry a key-down sequence.
            WinUser.INPUT[] release=(WinUser.INPUT[])new WinUser.INPUT().toArray(1);
            for(int i=0;i<1;i++){release[i].type=new WinDef.DWORD(1);release[i].input.setType(WinUser.KEYBDINPUT.class);
                release[i].input.ki.wVk=new WinDef.WORD(left?0x25:0x27);release[i].input.ki.dwFlags=new WinDef.DWORD(3);release[i].write();}
            user.SendInput(new WinDef.DWORD(1),release,release[0].size());
        }
        return sent==2?"sent":"os_error";
    }
}
