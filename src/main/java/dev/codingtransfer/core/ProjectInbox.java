package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

import dev.codingtransfer.core.ClipboardTransport.Received;
import static dev.codingtransfer.core.ProjectChunkCodec.*;

/** Persistent project chunks; payloads are assembled only after all indexes arrive. */
final class ProjectInbox {
    private ProjectInbox() {}
    public static Received receive(Path workspace, Config config, String selectedId, String text)throws IOException{
        Part part=parse(text);config.project(selectedId);
        require(part.fields().get("TARGET").equals(config.role().name()),"接收角色不匹配");
        require(part.fields().get("PROJECT").equals(selectedId),"项目不匹配，请选择 "+part.fields().get("PROJECT"));
        Path dir = prepareSession(workspace, part);
        savePart(dir, part, text);
        SortedMap<Integer,Path> parts = listParts(dir, part.count());
        int missing = 1;
        for (int index : parts.keySet()) {
            if (index != missing) break;
            missing++;
        }
        if (parts.size() != part.count()) {
            return new Received(part.session(), parts.size(), part.count(), missing, null);
        }
        Payload payload = assemble(parts, part, config, selectedId);
        return new Received(part.session(),parts.size(),part.count(),0,payload);
    }

    private static Payload assemble(SortedMap<Integer,Path> parts, Part part, Config config,
                                    String selectedId) throws IOException {
        // Revalidate every persisted block at completion, including after a restart.
        StringBuilder joined = new StringBuilder();
        for (var entry : parts.entrySet()) {
            Part saved = parse(new String(SafeFiles.read(entry.getValue(), TransferLimits.MAX_PACKET_CHARS), StandardCharsets.UTF_8));
            require(saved.signature().equals(part.signature()) && saved.count()==part.count()
                    && saved.index()==entry.getKey(), "持久化会话不一致");
            joined.append(saved.data());
        }
        byte[] packed;try{packed=Base64.getDecoder().decode(joined.toString());}catch(IllegalArgumentException e){throw new IOException("无效 Base64 数据",e);}
        require(hash(packed).equals(part.fields().get("SHA256")),"CHECKSUM_ERROR: 完整包校验失败");Payload payload=Codec.decode(packed);
        require(payload.type().name().equals(part.fields().get("TYPE")) && payload.project().equals(selectedId)
            && payload.head().equals(part.fields().get(payload.type()==Type.WORKTREE?"BASE":"HEAD")),"包体与分块头不一致");
        require(payload.version()==part.protocol(),"协议版本与包体不匹配");
        require(payload.policy().equals(config.policy()),"EXCLUDE_MISMATCH: 两端排除配置不同");
        return payload;
    }
    private static Path prepareSession(Path workspace, Part part) throws IOException {
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
        return dir;
    }

    private static void savePart(Path dir, Part part, String text) throws IOException {
        Path file=dir.resolve(part.index()+".txt");byte[] current=text.getBytes(StandardCharsets.UTF_8);
        if(Files.exists(file)){
            Part existing=parse(new String(SafeFiles.read(file,TransferLimits.MAX_PACKET_CHARS),StandardCharsets.UTF_8));
            require(existing.signature().equals(part.signature()) && existing.count()==part.count() && existing.data().equals(part.data()),"重复块内容不一致");
        }else SafeFiles.atomicWrite(file,current);
    }

    private static SortedMap<Integer,Path> listParts(Path dir, int count) throws IOException {
        SortedMap<Integer,Path> result = new TreeMap<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                SafeFiles.noLinks(file);
                String name = file.getFileName().toString();
                if (name.equals("metadata.txt")) continue;
                require(Files.isRegularFile(file) && name.matches("[1-9][0-9]{0,8}\\.txt"),
                        "持久化会话不一致");
                int index = Integer.parseInt(name.substring(0, name.length()-4));
                require(index <= count, "多余分块");
                result.put(index, file);
            }
        }
        return result;
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
