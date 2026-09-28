package dev.codingtransfer.plugin;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import dev.codingtransfer.core.EncryptedFrameCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** AppKit NSPasteboard adapter. Calls are serialized and each call has its own autorelease pool. */
final class MacClipboardBackend implements NativeClipboardAccess.Backend {
    private static final String STRING_TYPE="public.utf8-plain-text";
    private final ObjcBridge objc;
    private final Function strnlen=NativeLibrary.getInstance("System").getFunction("strnlen");
    private final MacMainQueue mainQueue;
    private volatile boolean closed;
    private long writeRevision=-1;
    @Override public synchronized long lastWriteRevision(){return writeRevision;}
    @Override public synchronized long revision()throws Exception{
        ensureOpen();return onMainQueue(()->withPool(()->{
            Pointer pasteboard=requirePointer(objc.object("generalPasteboard",objc.classNamed("NSPasteboard")),"NSPasteboard.generalPasteboard");
            return objc.integer("changeCount",pasteboard);
        }));
    }

    MacClipboardBackend(){this(new JnaObjcBridge(),new MacMainQueue());}
    MacClipboardBackend(ObjcBridge objc,MacMainQueue mainQueue){this.objc=objc;this.mainQueue=mainQueue;}
    @Override public String backendName(){return "macOS-AppKit";}

    @Override public synchronized String read()throws Exception {
        ensureOpen();return onMainQueue(()->withPool(()->{
            Pointer pasteboard=requirePointer(objc.object("generalPasteboard",objc.classNamed("NSPasteboard")),"NSPasteboard.generalPasteboard");
            Pointer type=nsString(STRING_TYPE),string=objc.object("stringForType:",pasteboard,type);
            if(string==null||Pointer.nativeValue(string)==0)return null;
            Pointer utf8=objc.object("UTF8String",string);
            if(utf8==null||Pointer.nativeValue(utf8)==0)return null;
            long maxChars=EncryptedFrameCodec.maxEncodedFrameChars();long byteLimit=(maxChars+1L)*4L;
            long length=strnlen.invokeLong(new Object[]{utf8,byteLimit});
            if(length>=byteLimit)throw new IOException("macOS 剪贴板文本超过传输上限");
            StringBuilder value=new StringBuilder();long offset=0;
            while(offset<length){
                int count=(int)Math.min(1024L*1024L,length-offset);byte[] bytes=utf8.getByteArray(offset,count);
                int safe=count;
                if(offset+count<length){
                    int lead=safe-1;while(lead>=0&&(bytes[lead]&0xc0)==0x80)lead--;
                    if(lead<0)throw new IOException("macOS 剪贴板 UTF-8 边界无效");
                    int first=bytes[lead]&0xff;int expected=first<0x80?1:(first&0xe0)==0xc0?2:(first&0xf0)==0xe0?3:(first&0xf8)==0xf0?4:1;
                    if(count-lead<expected)safe=lead;
                }
                value.append(new String(bytes,0,safe,StandardCharsets.UTF_8));offset+=safe;
                if(value.length()>maxChars)throw new IOException("macOS 剪贴板文本超过传输上限");
            }
            return value.toString();
        }));
    }

    @Override public synchronized void write(String value)throws Exception {
        ensureOpen();if(value==null)throw new IOException("不能写入空引用文本");
        long maxChars=EncryptedFrameCodec.maxEncodedFrameChars();
        if(value.indexOf('\0')>=0)throw new IOException("剪贴板文本不能包含 NUL 字符");
        if(value.length()>maxChars)throw new IOException("剪贴板文本超过传输上限");
        writeRevision=-1;onMainQueue(()->withPool(()->{
            Pointer pasteboard=requirePointer(objc.object("generalPasteboard",objc.classNamed("NSPasteboard")),"NSPasteboard.generalPasteboard");
            Pointer text=nsString(value),type=nsString(STRING_TYPE);
            long ownedRevision=objc.integer("clearContents",pasteboard);
            if(!objc.bool("setString:forType:",pasteboard,text,type))throw new IOException("NSPasteboard setString:forType: failed");
            if(objc.integer("changeCount",pasteboard)==ownedRevision)writeRevision=ownedRevision;
            return null;
        }));
    }

    private <T>T onMainQueue(Throwing<T> action)throws Exception{return mainQueue.call(action::run);}
    private <T>T withPool(Throwing<T> action)throws Exception {
        Pointer poolClass=objc.classNamed("NSAutoreleasePool"),allocated=objc.object("alloc",poolClass),pool=objc.object("init",allocated);
        requirePointer(pool,"NSAutoreleasePool init");
        try{return action.run();}finally{objc.voidMessage("release",pool);}
    }
    private Pointer nsString(String value)throws Exception{return requirePointer(objc.object("stringWithUTF8String:",objc.classNamed("NSString"),new MemoryString(value)),"NSString UTF-8 conversion");}
    private static Pointer requirePointer(Pointer pointer,String operation)throws NativeClipboardAccess.InitializationFailure{
        if(pointer==null||Pointer.nativeValue(pointer)==0)throw new NativeClipboardAccess.InitializationFailure("原生剪贴板接口初始化失败："+operation+" 返回空值");
        return pointer;
    }
    private void ensureOpen()throws IOException{if(closed)throw new IOException("macOS 剪贴板后端已关闭");}
    @Override public void close(){closed=true;}

    interface ObjcBridge {
        Pointer classNamed(String name)throws Exception;
        Pointer object(String selector,Pointer receiver,Pointer...arguments)throws Exception;
        boolean bool(String selector,Pointer receiver,Pointer...arguments)throws Exception;
        long integer(String selector,Pointer receiver,Pointer...arguments)throws Exception;
        void voidMessage(String selector,Pointer receiver,Pointer...arguments)throws Exception;
    }

    private static final class JnaObjcBridge implements ObjcBridge {
        private final NativeLibrary objc=NativeLibrary.getInstance("objc");
        private final Function getClass=objc.getFunction("objc_getClass"),registerSelector=objc.getFunction("sel_registerName"),send=objc.getFunction("objc_msgSend");
        @Override public Pointer classNamed(String name)throws Exception{return requirePointer(getClass.invokePointer(new Object[]{new MemoryString(name)}),"objc_getClass("+name+")");}
        @Override public Pointer object(String selector,Pointer receiver,Pointer...arguments)throws Exception{return send.invokePointer(callArguments(selector,receiver,arguments));}
        @Override public boolean bool(String selector,Pointer receiver,Pointer...arguments)throws Exception{Byte value=(Byte)send.invoke(Byte.class,callArguments(selector,receiver,arguments));return value!=null&&value.byteValue()!=0;}
        @Override public long integer(String selector,Pointer receiver,Pointer...arguments)throws Exception{return ((NativeLong)send.invoke(NativeLong.class,callArguments(selector,receiver,arguments))).longValue();}
        @Override public void voidMessage(String selector,Pointer receiver,Pointer...arguments)throws Exception{send.invokeVoid(callArguments(selector,receiver,arguments));}
        private Object[] callArguments(String selector,Pointer receiver,Pointer...arguments)throws Exception{
            requirePointer(receiver,"objc_msgSend receiver for "+selector);
            Pointer sel=requirePointer(registerSelector.invokePointer(new Object[]{new MemoryString(selector)}),"sel_registerName("+selector+")");
            Object[] call=new Object[2+arguments.length];call[0]=receiver;call[1]=sel;System.arraycopy(arguments,0,call,2,arguments.length);return call;
        }
    }

    private static final class MemoryString extends Memory {
        private static final int CHUNK=64*1024;
        MemoryString(String text){this(text,encodedLength(text));}
        private MemoryString(String text,long size){super(size+1);byte[] bytes=new byte[CHUNK];long offset=0;int used=0;
            for(int index=0;index<text.length();){int codePoint=text.codePointAt(index);int chars=Character.charCount(codePoint);if(Character.isSurrogate(text.charAt(index))&&(chars==1))codePoint='?';index+=chars;
                int needed=codePoint<=0x7f?1:codePoint<=0x7ff?2:codePoint<=0xffff?3:4;
                if(used+needed>CHUNK){write(offset,bytes,0,used);offset+=used;used=0;}
                if(codePoint<=0x7f){used=put(bytes,used,(byte)codePoint);}
                else if(codePoint<=0x7ff){used=put(bytes,used,(byte)(0xc0|(codePoint>>6)));used=put(bytes,used,(byte)(0x80|(codePoint&0x3f)));}
                else if(codePoint<=0xffff){used=put(bytes,used,(byte)(0xe0|(codePoint>>12)));used=put(bytes,used,(byte)(0x80|((codePoint>>6)&0x3f)));used=put(bytes,used,(byte)(0x80|(codePoint&0x3f)));}
                else{used=put(bytes,used,(byte)(0xf0|(codePoint>>18)));used=put(bytes,used,(byte)(0x80|((codePoint>>12)&0x3f)));used=put(bytes,used,(byte)(0x80|((codePoint>>6)&0x3f)));used=put(bytes,used,(byte)(0x80|(codePoint&0x3f)));}
            }
            if(used>0){write(offset,bytes,0,used);offset+=used;}setByte(offset,(byte)0);
        }
        private static int put(byte[] buffer,int at,byte value){buffer[at]=value;return at+1;}
        private static long encodedLength(String text){long length=0;for(int index=0;index<text.length();){int cp=text.codePointAt(index);int chars=Character.charCount(cp);if(Character.isSurrogate(text.charAt(index))&&chars==1)cp='?';index+=chars;length+=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;}return length;}
    }
    @FunctionalInterface private interface Throwing<T>{T run()throws Exception;}
}
