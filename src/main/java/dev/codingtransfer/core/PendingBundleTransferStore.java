package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** One durable pointer to a VM Bundle whose automatic transfer has actually started. */
public final class PendingBundleTransferStore {
    public record Transfer(String projectId,String session,String transferId,int total){}
    private final Path file;
    public PendingBundleTransferStore(Path workspace)throws IOException{Path root=SafeFiles.state(workspace).resolve("bundle-state").resolve("inflight");SafeFiles.noLinks(root);Files.createDirectories(root);file=root.resolve("current.context");SafeFiles.noLinks(file);}
    public synchronized void mark(Transfer t)throws IOException{validate(t);Transfer old=read();if(old!=null&&!old.equals(t))throw new IOException("项目 "+old.projectId()+" 有一个已开始但未确认的 VM Bundle 传输");SafeFiles.atomicWrite(file,(t.projectId()+"\n"+t.session()+"\n"+t.transferId()+"\n"+t.total()+"\n").getBytes(StandardCharsets.US_ASCII));}
    public synchronized Transfer read()throws IOException{SafeFiles.noLinks(file);if(!Files.exists(file))return null;String[] f=new String(SafeFiles.read(file,512),StandardCharsets.US_ASCII).split("\n",-1);if(f.length!=5||!f[4].isEmpty())throw new IOException("VM Bundle 传输索引损坏");try{Transfer t=new Transfer(f[0],f[1],f[2],Integer.parseInt(f[3]));validate(t);return t;}catch(RuntimeException e){throw new IOException("VM Bundle 传输索引字段无效",e);}}
    public synchronized boolean clear(String expectedProject,String expectedSession)throws IOException{Transfer t=read();if(t==null||!t.projectId().equals(expectedProject)||!t.session().equals(expectedSession))return false;Files.deleteIfExists(file);return true;}
    private static void validate(Transfer t)throws IOException{if(t.projectId()==null||!t.projectId().matches("[A-Za-z0-9_.:-]{1,128}")||t.transferId()==null||!t.transferId().matches("[A-Za-z0-9_.:-]{1,128}")||t.total()<1||t.total()>1_000_000)throw new IOException("VM Bundle 传输索引无效");try{if(!UUID.fromString(t.session()).toString().equals(t.session()))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("VM Bundle 传输会话无效",e);}}
}
