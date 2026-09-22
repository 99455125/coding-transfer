package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** One explicitly acknowledged VM→HOST chain per configured project. */
public final class BundleSync {
    private final Path workspace;private final Config config;private final SyncEngine engine;
    public BundleSync(Path workspace,Config config)throws IOException{this.workspace=workspace.toRealPath();this.config=config;engine=new SyncEngine(workspace,config);}
    private Path state(String kind,String id)throws IOException{projectId(id);Path p=SafeFiles.state(workspace).resolve("bundle-state").resolve(kind).resolve(id+".gz");SafeFiles.noLinks(p);return p;}
    private Payload acknowledged(String id)throws IOException{
        Path path=state("acknowledged",id);require(Files.exists(path),"NO_BASELINE: 请先全量同步，HOST 应用成功后把回执传回 VM 并确认");
        Payload p=Codec.decode(SafeFiles.read(path));require(p.type()==Type.SNAPSHOT&&p.git()!=null&&p.project().equals(id)&&p.policy().equals(config.policy()),"基线/排除规则不匹配，请重新全量同步");return p;
    }
    private static Payload attach(Payload p,GitData git){return new Payload(p.type(),p.project(),p.head(),p.policy(),p.entries(),git);}
    public SyncEngine.Generated scan(String id)throws IOException{
        require(config.role()==Role.VM,"仅 VM 可生成 Bundle");SyncEngine.Generated full=engine.generate(id);
        Path ack=state("acknowledged",id);
        return Files.exists(ack)?new SyncEngine.Generated(full.payload(),SyncEngine.diff(engine.manifest(acknowledged(id)),engine.manifest(full.payload())),full.root()):full;
    }
    public List<GitHistory.RefChange> refChanges(String id)throws IOException{
        require(config.role()==Role.VM,"仅 VM 展示整个 refs 的变化");Path root=config.root(workspace,config.project(id));
        GitHistory.Tips now=GitHistory.tips(root,config.git());Payload base=Files.exists(state("acknowledged",id))?acknowledged(id):null;
        Map<String,String> old=base==null?Map.of():base.git().refs();Set<String> names=new TreeSet<>(old.keySet());names.addAll(now.refs().keySet());
        List<GitHistory.RefChange> rows=new ArrayList<>();
        for(String ref:names)if(!Objects.equals(old.get(ref),now.refs().get(ref)))rows.addAll(GitHistory.refChanges(root,config.git(),ref,old.get(ref),now.refs().get(ref),config));
        if(now.branch().isEmpty())rows.addAll(GitHistory.refChanges(root,config.git(),"HEAD",base==null?null:base.head(),now.head(),config));
        rows.addAll(GitHistory.workingChanges(root,config.git(),now,config));
        require(now.equals(GitHistory.tips(root,config.git())),"VM refs 在刷新期间改变，请重试");return List.copyOf(rows);
    }
    public SyncEngine.Generated generate(String id,boolean incremental)throws IOException{
        require(config.role()==Role.VM,"仅 VM 可生成 Bundle");
        Payload base=incremental?acknowledged(id):null;SyncEngine.Generated full=engine.generate(id);
        GitHistory.Tips tips=GitHistory.tips(full.root(),config.git());require(tips.head().equals(full.payload().head()),"VM HEAD 已变化，请重新生成");
        String snapshot=UUID.randomUUID().toString(),parent=base==null?"-":base.git().snapshot();
        Path output=SafeFiles.state(workspace).resolve("git-outgoing").resolve(snapshot).resolve("history.bundle");SafeFiles.noLinks(output);Files.createDirectories(output.getParent());
        byte[] bundle=GitHistory.bundle(full.root(),config.git(),output,tips,base);
        require(tips.equals(GitHistory.tips(full.root(),config.git())),"VM refs 已变化，请重新生成");
        SafeFiles.same(engine.manifest(full.payload()),SafeFiles.scan(full.root(),config));
        GitData git=new GitData(snapshot,parent,tips.branch(),tips.refs(),bundle);
        List<Change> changes=base==null?full.changes():SyncEngine.diff(engine.manifest(base),engine.manifest(full.payload()));
        List<Entry> entries=new ArrayList<>();
        for(Change c:changes)entries.add(new Entry(c.path(),hash(c.before()),hash(c.after()),c.after()==null?Encoding.DELETE:Encoding.RAW,c.after()==null?new byte[0]:c.after()));
        Payload payload=incremental?new Payload(Type.DELTA,id,tips.head(),config.policy(),entries,git):attach(full.payload(),git);
        Codec.encode(payload); // Enforce entire packet limits before replacing the pending receipt target.
        SafeFiles.atomicWrite(state("pending",id),Codec.encode(attach(full.payload(),git.withoutBundle())));
        return new SyncEngine.Generated(payload,changes,full.root());
    }
    public Payload target(Payload payload)throws IOException{
        require(config.role()==Role.HOST&&payload.git()!=null,"不是 HOST Bundle");
        if(payload.type()==Type.SNAPSHOT)return attach(payload,payload.git().withoutBundle());
        require(payload.type()==Type.DELTA,"不是增量 Bundle");Payload baseline=engine.readBaseline(payload.project());
        require(baseline.git()!=null&&baseline.git().snapshot().equals(payload.git().parent()),"BASE_MISMATCH: 缺失/过期增量基线，请按顺序接收或重新全量同步");
        SortedMap<String,byte[]> files=engine.manifest(baseline);
        for(Entry entry:payload.entries()){
            require(!config.excluded(entry.path()),"增量包含排除文件");byte[] old=files.get(entry.path());require(hash(old).equals(entry.before()),"增量原文校验失败: "+entry.path());
            byte[] next=switch(entry.encoding()){case DELETE->null;case RAW->entry.data();case PATCH->TextPatch.apply(old,entry.data());};
            require(hash(next).equals(entry.after()),"增量目标校验失败: "+entry.path());if(next==null)files.remove(entry.path());else files.put(entry.path(),next);
        }
        Payload target=attach(engine.snapshot(payload.project(),payload.head(),files),payload.git().withoutBundle());Codec.validate(target);return target;
    }
    public Payload receipt(String id)throws IOException{
        require(config.role()==Role.HOST,"仅 HOST 生成接收回执");Payload base=engine.readBaseline(id);require(base.git()!=null,"旧文件快照没有 Bundle 回执，请重新全量同步");
        GitData g=base.git();return new Payload(Type.ACK,id,base.head(),base.policy(),List.of(),new GitData(g.snapshot(),g.parent(),"",new TreeMap<>(),new byte[0]));
    }
    public void acceptReceipt(String id,Payload receipt)throws IOException{
        Codec.validate(receipt);require(config.role()==Role.VM&&receipt.type()==Type.ACK&&receipt.project().equals(id)&&receipt.policy().equals(config.policy()),"回执项目/角色/排除规则不匹配");
        Path pending=state("pending",id);require(Files.exists(pending),"没有待确认的发送包");Payload sent=Codec.decode(SafeFiles.read(pending));
        require(sent.git()!=null&&sent.git().snapshot().equals(receipt.git().snapshot())&&sent.git().parent().equals(receipt.git().parent())&&sent.head().equals(receipt.head()),"回执不是最近生成的包；请重新同步该包或全量初始化");
        Path ack=state("acknowledged",id);
        if(Files.exists(ack)){
            Payload previous=Codec.decode(SafeFiles.read(ack));
            if(previous.git().snapshot().equals(sent.git().snapshot()))return;
            require(sent.git().parent().equals("-")||sent.git().parent().equals(previous.git().snapshot()),"回执父基线已过期");
        }else require(sent.git().parent().equals("-"),"缺少回执父基线");
        SafeFiles.atomicWrite(ack,Codec.encode(sent));
    }
}
