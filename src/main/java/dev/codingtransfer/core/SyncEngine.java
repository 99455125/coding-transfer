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
        public final List<ThreeWayMerge.Conflict> conflicts;
        private final SortedMap<String,byte[]> before,after;
        private Payload target;
        private GitImportTransaction.Plan gitPlan;
        private Preview(Payload payload,Path root,List<Change> changes,SortedMap<String,byte[]> before,SortedMap<String,byte[]> after,List<ThreeWayMerge.Conflict> conflicts){
            this.payload=payload;this.root=root;this.changes=List.copyOf(changes);this.before=before;this.after=after;this.conflicts=List.copyOf(conflicts);
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
        return config.role()==Role.VM ? generateSnapshot(id,root) : generateWorktree(id,root);
    }

    private Generated generateSnapshot(String id,Path root)throws IOException{
        String head=GitReader.head(root,config.git());
        SortedMap<String,byte[]> current=SafeFiles.scan(root,config);
        SafeFiles.same(current,SafeFiles.scan(root,config));
        require(head.equals(GitReader.head(root,config.git())),"VM HEAD 在扫描期间改变，请重试");
        return new Generated(snapshot(id,head,current),diff(new TreeMap<>(),current),root);
    }

    private Generated generateWorktree(String id,Path root)throws IOException{
        GitHistory.Tips tips=GitHistory.tips(root,config.git());
        List<GitHistory.RefChange> rows=GitHistory.hostChanges(root,config.git(),tips,config);
        List<Change> changes=new ArrayList<>();List<Entry> entries=new ArrayList<>();
        for(GitHistory.RefChange row:rows){Change c=GitHistory.loadTrackedChange(root,config.git(),row);if(!Arrays.equals(c.before(),c.after()))changes.add(c);}
        for(Change c:changes){
            byte[] a=c.before(),b=c.after();entries.add(new Entry(c.path(),hash(a),hash(b),b==null?Encoding.DELETE:Encoding.RAW,b==null?new byte[0]:b,a));
        }
        require(rows.equals(GitHistory.hostChanges(root,config.git(),tips,config)),"HOST 变更集合在生成期间改变，请重试");
        for(GitHistory.RefChange row:rows){Change again=GitHistory.loadTrackedChange(root,config.git(),row);Entry e=entries.stream().filter(v->v.path().equals(row.path())).findFirst().orElse(null);
            require(e==null?Arrays.equals(again.before(),again.after()):e.before().equals(hash(again.before()))&&e.after().equals(hash(again.after())),"HOST 文件在生成期间改变，请重试: "+row.path());}
        GitHistory.Tips end=GitHistory.tips(root,config.git());require(tips.head().equals(end.head())&&tips.branch().equals(end.branch()),"HOST HEAD/分支在生成期间改变，请重试");
        Payload payload=Payload.worktree(id,tips.head(),config.policy(),tips.branch(),entries);Codec.validate(payload);
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
        List<ThreeWayMerge.Conflict> conflicts=new ArrayList<>();
        SortedMap<String,byte[]> before=SafeFiles.scan(root,config),after;
        if(config.role()==Role.HOST)after=manifest(target);
        else{
            after=new TreeMap<>(before);
            for(Entry e:payload.entries()){
                require(!config.excluded(e.path()),"包中存在排除文件: "+e.path());SafeFiles.resolve(root,e.path());
                byte[] old=before.get(e.path()),theirs=e.encoding()==Encoding.DELETE?null:e.data();
                ThreeWayMerge.Result merged=ThreeWayMerge.merge(root,config.git(),e.base(),old,theirs);
                if(merged.conflict()){conflicts.add(new ThreeWayMerge.Conflict(e.path(),e.base(),old,theirs));continue;}
                byte[] next=merged.content();
                if(next==null)after.remove(e.path());else after.put(e.path(),next);
            }
        }
        require(after.size()<=FILE_COUNT,"目标项目文件数量超过限制");
        SafeFiles.validateNames(after.keySet());
        List<Change> changes=diff(before,after);preflight(root,changes);checkHead(root,payload);
        Preview preview=new Preview(payload,root,changes,before,after,conflicts);preview.target=target;
        if(payload.git()!=null)preview.gitPlan=GitImportTransaction.prepare(workspace,root,config.git(),payload);
        return preview;
    }
    private void checkHead(Path root,Payload p)throws IOException{
        if(config.role()==Role.VM){require(p.version()==3,"请升级两端插件并重新生成 HOST 传输；旧 WORKTREE 不支持三方合并");GitReader.head(root,config.git());}
    }
    public Preview resolve(Preview original,Map<String,byte[]> resolutions)throws IOException{
        require(original.payload.type()==Type.WORKTREE&&config.role()==Role.VM,"仅 WORKTREE 支持冲突解决");
        Set<String> expected=new HashSet<>();for(var c:original.conflicts)expected.add(c.path());
        require(resolutions.keySet().equals(expected),"必须解决全部冲突后才能应用");
        SafeFiles.same(original.before,SafeFiles.scan(original.root,config));
        SortedMap<String,byte[]> after=new TreeMap<>(original.after);
        for(var e:resolutions.entrySet()){if(e.getValue()==null)after.remove(e.getKey());else after.put(e.getKey(),e.getValue().clone());}
        require(after.size()<=FILE_COUNT,"目标项目文件数量超过限制");SafeFiles.validateNames(after.keySet());
        List<Change> changes=diff(original.before,after);preflight(original.root,changes);
        Preview result=new Preview(original.payload,original.root,changes,original.before,after,List.of());result.target=original.target;return result;
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
        require(preview.conflicts.isEmpty(),"存在未解决冲突，不能应用");
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
        List<Change> done=new ArrayList<>();GitImportTransaction.Transaction git=null;
        try{
            if(preview.payload.type()==Type.DELTA)new BundleSync(workspace,config).target(preview.payload);
            if(preview.gitPlan!=null)git=GitImportTransaction.begin(preview.gitPlan,config.git(),backup);
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
