package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

public final class ClipboardTransport {
    private ClipboardTransport() {}
    public record Received(String session, int received, int total, int firstMissing, Payload payload) {}
    private record Part(Map<String,String> fields, int index, int count, String data,boolean extended) {
        String session(){return fields.get("SESSION");}
        String signature(){
            return (extended?"VMSYNC/2\n":"")+"PARTS:"+count+"\n"+fields.entrySet().stream().filter(e -> !Set.of("PART","CHUNK256").contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+":"+e.getValue()+"\n").reduce("",String::concat);
        }
    }
    public static List<String> split(Payload payload, int chunkKb) throws IOException {
        require(chunkKb>=4 && chunkKb<=4096,"分块大小范围 4–4096 KiB");
        byte[] encoded=Codec.encode(payload);String data=Base64.getEncoder().encodeToString(encoded), checksum=hash(encoded),session=UUID.randomUUID().toString();
        int width=chunkKb*1024, count=(int)(((long)data.length()+width-1)/width);List<String> result=new ArrayList<>();
        boolean work=payload.type()==Type.WORKTREE,host=work||payload.type()==Type.ACK;
        for(int i=0;i<count;i++){
            String chunk=data.substring(i*width,(int)Math.min((i+1L)*width,data.length()));
            result.add((payload.git()==null?"VMSYNC/1":"VMSYNC/2")+"\nTYPE:"+payload.type()+"\nSOURCE:"+(host?"HOST":"VM")+"\nTARGET:"+(host?"VM":"HOST")
                +"\nPROJECT:"+payload.project()+"\nSESSION:"+session+"\nPART:"+(i+1)+"/"+count+"\nENCODING:GZIP+BASE64\nSHA256:"+checksum
                +"\nCHUNK256:"+hash(chunk.getBytes(StandardCharsets.US_ASCII))+"\n"+(work?"BASE:":"HEAD:")+payload.head()+"\nDATA:\n"+chunk);
        }
        return List.copyOf(result);
    }
    private static Part parse(String text)throws IOException{
        require(text!=null && text.length()<=4*1024*1024+4096,"剪贴板为空或分块过大");
        text=text.strip().replace("\r\n","\n");int dataAt=text.indexOf("\nDATA:\n");
        require(dataAt>0 && dataAt<2048,"无效 VMSYNC/1 分块");String[] lines=text.substring(0,dataAt).split("\n");
        boolean extended=lines[0].equals("VMSYNC/2");require(extended||lines[0].equals("VMSYNC/1"),"不支持的剪贴板协议");Map<String,String> fields=new TreeMap<>();
        for(int i=1;i<lines.length;i++){
            int colon=lines[i].indexOf(':');require(colon>0,"无效分块头");
            require(fields.putIfAbsent(lines[i].substring(0,colon),lines[i].substring(colon+1))==null,"重复分块头");
        }
        String type=fields.get("TYPE");boolean work="WORKTREE".equals(type),host=work||"ACK".equals(type);require(work||"SNAPSHOT".equals(type)||(extended&&("DELTA".equals(type)||"ACK".equals(type))),"无效 payload 类型");
        require(fields.keySet().equals(Set.of("TYPE","SOURCE","TARGET","PROJECT","SESSION","PART","ENCODING","SHA256","CHUNK256",work?"BASE":"HEAD")),"未知/缺失分块头");
        require(fields.get("SOURCE").equals(host?"HOST":"VM") && fields.get("TARGET").equals(host?"VM":"HOST"),"非法传输方向");
        projectId(fields.get("PROJECT"));require(fields.get("SESSION").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效 session");
        require(fields.get("ENCODING").equals("GZIP+BASE64") && hashValue(fields.get("SHA256")) && hashValue(fields.get("CHUNK256")),"无效编码/checksum");
        require(headValue(fields.get(work?"BASE":"HEAD")),"无效 BASE/HEAD");
        require(fields.get("PART").matches("[1-9][0-9]{0,8}/[1-9][0-9]{0,8}"),"无效分块序号");
        String[] part=fields.get("PART").split("/");int index=Integer.parseInt(part[0]),count=Integer.parseInt(part[1]);
        require(index<=count,"分块数量超限");String data=text.substring(dataAt+7);
        require(!data.isEmpty() && data.length()<=4*1024*1024 && data.matches("[A-Za-z0-9+/]+={0,2}"),"无效 Base64 分块");
        require(hash(data.getBytes(StandardCharsets.US_ASCII)).equals(fields.get("CHUNK256")),"CHECKSUM_ERROR: 分块校验失败，请重新复制");
        return new Part(fields,index,count,data,extended);
    }
    public static Received receive(Path workspace, Config config, String selectedId, String text)throws IOException{
        Part part=parse(text);config.project(selectedId);
        require(part.fields.get("TARGET").equals(config.role().name()),"接收角色不匹配");
        require(part.fields.get("PROJECT").equals(selectedId),"项目不匹配，请选择 "+part.fields.get("PROJECT"));
        Path inbox=SafeFiles.state(workspace).resolve("inbox");SafeFiles.noLinks(inbox);Files.createDirectories(inbox);
        int sessions=0;
        try(var paths=Files.walk(inbox)){
            for(Path p:paths.toList()){
                SafeFiles.noLinks(p);
                if(Files.isDirectory(p)&&!p.equals(inbox)&&p.getParent().equals(inbox))sessions++;
            }
        }
        Path dir=inbox.resolve(part.session());SafeFiles.noLinks(dir);
        require(sessions<32 || Files.exists(dir),"收件箱已达 32 会话，请先清理已完成会话");
        Files.createDirectories(dir);Path meta=dir.resolve("metadata.txt");byte[] signature=part.signature().getBytes(StandardCharsets.UTF_8);
        if(Files.exists(meta))require(Arrays.equals(SafeFiles.read(meta,4096),signature),"混合会话: 元数据不一致");
        else SafeFiles.atomicWrite(meta,signature);
        Path file=dir.resolve(part.index+".txt");byte[] current=text.getBytes(StandardCharsets.UTF_8);
        if(Files.exists(file)){
            Part existing=parse(new String(SafeFiles.read(file,4*1024*1024+4096),StandardCharsets.UTF_8));
            require(existing.signature().equals(part.signature()) && existing.count==part.count && existing.data.equals(part.data),"重复块内容不一致");
        }else SafeFiles.atomicWrite(file,current);
        int received=0,missing=1;StringBuilder joined=new StringBuilder();
        try(var paths=Files.list(dir)){
            List<Path> parts=paths.filter(p->!p.getFileName().toString().equals("metadata.txt")).sorted(Comparator.comparingInt(p->{
                String n=p.getFileName().toString();return n.matches("[1-9][0-9]{0,8}\\.txt")?Integer.parseInt(n.substring(0,n.length()-4)):Integer.MAX_VALUE;
            })).toList();
            for(Path p:parts){
                Part saved=parse(new String(SafeFiles.read(p,4*1024*1024+4096),StandardCharsets.UTF_8));
                require(saved.signature().equals(part.signature()) && saved.count==part.count && p.getFileName().toString().equals(saved.index+".txt"),"持久化会话不一致");
                joined.append(saved.data);received++;if(saved.index==missing)missing++;
            }
        }
        require(received<=part.count,"多余分块");
        if(received!=part.count)return new Received(part.session(),received,part.count,missing,null);
        byte[] packed;try{packed=Base64.getDecoder().decode(joined.toString());}catch(IllegalArgumentException e){throw new IOException("无效 Base64 数据",e);}
        require(hash(packed).equals(part.fields.get("SHA256")),"CHECKSUM_ERROR: 完整包校验失败");Payload payload=Codec.decode(packed);
        require(payload.type().name().equals(part.fields.get("TYPE")) && payload.project().equals(selectedId)
            && payload.head().equals(part.fields.get(payload.type()==Type.WORKTREE?"BASE":"HEAD")),"包体与分块头不一致");
        require((payload.git()!=null)==part.extended,"协议版本与包体不匹配");
        require(payload.policy().equals(config.policy()),"EXCLUDE_MISMATCH: 两端排除配置不同");
        return new Received(part.session(),received,part.count,0,payload);
    }
    public static void clear(Path workspace,String session)throws IOException{
        require(session!=null && session.matches("[0-9a-f-]{36}"),"无效会话");
        Path dir=SafeFiles.state(workspace).resolve("inbox").resolve(session);SafeFiles.noLinks(dir);
        if(!Files.exists(dir))return;
        try(var paths=Files.list(dir)){
            for(Path p:paths.toList()){
                SafeFiles.noLinks(p);require(Files.isRegularFile(p) && (p.getFileName().toString().equals("metadata.txt") || p.getFileName().toString().matches("[1-9][0-9]{0,8}\\.txt")),"会话含未知文件，拒绝删除");
            }
        }
        try(var paths=Files.list(dir)){for(Path p:paths.toList())Files.delete(p);}Files.delete(dir);
    }
}
