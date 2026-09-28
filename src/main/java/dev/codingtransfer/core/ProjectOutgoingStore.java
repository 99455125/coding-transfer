package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.UUID;
import static dev.codingtransfer.core.Model.Payload;

/** Exact project payload required to resume an interrupted encrypted send. */
public final class ProjectOutgoingStore {
    private final Path root;
    public ProjectOutgoingStore(Path workspace)throws IOException{root=SafeFiles.state(workspace).resolve("project-transfer-outgoing");SafeFiles.noLinks(root);Files.createDirectories(root);}
    public void save(String session,Payload payload,List<String> parts)throws IOException{
        Path dir=dir(session);Files.createDirectories(dir);SafeFiles.atomicWrite(dir.resolve("payload.bin"),Codec.encode(payload));
        if(parts==null||parts.isEmpty())throw new IOException("项目发送分块为空");ProjectChunkCodec.Part first=ProjectChunkCodec.parse(parts.getFirst());
        if(first.count()!=parts.size())throw new IOException("项目发送分块数量与头部不一致");String inner=first.session(),signature=first.signature();
        for(int i=0;i<parts.size();i++){ProjectChunkCodec.Part part=ProjectChunkCodec.parse(parts.get(i));if(part.index()!=i+1||part.count()!=parts.size()||!part.session().equals(inner)||!part.signature().equals(signature))throw new IOException("项目发送分块身份/边界不一致");SafeFiles.atomicWrite(dir.resolve(String.format(Locale.ROOT,"chunk-%08d.frame",i+1)),parts.get(i).getBytes(StandardCharsets.UTF_8));}
        SafeFiles.atomicWrite(dir.resolve("manifest.txt"),(inner+"\n"+parts.size()+"\n").getBytes(StandardCharsets.US_ASCII));
    }
    public Payload load(String session)throws IOException{Path file=dir(session).resolve("payload.bin");SafeFiles.noLinks(file);if(!Files.isRegularFile(file))throw new IOException("项目发送源未持久保存");return Codec.decode(SafeFiles.read(file,TransferLimits.MAX_PACKET_CHARS));}
    public List<String> loadParts(String session,String expectedInner,int expectedTotal)throws IOException{
        Path dir=dir(session);String[] manifest=new String(SafeFiles.read(dir.resolve("manifest.txt"),512),StandardCharsets.US_ASCII).split("\\n",-1);if(manifest.length!=3||!manifest[2].isEmpty()||(expectedInner!=null&&!manifest[0].equals(expectedInner)))throw new IOException("项目分块恢复索引与传输上下文不匹配");try{if(Integer.parseInt(manifest[1])!=expectedTotal)throw new IOException("项目分块总数与传输上下文不匹配");}catch(NumberFormatException e){throw new IOException("项目分块恢复索引损坏",e);}
        List<String> parts=new ArrayList<>(expectedTotal);String signature=null;StringBuilder encoded=new StringBuilder();
        for(int i=1;i<=expectedTotal;i++){Path file=dir.resolve(String.format(Locale.ROOT,"chunk-%08d.frame",i));String text=new String(SafeFiles.read(file,TransferLimits.MAX_PACKET_CHARS),StandardCharsets.UTF_8);ProjectChunkCodec.Part part=ProjectChunkCodec.parse(text);if(part.index()!=i||part.count()!=expectedTotal||!part.session().equals(manifest[0])||(signature!=null&&!signature.equals(part.signature())))throw new IOException("持久化项目分块顺序/身份不匹配");signature=part.signature();encoded.append(part.data());parts.add(text);}
        byte[] unpacked;try{unpacked=Base64.getDecoder().decode(encoded.toString());}catch(IllegalArgumentException e){throw new IOException("持久化项目分块编码损坏",e);}if(!Arrays.equals(unpacked,Codec.encode(load(session))))throw new IOException("持久化项目分块与发送源不一致");
        return List.copyOf(parts);
    }
    public void clear(String session)throws IOException{Path dir=dir(session);SafeFiles.noLinks(dir);if(!Files.exists(dir))return;try(var list=Files.list(dir)){for(Path f:list.toList()){SafeFiles.noLinks(f);String name=f.getFileName().toString();if(!(name.equals("payload.bin")||name.equals("manifest.txt")||name.matches("chunk-[0-9]{8}\\.frame"))||!Files.isRegularFile(f))throw new IOException("项目发送缓存含未知文件");}}try(var list=Files.list(dir)){for(Path f:list.toList())Files.delete(f);}Files.delete(dir);}
    private Path dir(String session)throws IOException{try{if(!UUID.fromString(session).toString().equals(session))throw new IllegalArgumentException();}catch(RuntimeException e){throw new IOException("无效项目传输会话",e);}Path p=root.resolve(session);SafeFiles.noLinks(p);return p;}
}
