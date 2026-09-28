package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Small durable transport state; business inboxes and outgoing snapshots remain separate. */
public final class TransferSessionStore {
    private static final int MAX_FRAME_CHARS = EncryptedFrameCodec.maxEncodedFrameChars();
    private final Path root;
    private final Path contextFile;
    public record Context(String session,String scopeId,String transferId,ReliableTransferEngine.Kind kind,ReliableTransferEngine.Direction direction,int total) {}

    public TransferSessionStore(Path workspace) throws IOException {
        root = SafeFiles.state(workspace).resolve("transfer-sessions");
        SafeFiles.noLinks(root);
        Files.createDirectories(root);
        SafeFiles.noLinks(root);
        contextFile=root.resolve("current.context");SafeFiles.noLinks(contextFile);
    }

    /** Persist one transport identity so panels can offer explicit recovery after restart. */
    public synchronized void saveContext(Context c)throws IOException{
        try{if(!UUID.fromString(c.session()).toString().equals(c.session()))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("无效传输会话",e);}
        if(c.scopeId()==null||!c.scopeId().matches("[A-Za-z0-9_.:-]{1,128}")||c.transferId()==null||!c.transferId().matches("[A-Za-z0-9_.:-]{1,128}")||c.kind()==null||c.direction()==null||c.total()<1||c.total()>1_000_000)throw new IOException("传输上下文无效");
        Context old=currentContext();if(old!=null&&(!old.session().equals(c.session())||!old.scopeId().equals(c.scopeId())||!old.transferId().equals(c.transferId())||old.kind()!=c.kind()||old.direction()!=c.direction()||old.total()!=c.total()))throw new IOException("存在未完成传输 "+old.session()+"；请先继续确认或结束后恢复");
        String data=String.join("\n",c.session(),c.scopeId(),c.transferId(),c.kind().name(),c.direction().name(),Integer.toString(c.total()))+"\n";
        SafeFiles.atomicWrite(contextFile,data.getBytes(StandardCharsets.UTF_8));
    }
    public synchronized Context currentContext()throws IOException{
        SafeFiles.noLinks(contextFile);if(!Files.exists(contextFile))return null;
        String[] f=new String(SafeFiles.read(contextFile,2048),StandardCharsets.UTF_8).split("\\n",-1);
        if(f.length!=7||!f[6].isEmpty())throw new IOException("传输上下文损坏");
        try{Context c=new Context(f[0],f[1],f[2],ReliableTransferEngine.Kind.valueOf(f[3]),ReliableTransferEngine.Direction.valueOf(f[4]),Integer.parseInt(f[5]));saveContextValidate(c);return c;}catch(RuntimeException e){throw new IOException("传输上下文字段无效",e);}
    }
    /** Detaches one explicitly ended session without deleting frames, business inboxes, or completion records. */
    public synchronized boolean clearContext(String expectedSession)throws IOException{
        try{if(!UUID.fromString(expectedSession).toString().equals(expectedSession))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("无效传输会话",e);}
        Context current=currentContext();if(current==null||!current.session().equals(expectedSession))return false;Files.deleteIfExists(contextFile);return true;
    }
    private static void saveContextValidate(Context c)throws IOException{try{if(!UUID.fromString(c.session()).toString().equals(c.session()))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("无效传输会话",e);}if(c.scopeId()==null||!c.scopeId().matches("[A-Za-z0-9_.:-]{1,128}")||c.transferId()==null||!c.transferId().matches("[A-Za-z0-9_.:-]{1,128}")||c.kind()==null||c.direction()==null||c.total()<1||c.total()>1_000_000)throw new IOException("传输上下文字段无效");}

    /** Store the single unacknowledged frame so retries reuse the exact ciphertext. */
    public void saveFrame(String session, String frame) throws IOException {
        if (frame == null || frame.isEmpty() || frame.length() > MAX_FRAME_CHARS) throw new IOException("传输帧为空或过大");
        Path file = sessionDir(session).resolve("current.frame");
        byte[] encoded = frame.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file)) {
            if (!java.util.Arrays.equals(SafeFiles.read(file, MAX_FRAME_CHARS), encoded)) throw new IOException("未确认的传输帧不可替换");
            return;
        }
        SafeFiles.atomicWrite(file, encoded);
    }

    public String readFrame(String session) throws IOException {
        Path file = sessionDir(session).resolve("current.frame");
        if (!Files.exists(file)) throw new IOException("传输帧不存在");
        return new String(SafeFiles.read(file, MAX_FRAME_CHARS), StandardCharsets.UTF_8);
    }

    public boolean hasFrame(String session) throws IOException {
        Path file = sessionDir(session).resolve("current.frame");
        SafeFiles.noLinks(file);
        return Files.exists(file);
    }

    public void clearFrame(String session) throws IOException {
        Path file = sessionDir(session).resolve("current.frame");
        SafeFiles.noLinks(file);
        Files.deleteIfExists(file);
    }

    public void saveState(String session, byte[] state) throws IOException {
        if (state == null || state.length == 0 || state.length > 256 * 1024) throw new IOException("传输状态过大或为空");
        SafeFiles.atomicWrite(sessionDir(session).resolve("state.bin"), state);
    }

    public byte[] readState(String session) throws IOException {
        Path file = sessionDir(session).resolve("state.bin");
        if (!Files.exists(file)) throw new IOException("传输状态不存在");
        return SafeFiles.read(file, 256 * 1024);
    }

    public boolean hasState(String session) throws IOException {
        Path file = sessionDir(session).resolve("state.bin");
        SafeFiles.noLinks(file);
        return Files.exists(file);
    }

    public void clearSession(String session) throws IOException {
        Context context=currentContext();
        Path dir = sessionDir(session);
        SafeFiles.noLinks(dir);
        if (!Files.exists(dir)) { if(context!=null&&context.session().equals(session))Files.deleteIfExists(contextFile); return; }
        try (var paths = Files.list(dir)) {
            for (Path file : paths.toList()) {
                SafeFiles.noLinks(file);
                String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !java.util.Set.of("state.bin", "current.frame").contains(name))
                    throw new IOException("传输会话含未知文件，拒绝删除");
            }
        }
        try (var paths = Files.list(dir)) { for (Path file : paths.toList()) Files.delete(file); }
        Files.delete(dir);
        if(context!=null&&context.session().equals(session))Files.deleteIfExists(contextFile);
    }

    private Path sessionDir(String session) throws IOException {
        try {
            if (!UUID.fromString(session).toString().equals(session)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IOException("无效传输会话", e); }
        Path dir = root.resolve(session);
        SafeFiles.noLinks(dir);
        return dir;
    }

}
