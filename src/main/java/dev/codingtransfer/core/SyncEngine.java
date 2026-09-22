package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** Pure Java domain layer. Explicit HOST bundle apply delegates controlled Git writes; no shell or network. */
public final class SyncEngine {
    private final Path workspace;
    private final Config config;
    public SyncEngine(Path workspace,Config config)throws IOException{this.workspace=workspace.toRealPath();this.config=config;}
    public record Generated(Payload payload,List<Change> changes,Path root){public Generated{changes=List.copyOf(changes);}}
    public static final class Preview {
        public final Payload payload;
        public final Path root;
        public final List<Change> changes;
        private final SortedMap<String,byte[]> before,after;
        private Payload target;
        private GitHistory.Plan gitPlan;
        private Preview(Payload payload,Path root,List<Change> changes,SortedMap<String,byte[]> before,SortedMap<String,byte[]> after){
            this.payload=payload;this.root=root;this.changes=List.copyOf(changes);this.before=before;this.after=after;
        }
    }
    private Path baseline(String id)throws IOException{projectId(id);Path p=SafeFiles.state(workspace).resolve("baselines").resolve(id+".gz");SafeFiles.noLinks(p);return p;}
    Payload readBaseline(String id)throws IOException{
        Path p=baseline(id);require(Files.exists(p),"NO_BASELINE: 请先在 VM 生成快照并在 HOST 接收应用");
        Payload base=Codec.decode(SafeFiles.read(p));
        require(base.type()==Type.SNAPSHOT && base.project().equals(id),"本地基线类型/项目错误");
        require(base.policy().equals(config.policy()),"EXCLUDE_MISMATCH: 排除配置已改变，请重新同步 VM 快照");return base;
    }
    SortedMap<String,byte[]> manifest(Payload p)throws IOException{
        SortedMap<String,byte[]> map=new TreeMap<>();
        for(Entry e:p.entries()){
            require(!config.excluded(e.path()),"包中存在排除文件: "+e.path());byte[] data=e.data();
            map.put(e.path(),data);
        }
        return map;
    }
    Payload snapshot(String id,String head,Map<String,byte[]> files){
        List<Entry> entries=new ArrayList<>();files.forEach((path,data)->entries.add(new Entry(path,"-",hash(data),Encoding.RAW,data)));
        return new Payload(Type.SNAPSHOT,id,head,config.policy(),entries);
    }
    public Generated generate(String id)throws IOException{
        Config.Mapping mapping=config.project(id);Path root=config.root(workspace,mapping);
        if(config.role()==Role.VM){
            String head=GitReader.head(root,config.git());SortedMap<String,byte[]> current=SafeFiles.scan(root,config);
            SafeFiles.same(current,SafeFiles.scan(root,config));require(head.equals(GitReader.head(root,config.git())),"VM HEAD 在扫描期间改变，请重试");
            return new Generated(snapshot(id,head,current),diff(new TreeMap<>(),current),root);
        }
        Payload base=readBaseline(id);SortedMap<String,byte[]> before=manifest(base),current=SafeFiles.scan(root,config);
        SafeFiles.same(current,SafeFiles.scan(root,config));List<Change> changes=diff(before,current);List<Entry> entries=new ArrayList<>();
        for(Change c:changes){
            byte[] a=c.before(),b=c.after();Encoding encoding;byte[] data;
            if(b==null){encoding=Encoding.DELETE;data=new byte[0];}
            else if(a!=null && TextPatch.text(a)!=null && TextPatch.text(b)!=null){
                encoding=Encoding.PATCH;data=TextPatch.create(a,b);
            }
            else{encoding=Encoding.RAW;data=b;}
            entries.add(new Entry(c.path(),hash(a),hash(b),encoding,data));
        }
        Payload payload=new Payload(Type.WORKTREE,id,base.head(),config.policy(),entries);Codec.validate(payload);
        return new Generated(payload,changes,root);
    }
    public Preview preview(String selectedId,Payload payload)throws IOException{
        Codec.validate(payload);config.project(selectedId);
        require(selectedId.equals(payload.project()),"项目不匹配");
        require(config.policy().equals(payload.policy()),"EXCLUDE_MISMATCH: 两端排除配置不一致");
        require((config.role()==Role.HOST && (payload.type()==Type.SNAPSHOT||payload.type()==Type.DELTA))||(config.role()==Role.VM && payload.type()==Type.WORKTREE),"传输方向与角色不匹配");
        Payload target=payload;
        if(config.role()==Role.HOST&&payload.git()!=null)target=new BundleSync(workspace,config).target(payload);
        Path root=config.root(workspace,config.project(selectedId));checkHead(root,payload);
        SortedMap<String,byte[]> before=SafeFiles.scan(root,config),after;
        if(config.role()==Role.HOST)after=manifest(target);
        else{
            after=new TreeMap<>(before);
            for(Entry e:payload.entries()){
                require(!config.excluded(e.path()),"包中存在排除文件: "+e.path());SafeFiles.resolve(root,e.path());
                byte[] old=before.get(e.path());require(hash(old).equals(e.before()),"BASE_CONTENT_MISMATCH: VM 文件已有不同修改: "+e.path());
                byte[] next=switch(e.encoding()){case DELETE->null;case RAW->e.data();case PATCH->TextPatch.apply(old,e.data());};
                require(hash(next).equals(e.after()),"CHECKSUM_ERROR: 应用结果不匹配: "+e.path());
                if(next==null)after.remove(e.path());else after.put(e.path(),next);
            }
        }
        require(after.size()<=FILE_COUNT,"目标项目文件数量超过限制");
        SafeFiles.validateNames(after.keySet());
        List<Change> changes=diff(before,after);preflight(root,changes);checkHead(root,payload);
        Preview preview=new Preview(payload,root,changes,before,after);preview.target=target;
        if(payload.git()!=null)preview.gitPlan=GitHistory.prepare(workspace,root,config.git(),payload);
        return preview;
    }
    private void checkHead(Path root,Payload p)throws IOException{
        if(config.role()==Role.VM)require(GitReader.head(root,config.git()).equals(p.head()),"BASE_MISMATCH: 当前 VM HEAD 不等于 HOST 基线；请先发送新的 VM 快照");
    }
    private void preflight(Path root,List<Change> changes)throws IOException{
        Set<String> folded=new HashSet<>();
        for(Change c:changes){
            require(folded.add(c.path().toLowerCase(Locale.ROOT)),"大小写冲突");Path target=SafeFiles.resolve(root,c.path());
            require(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS),"V1 不支持目录替换为文件: "+c.path());
            for(Path parent=target.getParent();parent!=null && parent.startsWith(root);parent=parent.getParent())
                require(!Files.exists(parent,LinkOption.NOFOLLOW_LINKS)||Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS),"V1 不支持文件替换为目录: "+c.path());
        }
    }
    @FunctionalInterface interface ApplyObserver { void afterWrite(int completed) throws IOException; default void afterGitCommit()throws IOException{} }
    public Path apply(Preview preview)throws IOException { return apply(preview, completed -> {}); }
    // Package-private observer allows deterministic failure/recovery tests, never populated from payloads.
    Path apply(Preview preview,ApplyObserver observer)throws IOException{
        require(config.root(workspace,config.project(preview.payload.project())).equals(preview.root),"项目路径已改变");
        SafeFiles.noLinks(preview.root);Files.createDirectories(preview.root);SafeFiles.noLinks(preview.root);
        // One lock per actual target, even when different IDEA workspaces/IDs map to it.
        Path lock=SafeFiles.state(preview.root).resolve("locks").resolve("apply.lock");
        SafeFiles.noLinks(lock);Files.createDirectories(lock.getParent());SafeFiles.noLinks(lock);
        try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            try(FileLock held=channel.tryLock()){
                require(held!=null,"另一个同步正在应用此项目，请稍后重试");return applyLocked(preview,observer);
            }catch(OverlappingFileLockException e){throw new IOException("另一个同步正在应用此项目，请稍后重试",e);}
        }
    }
    private Path applyLocked(Preview preview,ApplyObserver observer)throws IOException{
        require(config.root(workspace,config.project(preview.payload.project())).equals(preview.root),"项目路径已改变");
        checkHead(preview.root,preview.payload);SafeFiles.same(preview.before,SafeFiles.scan(preview.root,config));preflight(preview.root,preview.changes);
        Path backup=SafeFiles.state(workspace).resolve("backups").resolve(UUID.randomUUID().toString());SafeFiles.noLinks(backup);Files.createDirectories(backup);
        SafeFiles.atomicWrite(backup.resolve("before.gz"),Codec.encode(snapshot(preview.payload.project(),preview.payload.head(),preview.before)));
        SafeFiles.atomicWrite(backup.resolve("after.gz"),Codec.encode(snapshot(preview.payload.project(),preview.payload.head(),preview.after)));
        SafeFiles.atomicWrite(backup.resolve("target.txt"),preview.root.toString().getBytes(StandardCharsets.UTF_8));
        Path baseline=baseline(preview.payload.project());
        if(Files.exists(baseline))SafeFiles.atomicWrite(backup.resolve("previous-baseline.gz"),SafeFiles.read(baseline));
        StringBuilder manifest=new StringBuilder("operation\tpath\tbefore-sha256\tafter-sha256\n");
        for(Change c:preview.changes)manifest.append(c.status()).append('\t').append(c.path()).append('\t').append(hash(c.before())).append('\t').append(hash(c.after())).append('\n');
        SafeFiles.atomicWrite(backup.resolve("changes.tsv"),manifest.toString().getBytes(StandardCharsets.UTF_8));
        SafeFiles.atomicWrite(backup.resolve("status.txt"),"PREPARED".getBytes(StandardCharsets.UTF_8));
        // Backup is complete before the first business-file mutation.
        SafeFiles.same(preview.before,SafeFiles.scan(preview.root,config));checkHead(preview.root,preview.payload);
        List<Change> done=new ArrayList<>();GitHistory.Transaction git=null;
        try{
            if(preview.payload.type()==Type.DELTA)new BundleSync(workspace,config).target(preview.payload);
            if(preview.gitPlan!=null)git=GitHistory.begin(preview.gitPlan,config.git(),backup);
            for(Change c:preview.changes){
                Path target=SafeFiles.resolve(preview.root,c.path());
                byte[] now=Files.exists(target,LinkOption.NOFOLLOW_LINKS)?SafeFiles.read(target):null;
                require(Arrays.equals(now,c.before()),"STALE_PREVIEW: 写入前文件改变: "+c.path());
                if(c.after()==null)Files.delete(target);else SafeFiles.atomicWrite(target,c.after());done.add(c);observer.afterWrite(done.size());
            }
            checkHead(preview.root,preview.payload);SafeFiles.same(preview.after,SafeFiles.scan(preview.root,config));
            // Baseline advances only for an accepted VM snapshot, never on a HOST send.
            if(git!=null){git.commit();observer.afterGitCommit();}
            if(config.role()==Role.HOST)SafeFiles.atomicWrite(baseline,Codec.encode(preview.target));
        }catch(IOException|RuntimeException failure){
            List<String> failures=new ArrayList<>();Collections.reverse(done);
            if(git!=null)try{git.rollback();}catch(IOException e){failures.add(e.getMessage());}
            for(Change c:done){try{
                Path target=SafeFiles.resolve(preview.root,c.path());byte[] now=Files.exists(target,LinkOption.NOFOLLOW_LINKS)?SafeFiles.read(target):null;
                if(Arrays.equals(now,c.before()))continue;
                require(Arrays.equals(now,c.after()),"回滚时文件被外部修改: "+c.path());
                if(c.before()==null)Files.deleteIfExists(target);else SafeFiles.atomicWrite(target,c.before());
            }catch(IOException e){failures.add(e.getMessage());}}
            try{SafeFiles.atomicWrite(backup.resolve("status.txt"),("APPLY_FAILED\n"+failure+"\nRollback: "+(failures.isEmpty()?"restored":String.join("; ",failures))).getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){failure.addSuppressed(ignored);}
            throw new IOException("APPLY_FAILED: "+failure.getMessage()+"\n"+(failures.isEmpty()?"已恢复本次写入的文件。":"部分文件需要人工恢复: "+failures)+"\n备份: "+backup,failure);
        }
        // Logging failure must not turn an already committed baseline into an apparent failed apply.
        try{SafeFiles.atomicWrite(backup.resolve("status.txt"),"APPLIED".getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){/* PREPARED journal still holds full pre/post images. */}
        return backup;
    }
    public static List<Change> diff(Map<String,byte[]> before,Map<String,byte[]> after){
        SortedSet<String> paths=new TreeSet<>(before.keySet());paths.addAll(after.keySet());List<Change> out=new ArrayList<>();
        for(String p:paths)if(!Arrays.equals(before.get(p),after.get(p)))out.add(new Change(p,before.get(p),after.get(p)));return List.copyOf(out);
    }
}
