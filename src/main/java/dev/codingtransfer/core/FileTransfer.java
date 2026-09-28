package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** Independent single-file transport. No project mapping, Git, text conversion or payload destination. */
public final class FileTransfer {
    private FileTransfer(){}
    public record Metadata(String session,String filename,long size,int chunkBytes,int count,String sha256){
        String header(){return "VMSYNC-FILE/1\nSESSION:"+session+"\nFILENAME:"+filename+"\nSIZE:"+size+"\nCHUNK-BYTES:"+chunkBytes+"\nPARTS:"+count+"\nENCODING:BASE64\nSHA256:"+sha256+"\n";}
    }
    public record Outgoing(Metadata metadata,Path snapshot){
        public List<String> chunks(){return new AbstractList<>(){
            @Override public int size(){return metadata.count();}
            @Override public String get(int index){
                Objects.checkIndex(index,size());try{
                    SafeFiles.noLinks(snapshot);int length=(int)Math.min(metadata.chunkBytes,metadata.size-(long)index*metadata.chunkBytes);
                    byte[] part=new byte[length];try(RandomAccessFile in=new RandomAccessFile(snapshot.toFile(),"r")){in.seek((long)index*metadata.chunkBytes);in.readFully(part);}
                    return metadata.header()+"PART:"+(index+1)+"\nCHUNK256:"+hash(part)+"\nDATA:\n"+Base64.getEncoder().encodeToString(part);
                }catch(IOException e){throw new UncheckedIOException(e);}
            }
        };}
    }
    public record Received(Metadata metadata,int received,int firstMissing,Path verifiedFile){public boolean complete(){return verifiedFile!=null;}}
    public record Destination(Path path,String previousHash){}
    private record Part(Metadata metadata,int number,byte[] bytes){}
    private static void filename(String name)throws IOException{
        require(name!=null&&!name.isBlank()&&name.getBytes(StandardCharsets.UTF_8).length<=1024&&!name.equals(".")&&!name.equals("..")
            &&name.chars().noneMatch(c->Character.isISOControl(c)||c=='/'||c=='\\'),"文件名必须是单个名称，不能包含路径或控制字符");
    }
    public static Outgoing generate(Path file,int chunkKb)throws IOException{return generate(file,chunkKb,file.toAbsolutePath().getParent());}
    public static Outgoing generate(Path file,int chunkKb,Path workspace)throws IOException{
        require(chunkKb>=4&&chunkKb<=TransferLimits.MAX_CHUNK_KB,"分块大小范围 4–1048576 KiB");filename(file.getFileName().toString());
        SafeFiles.noLinks(file);require(Files.isRegularFile(file),"不是普通文件: "+file);
        String session=UUID.randomUUID().toString();Path snapshot=SafeFiles.state(workspace).resolve("file-outgoing").resolve(session).resolve("content.bin");
        SafeFiles.noLinks(snapshot);Files.createDirectories(snapshot.getParent());String digest=copyHash(file,snapshot);long size=Files.size(snapshot);int width=chunkKb*1024/4*3;
        int count=Math.toIntExact(Math.max(1,size/width+(size%width==0?0:1)));
        Metadata metadata=new Metadata(session,file.getFileName().toString(),size,width,count,digest);
        SafeFiles.atomicWrite(snapshot.getParent().resolve("metadata.txt"),metadata.header().getBytes(StandardCharsets.UTF_8));
        return new Outgoing(metadata,snapshot);
    }
    public static Outgoing loadOutgoing(Path workspace,String session)throws IOException{
        require(session!=null&&session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效文件会话");
        Path dir=SafeFiles.state(workspace).resolve("file-outgoing").resolve(session);SafeFiles.noLinks(dir);Path metadataFile=dir.resolve("metadata.txt"),snapshot=dir.resolve("content.bin");SafeFiles.noLinks(metadataFile);SafeFiles.noLinks(snapshot);
        String[] lines=new String(SafeFiles.read(metadataFile,4096),StandardCharsets.UTF_8).split("\\n",-1);
        if(lines.length!=9||!lines[8].isEmpty()||!lines[0].equals("VMSYNC-FILE/1"))throw new IOException("文件发送快照元数据损坏");
        try{Map<String,String> f=new HashMap<>();for(int i=1;i<8;i++){int at=lines[i].indexOf(':');if(at<1||f.putIfAbsent(lines[i].substring(0,at),lines[i].substring(at+1))!=null)throw new IOException("文件发送快照元数据无效");}
            if(!f.keySet().equals(Set.of("SESSION","FILENAME","SIZE","CHUNK-BYTES","PARTS","ENCODING","SHA256"))||!f.get("SESSION").equals(session)||!f.get("ENCODING").equals("BASE64"))throw new IOException("文件发送快照元数据不匹配");
            filename(f.get("FILENAME"));long size=number(f.get("SIZE"),Long.MAX_VALUE);int width=(int)number(f.get("CHUNK-BYTES"),TransferLimits.MAX_FILE_CHUNK_BYTES),count=(int)number(f.get("PARTS"),Integer.MAX_VALUE);
            if(width<3072||width%3!=0||count!=Math.max(1,size/width+(size%width==0?0:1))||!hashValue(f.get("SHA256"))||!Files.isRegularFile(snapshot)||Files.size(snapshot)!=size||!copyHash(snapshot,null).equals(f.get("SHA256")))throw new IOException("文件发送快照校验失败");
            return new Outgoing(new Metadata(session,f.get("FILENAME"),size,width,count,f.get("SHA256")),snapshot);
        }catch(NumberFormatException e){throw new IOException("文件发送快照元数据无效",e);}
    }
    public static Received resume(Path workspace,String session)throws IOException{
        Path dir=SafeFiles.state(workspace).resolve("file-inbox").resolve(session);SafeFiles.noLinks(dir);Path meta=dir.resolve("metadata.txt");SafeFiles.noLinks(meta);
        String header=new String(SafeFiles.read(meta,4096),StandardCharsets.UTF_8);Path piece=null;
        try(var files=Files.list(dir)){for(Path f:files.toList())if(f.getFileName().toString().matches("[1-9][0-9]{0,9}\\.part")){piece=f;break;}}
        if(piece==null)throw new IOException("文件收件会话没有已保存分块");String number=piece.getFileName().toString().replace(".part","");byte[] data=SafeFiles.read(piece,TransferLimits.MAX_FILE_CHUNK_BYTES);
        return receive(workspace,header+"PART:"+number+"\nCHUNK256:"+hash(data)+"\nDATA:\n"+Base64.getEncoder().encodeToString(data));
    }
    /** Restores the newest verified receiver cache without relying on the transport session still being active. */
    public static Received latestVerified(Path workspace)throws IOException{
        Path latest=null;long modified=Long.MIN_VALUE;
        try(var sessions=Files.list(inbox(workspace))){
            for(Path dir:sessions.toList()){
                SafeFiles.noLinks(dir);
                require(Files.isDirectory(dir)&&dir.getFileName().toString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"文件收件箱含未知会话");
                Path verified=dir.resolve("verified.bin");SafeFiles.noLinks(verified);
                if(!Files.isRegularFile(verified))continue;
                long time=Files.getLastModifiedTime(verified).toMillis();
                if(latest==null||time>modified){latest=dir;modified=time;}
            }
        }
        if(latest==null)return null;
        Received received=resume(workspace,latest.getFileName().toString());
        require(received.complete(),"文件收件缓存尚未完整");
        return received;
    }
    private static long number(String s,long max)throws IOException{
        require(s!=null&&s.matches("0|[1-9][0-9]{0,18}"),"无效数字字段");
        try{long n=Long.parseLong(s);require(n<=max,"数字字段超过表示范围");return n;}catch(NumberFormatException e){throw new IOException("无效数字字段",e);}
    }
    private static String copyHash(Path source,Path destination)throws IOException{
        SafeFiles.noLinks(source);if(destination!=null)SafeFiles.noLinks(destination);
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(source);OutputStream out=destination==null?OutputStream.nullOutputStream():Files.newOutputStream(destination)){
                byte[] buffer=new byte[64*1024];int n;while((n=in.read(buffer))!=-1){digest.update(buffer,0,n);out.write(buffer,0,n);}
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    private static Part parse(String text)throws IOException{
        require(text!=null&&text.length()<=TransferLimits.MAX_PACKET_CHARS,"分块为空或超过限制");text=text.replace("\r\n","\n");
        // Preserve empty-file DATA and do not trim filename/header content.
        int at=text.indexOf("\nDATA:\n");require(at>0&&at<3072,"不是文件传输分块，请选择正确页面");
        String[] lines=text.substring(0,at).split("\n",-1);require(lines[0].equals("VMSYNC-FILE/1"),"不是文件传输协议");
        Map<String,String> fields=new TreeMap<>();for(int i=1;i<lines.length;i++){
            int colon=lines[i].indexOf(':');require(colon>0,"无效分块头");require(fields.putIfAbsent(lines[i].substring(0,colon),lines[i].substring(colon+1))==null,"重复分块头");
        }
        require(fields.keySet().equals(Set.of("SESSION","FILENAME","SIZE","CHUNK-BYTES","PARTS","ENCODING","SHA256","PART","CHUNK256")),"未知/缺失分块头");
        String session=fields.get("SESSION"),name=fields.get("FILENAME");filename(name);
        require(session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效文件会话");
        require(fields.get("ENCODING").equals("BASE64")&&hashValue(fields.get("SHA256"))&&hashValue(fields.get("CHUNK256")),"无效编码/校验值");
        long size=number(fields.get("SIZE"),Long.MAX_VALUE);int width=(int)number(fields.get("CHUNK-BYTES"),TransferLimits.MAX_FILE_CHUNK_BYTES),count=(int)number(fields.get("PARTS"),Integer.MAX_VALUE),part=(int)number(fields.get("PART"),Integer.MAX_VALUE);
        require(width>=3072&&width%3==0&&count==Math.max(1,size/width+(size%width==0?0:1))&&part>=1&&part<=count,"无效分块数量/大小");
        String encoded=text.substring(at+7).strip();require(encoded.length()<=TransferLimits.MAX_DATA_CHARS&&encoded.matches("[A-Za-z0-9+/]*={0,2}"),"无效文件 Base64");
        byte[] bytes;try{bytes=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException e){throw new IOException("无效文件 Base64",e);}
        require(bytes.length==Math.min(width,size-(part-1L)*width),"分块长度与头部不一致");
        require(hash(bytes).equals(fields.get("CHUNK256")),"CHECKSUM_ERROR: 文件分块损坏，请重新复制");
        return new Part(new Metadata(session,name,size,width,count,fields.get("SHA256")),part,bytes);
    }
    private static Path inbox(Path workspace)throws IOException{
        Path p=SafeFiles.state(workspace).resolve("file-inbox");SafeFiles.noLinks(p);Files.createDirectories(p);return p;
    }
    public static Received receive(Path workspace,String text)throws IOException{return receivePart(workspace,parse(text));}
    public static Received receive(Path workspace,String text,String expectedSession,int expectedIndex,int expectedTotal)throws IOException{
        Part part=parse(text);
        require(part.metadata.session().equals(expectedSession)&&part.number==expectedIndex&&part.metadata.count()==expectedTotal,"文件块内外层会话/序号/总数不一致");
        return receivePart(workspace,part);
    }
    private static Received receivePart(Path workspace,Part part)throws IOException{
        Metadata m=part.metadata;Path inbox=inbox(workspace),dir=inbox.resolve(m.session);SafeFiles.noLinks(dir);
        try(var paths=Files.list(inbox)){require(Files.exists(dir)||paths.count()<32,"文件收件箱达到 32 会话，请先清理旧会话");}
        Files.createDirectories(dir);
        Path meta=dir.resolve("metadata.txt");byte[] header=m.header().getBytes(StandardCharsets.UTF_8);
        if(Files.exists(meta))require(Arrays.equals(SafeFiles.read(meta,4096),header),"混合文件会话：名称/大小/校验值不一致");else SafeFiles.atomicWrite(meta,header);
        Path chunk=dir.resolve(part.number+".part");
        if(Files.exists(chunk))require(Arrays.equals(SafeFiles.read(chunk,TransferLimits.MAX_FILE_CHUNK_BYTES),part.bytes),"重复分块内容不一致");else SafeFiles.atomicWrite(chunk,part.bytes);
        SortedSet<Integer> present=new TreeSet<>();
        try(var paths=Files.list(dir)){for(Path p:paths.toList())if(p.getFileName().toString().matches("[1-9][0-9]{0,9}\\.part")){
            SafeFiles.noLinks(p);String name=p.getFileName().toString();int index=(int)number(name.substring(0,name.length()-5),m.count);require(Files.isRegularFile(p),"分块不是文件");present.add(index);
        }}
        int received=present.size();long nextMissing=1;for(int index:present){if(index==nextMissing)nextMissing++;else break;}int missing=nextMissing>m.count?0:(int)nextMissing;
        if(received!=m.count)return new Received(m,received,missing,null);
        Path ready=dir.resolve("verified.bin");SafeFiles.noLinks(ready);
        Path assembled=Files.createTempFile(dir,"assemble-",".tmp");
        try{
            try(OutputStream out=Files.newOutputStream(assembled)){
                for(int i=0;i<m.count;i++){
                    Path piece=dir.resolve((i+1)+".part");SafeFiles.noLinks(piece);require(Files.size(piece)==Math.min(m.chunkBytes,m.size-(long)i*m.chunkBytes),"持久化分块长度错误");Files.copy(piece,out);
                }
            }
            require(Files.size(assembled)==m.size&&copyHash(assembled,null).equals(m.sha256),"CHECKSUM_ERROR: 完整文件校验失败");
            Files.move(assembled,ready,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(assembled);}
        return new Received(m,received,0,ready);
    }
    /** Capture the exact target the user selected before asking for overwrite confirmation. */
    public static Destination destination(Path path)throws IOException{
        path=path.toAbsolutePath().normalize();SafeFiles.noLinks(path);require(path.getParent()!=null&&Files.isDirectory(path.getParent()),"请选择存在的目标目录");
        require(!Files.isDirectory(path),"请选择目标文件，不是目录");return new Destination(path,Files.exists(path)?copyHash(path,null):"-");
    }
    public static void save(Received received,Destination target)throws IOException{
        require(received!=null&&received.complete(),"尚未收齐并校验文件");
        require(!target.path.startsWith(received.verifiedFile.getParent()),"不能覆盖当前文件收件会话");
        SafeFiles.noLinks(target.path);
        Path temp=Files.createTempFile(target.path.getParent(),".vmsync-file-",".tmp");
        try{
            String checksum=copyHash(received.verifiedFile,temp);
            require(Files.size(temp)==received.metadata.size&&checksum.equals(received.metadata.sha256),"CHECKSUM_ERROR: 待保存文件已改变");
            Destination current=destination(target.path);require(current.previousHash.equals(target.previousHash),"目标文件已改变，请重新选择并确认");
            if(target.previousHash.equals("-"))Files.move(temp,target.path);
            else{
                if(Files.getFileStore(target.path).supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class))Files.setPosixFilePermissions(temp,Files.getPosixFilePermissions(target.path));
                try{Files.move(temp,target.path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                catch(AtomicMoveNotSupportedException e){Files.move(temp,target.path,StandardCopyOption.REPLACE_EXISTING);}
            }
        }finally{Files.deleteIfExists(temp);}
    }
    public static void clear(Path workspace,String session)throws IOException{
        require(session!=null&&session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效文件会话");
        Path dir=inbox(workspace).resolve(session);SafeFiles.noLinks(dir);if(!Files.exists(dir))return;
        List<Path> files;try(var list=Files.list(dir)){files=list.toList();}
        for(Path p:files){SafeFiles.noLinks(p);String name=p.getFileName().toString();require(Files.isRegularFile(p)&&(name.equals("metadata.txt")||name.equals("verified.bin")||name.matches("[1-9][0-9]{0,9}\\.part")),"会话含未知文件，拒绝清理");}
        for(Path p:files)Files.delete(p);Files.delete(dir);
    }
}
