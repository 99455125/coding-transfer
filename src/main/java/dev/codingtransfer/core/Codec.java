package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static dev.codingtransfer.core.Model.*;

public final class Codec {
    private Codec() {}
    public static void validate(Payload p) throws IOException {
        require(p.type() != null, "缺少类型"); projectId(p.project()); require(headValue(p.head()), "无效 Git HEAD"); require(hashValue(p.policy()), "无效策略 hash");
        require(p.entries().size() <= FILE_COUNT, "文件数量超过限制");
        require(p.version()>=1&&p.version()<=3,"不支持的协议版本");
        if(p.version()==1)require(p.git()==null&&p.sourceBranch().isEmpty()&&(p.type()==Type.SNAPSHOT||p.type()==Type.WORKTREE),"无效 V1 payload");
        if(p.version()==2)require(p.git()!=null&&p.sourceBranch().isEmpty()&&p.type()!=Type.WORKTREE,"无效 V2 payload");
        if(p.version()==3){
            require(p.type()==Type.WORKTREE&&p.git()==null,"V3 仅用于 HOST WORKTREE");
            if(!p.sourceBranch().isEmpty())GitHistory.validRef(p.sourceBranch(),true);
        }
        if(p.type()==Type.DELTA||p.type()==Type.ACK)require(p.git()!=null,"缺少 Git 同步元数据");
        if(p.type()==Type.ACK)require(p.entries().isEmpty(),"回执不能包含文件");
        if(p.git()!=null){
            String uuid="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
            GitData g=p.git();require(g.snapshot().matches(uuid)&&(g.parent().equals("-")||g.parent().matches(uuid)),"无效快照标识");
            require(g.refs().size()<=1000,"Git 数据超过限制");
            require(p.type()!=Type.WORKTREE,"WORKTREE 不携带历史");
            if(p.type()==Type.ACK)require(g.bundle().length==0&&g.refs().isEmpty()&&g.branch().isEmpty(),"无效回执");
            if(!g.branch().isEmpty())GitHistory.validRef(g.branch(),true);
            for(var ref:g.refs().entrySet()){GitHistory.validRef(ref.getKey(),false);require(headValue(ref.getValue()),"无效 ref hash");}
            if(p.type()!=Type.ACK&&!g.branch().isEmpty())require(p.head().equals(g.refs().get(g.branch())),"分支与 HEAD 不一致");
        }
        SafeFiles.validateNames(p.entries().stream().map(Entry::path).toList());
        Set<String> names = new HashSet<>();
        for (Entry e : p.entries()) {
            SafeFiles.relative(e.path()); require(names.add(e.path().toLowerCase(Locale.ROOT)), "重复/大小写冲突路径: " + e.path());
            require((e.before().equals("-") || hashValue(e.before())) && (e.after().equals("-") || hashValue(e.after())), "无效文件 hash");
            if(p.version()==3)require(hash(e.base()).equals(e.before()),"BASE_CHECKSUM_ERROR: "+e.path());
            else require(e.base()==null,"旧协议不能携带 BASE 内容");
            int length = e.data().length;
            if (p.type() == Type.SNAPSHOT) require(e.encoding() == Encoding.RAW && e.before().equals("-"), "SNAPSHOT 必须是完整原始文件");
            if(p.version()==3)require(e.encoding()==Encoding.RAW||e.encoding()==Encoding.DELETE,"V3 WORKTREE 只允许 RAW/DELETE");
            switch (e.encoding()) {
                case RAW -> require(hash(e.data()).equals(e.after()), "CHECKSUM_ERROR: " + e.path());
                case DELETE -> require(length == 0 && !e.before().equals("-") && e.after().equals("-"), "无效删除项");
                case PATCH -> require(!e.before().equals("-") && !e.after().equals("-") && TextPatch.text(e.data()) != null, "无效 PATCH 项");
            }
            require(!e.before().equals(e.after()), "无变化/无效条目: " + e.path());
        }
        for (String name : names) {
            for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i+1)) require(!names.contains(name.substring(0,i)), "文件/目录路径冲突");
        }
    }
    public static byte[] encode(Payload p) throws IOException {
        validate(p); ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(0x564D5331); out.writeInt(p.version()); string(out,p.type().name()); string(out,p.project()); string(out,p.head()); string(out,p.policy());
            if(p.version()==3)string(out,p.sourceBranch());
            out.writeInt(p.entries().size());
            for (Entry e : p.entries()) {
                string(out,e.path()); string(out,e.before()); string(out,e.after()); string(out,e.encoding().name());
                if(p.version()==3){byte[] base=e.base();out.writeInt(base==null?-1:base.length);if(base!=null)out.write(base);}
                byte[] d=e.data(); out.writeInt(d.length); out.write(d);
            }
            if(p.version()==2){GitData g=p.git();string(out,g.snapshot());string(out,g.parent());string(out,g.branch());out.writeInt(g.refs().size());for(var ref:g.refs().entrySet()){string(out,ref.getKey());string(out,ref.getValue());}byte[] b=g.bundle();out.writeInt(b.length);out.write(b);}
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(packed)) { raw.writeTo(gz); }
        return packed.toByteArray();
    }
    public static Payload decode(byte[] packed) throws IOException {
        byte[] raw;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(packed))) { raw = gz.readAllBytes(); }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            require(in.readInt() == 0x564D5331,"不支持的协议");int version=in.readInt();require(version>=1&&version<=3,"不支持的协议版本");
            Type type = Type.valueOf(string(in,16)); String project = string(in,128), head = string(in,128), policy = string(in,128),sourceBranch=version==3?string(in,1024):"";
            int count=in.readInt(); require(count >= 0 && count <= FILE_COUNT, "无效条目数量"); List<Entry> entries=new ArrayList<>();
            for(int i=0;i<count;i++) {
                String path=string(in,4096), before=string(in,128), after=string(in,128); Encoding encoding=Encoding.valueOf(string(in,16));byte[] base=null;
                if(version==3){int baseLength=in.readInt();require(baseLength>=-1&&baseLength<=in.available(),"无效 BASE 数据长度");if(baseLength>=0)base=in.readNBytes(baseLength);}
                int n=in.readInt(); require(n>=0 && n<=in.available(), "无效数据长度");
                entries.add(new Entry(path,before,after,encoding,in.readNBytes(n),base));
            }
            GitData git=null;
            if(version==2){String snapshot=string(in,64),parent=string(in,64),branch=string(in,1024);int refs=in.readInt();require(refs>=0&&refs<=1000,"refs 数量超限");SortedMap<String,String> map=new TreeMap<>();for(int i=0;i<refs;i++){String ref=string(in,1024);require(map.putIfAbsent(ref,string(in,128))==null,"重复 ref");}int n=in.readInt();require(n>=0&&n<=in.available(),"Bundle 长度超限");git=new GitData(snapshot,parent,branch,map,in.readNBytes(n));}
            require(in.read() == -1, "Payload 尾部有额外数据"); Payload p=new Payload(type,project,head,policy,entries,git,sourceBranch,version); validate(p); return p;
        } catch(IllegalArgumentException e) { throw new IOException("无效枚举/编码",e); }
    }
    private static void string(DataOutputStream out,String s)throws IOException { byte[] b=s.getBytes(StandardCharsets.UTF_8);out.writeInt(b.length);out.write(b); }
    private static String string(DataInputStream in,int max)throws IOException {
        int n=in.readInt();require(n>=0 && n<=max && n<=in.available(),"无效字符串长度");String s=TextPatch.text(in.readNBytes(n));require(s!=null,"无效 UTF-8");return s;
    }
}
