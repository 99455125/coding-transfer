package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** One durable pointer to a VM WORKTREE awaiting explicit review and apply. */
public final class PendingWorktreeStore {
    public record Pending(String projectId,String outerSession,String innerSession){}
    private final Path file;
    public PendingWorktreeStore(Path workspace)throws IOException{Path root=SafeFiles.state(workspace);file=root.resolve("pending-worktree.context");SafeFiles.noLinks(file);}
    public synchronized void save(Pending p)throws IOException{
        if(p==null||p.projectId()==null||!p.projectId().matches("[A-Za-z0-9_.:-]{1,128}"))throw new IOException("待处理 WORKTREE 项目无效");
        uuid(p.outerSession());uuid(p.innerSession());Pending old=read();if(old!=null&&(!old.projectId().equals(p.projectId())||!old.outerSession().equals(p.outerSession())||!old.innerSession().equals(p.innerSession())))throw new IOException("项目 "+old.projectId()+" 仍有待处理 WORKTREE；请先预览、应用或清理");SafeFiles.atomicWrite(file,(p.projectId()+"\n"+p.outerSession()+"\n"+p.innerSession()+"\n").getBytes(StandardCharsets.UTF_8));
    }
    public synchronized Pending read()throws IOException{SafeFiles.noLinks(file);if(!Files.exists(file))return null;String[] f=new String(SafeFiles.read(file,512),StandardCharsets.UTF_8).split("\n",-1);if(f.length!=4||!f[3].isEmpty())throw new IOException("待处理 WORKTREE 索引损坏");Pending p=new Pending(f[0],f[1],f[2]);saveValidate(p);return p;}
    private static void saveValidate(Pending p)throws IOException{if(!p.projectId().matches("[A-Za-z0-9_.:-]{1,128}"))throw new IOException("待处理 WORKTREE 项目无效");uuid(p.outerSession());uuid(p.innerSession());}
    private static void uuid(String value)throws IOException{try{if(!UUID.fromString(value).toString().equals(value))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("待处理 WORKTREE 会话无效",e);}}
    public synchronized void clear(String expectedOuter)throws IOException{Pending p=read();if(p!=null&&p.outerSession().equals(expectedOuter))Files.deleteIfExists(file);}
    public synchronized void clear(Pending expected)throws IOException{Pending p=read();if(p!=null&&expected!=null&&p.equals(expected))Files.deleteIfExists(file);}
}
