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
    public boolean hasUnacknowledgedPending(String id)throws IOException{
        PendingBundleTransferStore.Transfer inFlight=new PendingBundleTransferStore(workspace).read();if(inFlight==null){TransferSessionStore.Context context=new TransferSessionStore(workspace).currentContext();if(context!=null)return true;return false;}if(!inFlight.projectId().equals(id))return true;
        Path pending=state("pending",id);if(!Files.exists(pending))return true;Payload sent=Codec.decode(SafeFiles.read(pending));if(sent.git()==null||!sent.git().snapshot().equals(inFlight.transferId()))throw new IOException("正在传输的 VM Bundle 与待确认项目不匹配");
        Path ack=state("acknowledged",id);if(!Files.exists(ack))return true;Payload accepted=Codec.decode(SafeFiles.read(ack));
        boolean waiting=accepted.git()==null||!sent.project().equals(accepted.project())||!sent.git().snapshot().equals(accepted.git().snapshot());if(!waiting)new PendingBundleTransferStore(workspace).clear(id,inFlight.session());return waiting;
    }
    public void markAutomaticTransferStarted(String projectId,String session,String transferId,int total)throws IOException{require(config.role()==Role.VM,"仅 VM Bundle 需要发送确认标记");Payload pending=Codec.decode(SafeFiles.read(state("pending",projectId)));require(pending.git()!=null&&pending.git().snapshot().equals(transferId),"待确认 Bundle 与自动传输不匹配");new PendingBundleTransferStore(workspace).mark(new PendingBundleTransferStore.Transfer(projectId,session,transferId,total));}
    private Path endedRoot()throws IOException{Path root=SafeFiles.state(workspace).resolve("bundle-state/ended");SafeFiles.noLinks(root);return root;}
    private String baselineHash(String id)throws IOException{Path ack=state("acknowledged",id);return Files.exists(ack)?hash(SafeFiles.read(ack)):"-";}
    private List<PendingBundleTransferStore.Transfer> endedTransfers(String id)throws IOException{
        projectId(id);Path root=endedRoot();if(!Files.exists(root))return List.of();List<PendingBundleTransferStore.Transfer> result=new ArrayList<>();
        try(var dirs=Files.list(root)){for(Path dir:dirs.toList()){SafeFiles.noLinks(dir);require(Files.isDirectory(dir),"恢复目录包含无效文件");var marker=PendingBundleTransferStore.archived(workspace,dir.getFileName().toString()).read();if(marker!=null&&marker.projectId().equals(id))result.add(marker);}}
        result.sort(Comparator.comparingLong((PendingBundleTransferStore.Transfer t)->{try{return Files.getLastModifiedTime(root.resolve(t.session()).resolve("current.context")).toMillis();}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).reversed());return result;
    }
    public boolean hasEndedTransfer(String id)throws IOException{return !endedTransfers(id).isEmpty();}
    public boolean needsEndedRestore(String id)throws IOException{
        var context=new TransferSessionStore(workspace).currentContext();var marker=new PendingBundleTransferStore(workspace).read();
        String session=context!=null&&context.scopeId().equals(id)&&context.kind()==ReliableTransferEngine.Kind.PROJECT&&context.direction()==ReliableTransferEngine.Direction.VM_TO_HOST?context.session():context==null&&marker!=null&&marker.projectId().equals(id)?marker.session():null;
        return session!=null&&endedTransfers(id).stream().anyMatch(t->t.session().equals(session));
    }
    /** Finish an interrupted explicit restore or end whose active pointer still occupies the slot. */
    public void repairEndedRestore(String id)throws IOException{if(needsEndedRestore(id))restoreEndedTransfer(id);}
    /** Archive recovery data before releasing active occupancy; no baseline or transport progress is deleted. */
    public void endAutomaticTransfer(String id,String session)throws IOException{
        require(config.role()==Role.VM,"仅 VM Bundle 使用此恢复路径");var active=new PendingBundleTransferStore(workspace);var marker=active.read();
        if(marker==null){new TransferSessionStore(workspace).clearContext(session);return;}
        require(marker.projectId().equals(id)&&marker.session().equals(session),"结束会话与当前 Bundle 不匹配");
        var sessions=new TransferSessionStore(workspace);var context=sessions.currentContext();require(context==null||(context.session().equals(session)&&context.scopeId().equals(id)&&context.transferId().equals(marker.transferId())&&context.kind()==ReliableTransferEngine.Kind.PROJECT&&context.direction()==ReliableTransferEngine.Direction.VM_TO_HOST&&context.total()==marker.total()),"当前传输上下文不匹配");
        byte[] target=SafeFiles.read(state("pending",id));Payload payload=Codec.decode(target);require(payload.git()!=null&&payload.git().snapshot().equals(marker.transferId())&&payload.project().equals(id),"待确认 Bundle 不匹配");
        Payload source=new ProjectOutgoingStore(workspace).load(session);require(source.git()!=null&&source.git().snapshot().equals(marker.transferId())&&source.project().equals(id),"恢复发送源不匹配");
        Path dir=endedRoot().resolve(session);SafeFiles.noLinks(dir);Files.createDirectories(dir);
        SafeFiles.atomicWrite(dir.resolve("target.gz"),target);SafeFiles.atomicWrite(dir.resolve("baseline.sha256"),baselineHash(id).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        PendingBundleTransferStore.archived(workspace,session).mark(marker);
        sessions.clearContext(session);active.clear(id,session);
    }
    /** Explicitly reattach the most recently ended send; a newer active transfer or baseline takes precedence. */
    public void restoreEndedTransfer(String id)throws IOException{
        require(config.role()==Role.VM,"仅 VM Bundle 使用此恢复路径");var candidates=endedTransfers(id);require(!candidates.isEmpty(),"没有可恢复的发送会话");var existingContext=new TransferSessionStore(workspace).currentContext();var existingMarker=new PendingBundleTransferStore(workspace).read();String activeSession=existingContext!=null?existingContext.session():existingMarker!=null?existingMarker.session():null;var marker=activeSession==null?candidates.getFirst():candidates.stream().filter(t->t.session().equals(activeSession)).findFirst().orElseThrow(()->new IOException("已有其他传输，不能恢复旧会话"));
        var active=new PendingBundleTransferStore(workspace);var sessions=new TransferSessionStore(workspace);var context=sessions.currentContext();var current=active.read();
        require(current==null||current.equals(marker),"已有其他待确认 Bundle，不能恢复旧会话");require(context==null||context.equals(new TransferSessionStore.Context(marker.session(),id,marker.transferId(),ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,marker.total())),"已有其他传输，不能恢复旧会话");
        Path dir=endedRoot().resolve(marker.session());String original=new String(SafeFiles.read(dir.resolve("baseline.sha256"),64),java.nio.charset.StandardCharsets.US_ASCII);require(original.equals(baselineHash(id)),"已确认基线已改变，不能恢复旧会话；旧数据仍保留");
        byte[] target=SafeFiles.read(dir.resolve("target.gz"));Payload payload=Codec.decode(target);require(payload.project().equals(id)&&payload.policy().equals(config.policy())&&payload.git()!=null&&payload.git().snapshot().equals(marker.transferId()),"恢复 Bundle 与配置不匹配");
        var source=new ProjectOutgoingStore(workspace);Payload outgoing=source.load(marker.session());require(outgoing.git()!=null&&outgoing.git().snapshot().equals(marker.transferId())&&outgoing.project().equals(id)&&outgoing.policy().equals(config.policy()),"恢复发送源不匹配");source.loadParts(marker.session(),null,marker.total());
        // Context first blocks generation if interruption occurs while restoring the pending target.
        sessions.saveContext(new TransferSessionStore.Context(marker.session(),id,marker.transferId(),ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,marker.total()));
        SafeFiles.atomicWrite(state("pending",id),target);active.mark(marker);PendingBundleTransferStore.archived(workspace,marker.session()).clear(id,marker.session());
    }
    private static Payload attach(Payload p,GitData git){return new Payload(p.type(),p.project(),p.head(),p.policy(),p.entries(),git);}
    public SyncEngine.Generated scan(String id)throws IOException{
        require(config.role()==Role.VM,"仅 VM 可生成 Bundle");SyncEngine.Generated full=engine.generate(id);
        Path ack=state("acknowledged",id);
        return Files.exists(ack)?new SyncEngine.Generated(full.payload(),SyncEngine.diff(engine.manifest(acknowledged(id)),engine.manifest(full.payload())),full.root()):full;
    }
    public List<GitHistory.RefChange> refChanges(String id)throws IOException{
        require(config.role()==Role.VM,"仅 VM 展示整个 refs 的变化");Path root=config.root(workspace,config.project(id));
        GitHistory.Tips now=GitHistory.tips(root,config.git());Payload base=null;
        Path acknowledged=state("acknowledged",id);
        // Display cannot reuse an old exclusion policy, but must not block a new full transfer.
        // Incremental generation still requires the strict acknowledged() check.
        if(Files.exists(acknowledged)&&Codec.decode(SafeFiles.read(acknowledged)).policy().equals(config.policy()))base=acknowledged(id);
        Map<String,String> old=base==null?Map.of():base.git().refs();Set<String> names=new TreeSet<>(old.keySet());names.addAll(now.refs().keySet());
        List<GitHistory.RefChange> rows=new ArrayList<>();
        for(String ref:names)if(!Objects.equals(old.get(ref),now.refs().get(ref)))rows.addAll(GitHistory.refChanges(root,config.git(),ref,old.get(ref),now.refs().get(ref),config));
        if(now.branch().isEmpty())rows.addAll(GitHistory.refChanges(root,config.git(),"HEAD",base==null?null:base.head(),now.head(),config));
        rows.addAll(GitHistory.workingChanges(root,config.git(),now,config));
        require(now.equals(GitHistory.tips(root,config.git())),"VM refs 在刷新期间改变，请重试");return List.copyOf(rows);
    }
    public SyncEngine.Generated generate(String id,boolean incremental)throws IOException{
        require(config.role()==Role.VM,"仅 VM 可生成 Bundle");
        require(!hasUnacknowledgedPending(id),"PENDING_ACK: 此项目已有待确认 Bundle；先接收并确认 HOST 回执，再生成下一包");
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
        require(endedTransfers(id).stream().noneMatch(t->t.transferId().equals(receipt.git().snapshot())),"回执所属会话已结束；请先明确恢复该会话");
        Path pending=state("pending",id);require(Files.exists(pending),"没有待确认的发送包");Payload sent=Codec.decode(SafeFiles.read(pending));
        require(sent.git()!=null&&sent.git().snapshot().equals(receipt.git().snapshot())&&sent.git().parent().equals(receipt.git().parent())&&sent.head().equals(receipt.head()),"回执不是最近生成的包；请重新同步该包或全量初始化");
        Path ack=state("acknowledged",id);
        if(Files.exists(ack)){
            Payload previous=Codec.decode(SafeFiles.read(ack));
            if(previous.git().snapshot().equals(sent.git().snapshot())){var marker=new PendingBundleTransferStore(workspace).read();if(marker!=null)new PendingBundleTransferStore(workspace).clear(id,marker.session());return;}
            require(sent.git().parent().equals("-")||sent.git().parent().equals(previous.git().snapshot()),"回执父基线已过期");
        }else require(sent.git().parent().equals("-"),"缺少回执父基线");
        SafeFiles.atomicWrite(ack,Codec.encode(sent));
        var marker=new PendingBundleTransferStore(workspace).read();if(marker!=null)new PendingBundleTransferStore(workspace).clear(id,marker.session());
    }
}
