package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;
import static dev.codingtransfer.core.GitCommandRunner.*;
import static dev.codingtransfer.core.GitHistory.guardRepository;
import static dev.codingtransfer.core.GitHistory.refs;

/** HOST-only Git staging, metadata backup, ref updates and rollback. */
final class GitImportTransaction {
    private GitImportTransaction() {}
    private static String destination(String source){return source.startsWith("refs/heads/")?"refs/remotes/vm-sync/"+portableSuffix(source.substring(11)):source.startsWith("refs/tags/")?"refs/tags/vm-sync/"+portableSuffix(source.substring(10)):"refs/vmsync/refs/"+portableSuffix(source.substring(5));}
    private static String currentBranch(GitData data){return "refs/heads/vm-work/"+(data.branch().isEmpty()?"detached":portableSuffix(data.branch().substring(11)));}

    /** Lowercase ASCII paths distinguish source case, Unicode and literal escape markers on HOST. */
    private static String portableSuffix(String source){
        StringJoiner path=new StringJoiner("/");
        for(String component:source.split("/")){
            byte[] bytes=component.getBytes(StandardCharsets.UTF_8);StringBuilder encoded=new StringBuilder();
            for(byte value:bytes){
                int c=Byte.toUnsignedInt(value);
                if(c>='a'&&c<='z'||c>='0'&&c<='9'||c=='-'||c=='.')encoded.append((char)c);
                else encoded.append('_').append(Character.forDigit(c>>>4,16)).append(Character.forDigit(c&15,16));
            }
            if(encoded.length()<=240)path.add(encoded.toString());
            else{
                // Keep each filesystem component short without losing the original name.
                path.add("_x");String hex=HexFormat.of().formatHex(bytes);
                for(int i=0;i<hex.length();i+=200)path.add(hex.substring(i,Math.min(i+200,hex.length())));
                path.add("_end");
            }
        }
        return path.toString();
    }
    public record State(boolean exists,byte[] head,byte[] index,SortedMap<String,String> refs){
        public String fingerprint(){return exists+":"+hash(head)+":"+hash(index)+":"+refs.toString();}
    }
    private static State state(Path root,String executable)throws IOException{
        Path git=root.resolve(".git");SafeFiles.noLinks(git);
        if(!Files.exists(git))return new State(false,null,null,new TreeMap<>());
        guardRepository(root,executable);
        String aliases=run(root,executable,"for-each-ref","--format=%(symref)","refs/heads/vm-work/","refs/remotes/vm-sync/","refs/tags/vm-sync/","refs/vmsync/refs/");
        require(aliases.isBlank(),"同步专用 refs 不能是符号引用");
        return new State(true,SafeFiles.read(git.resolve("HEAD"),4096),Files.exists(git.resolve("index"))?SafeFiles.read(git.resolve("index")):null,refs(root,executable,"refs/"));
    }
    public record Plan(Path root,Path bundle,Payload payload,State before){}
    public static Plan prepare(Path workspace,Path root,String executable,Payload payload)throws IOException{
        require(payload.git()!=null,"缺少 Bundle 元数据");State before=state(root,executable);
        require(payload.type()!=Type.DELTA||before.exists,"缺少 HOST Git 基线，请重新全量同步");
        Path stage=SafeFiles.state(workspace).resolve("git-staging").resolve(UUID.randomUUID().toString());SafeFiles.noLinks(stage);Files.createDirectories(stage);
        Path bare=Files.createDirectory(stage.resolve("verify.git"));init(bare,executable,true,payload.head().length());
        if(before.exists&&payload.type()==Type.DELTA){Path objects=root.resolve(".git/objects");SafeFiles.noLinks(objects);SafeFiles.atomicWrite(bare.resolve("objects/info/alternates"),(objects.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
        byte[] bytes=payload.git().bundle();Path bundle=stage.resolve("incoming.bundle");
        if(bytes.length>0){SafeFiles.atomicWrite(bundle,bytes);run(bare,executable,"bundle","verify",bundle.toString());run(bare,executable,"bundle","unbundle",bundle.toString());}
        else require(payload.type()==Type.DELTA,"全量同步必须包含完整 Git Bundle");
        run(bare,executable,"cat-file","-e",payload.head()+"^{commit}");
        for(var ref:payload.git().refs().entrySet()){
            run(bare,executable,"check-ref-format",ref.getKey());run(bare,executable,"check-ref-format",destination(ref.getKey()));run(bare,executable,"cat-file","-e",ref.getValue()+"^{object}");
        }
        run(bare,executable,"check-ref-format",currentBranch(payload.git()));
        String tree=run(bare,executable,"ls-tree","-rz","--full-tree",payload.head());int count=0;
        for(String entry:tree.split("\0"))if(!entry.isEmpty()){
            int tab=entry.indexOf('\t');require(tab>0,"无效 Git tree");String mode=entry.substring(0,6),path=entry.substring(tab+1);
            require(mode.equals("100644")||mode.equals("100755"),"暂不支持 Git 符号链接或子模块: "+path);
            if(!path.equals(".vmsync")&&!path.startsWith(".vmsync/"))SafeFiles.relative(path);
            require(++count<=50000,"Git tree 文件过多");
        }
        return new Plan(root,bytes.length>0?bundle:null,payload,before);
    }
    private static void init(Path path,String executable,boolean bare,int hashLength)throws IOException{
        List<String> args=new ArrayList<>(List.of("init","--quiet","--template="+path.resolve(".vmsync/disabled-hooks")));
        if(bare)args.add("--bare");if(hashLength==64)args.add("--object-format=sha256");run(path,executable,null,args);
    }
    @FunctionalInterface private interface RefProgress{void updated(SortedMap<String,String> refs)throws IOException;}
    private static void changeRefs(Path root,String executable,SortedMap<String,String> before,SortedMap<String,String> after,RefProgress progress)throws IOException{
        // Legacy names may share a lock with a new portable name. Remove only those aliases first;
        // record each successful CAS so a subsequent failure can roll back from the actual state.
        Map<String,List<String>> groups=new TreeMap<>();Set<String> names=new TreeSet<>(before.keySet());names.addAll(after.keySet());
        for(String name:names)if(!Objects.equals(before.get(name),after.get(name)))
            groups.computeIfAbsent(name.toLowerCase(Locale.ROOT),key->new ArrayList<>()).add(name);
        Set<String> earlyDeletes=new TreeSet<>();
        for(List<String> aliases:groups.values())if(aliases.size()>1)
            for(String name:aliases)if(after.get(name)==null)earlyDeletes.add(name);
        NavigableSet<String> targets=new TreeSet<>();for(String name:after.keySet())targets.add(name.toLowerCase(Locale.ROOT));
        for(String name:before.keySet())if(!after.containsKey(name)){
            String folded=name.toLowerCase(Locale.ROOT),child=targets.ceiling(folded+"/");
            if(child!=null&&child.startsWith(folded+"/"))earlyDeletes.add(name);
            for(int slash=folded.indexOf('/');slash>=0;slash=folded.indexOf('/',slash+1))
                if(targets.contains(folded.substring(0,slash)))earlyDeletes.add(name);
        }
        Map<String,List<String>> removals=new TreeMap<>();
        for(String name:earlyDeletes)removals.computeIfAbsent(name.toLowerCase(Locale.ROOT),key->new ArrayList<>()).add(name);
        SortedMap<String,String> current=before;
        while(!removals.isEmpty()){
            SortedMap<String,String> next=new TreeMap<>(current);
            for(var iterator=removals.entrySet().iterator();iterator.hasNext();){
                List<String> aliases=iterator.next().getValue();next.remove(aliases.removeFirst());if(aliases.isEmpty())iterator.remove();
            }
            writeRefs(root,executable,current,next);current=next;progress.updated(current);
        }
        writeRefs(root,executable,current,after);progress.updated(after);
    }
    private static void writeRefs(Path root,String executable,SortedMap<String,String> before,SortedMap<String,String> after)throws IOException{
        StringBuilder text=new StringBuilder("start\n");Set<String> names=new TreeSet<>(before.keySet());names.addAll(after.keySet());
        for(String name:names){String old=before.get(name),next=after.get(name);if(Objects.equals(old,next))continue;
            require(name.startsWith("refs/")&&name.chars().noneMatch(c->c<=32),"不安全 ref");
            if(next==null)text.append("delete ").append(name).append(' ').append(old).append('\n');
            else if(old==null)text.append("create ").append(name).append(' ').append(next).append('\n');
            else text.append("update ").append(name).append(' ').append(next).append(' ').append(old).append('\n');
        }
        text.append("prepare\ncommit\n");run(root,executable,text.toString().getBytes(StandardCharsets.UTF_8),List.of("update-ref","--stdin"));
    }
    public static final class Transaction {
        private final Plan plan;private final String executable;private final State original;private State expected;
        private Transaction(Plan p,String exe,State state){plan=p;executable=exe;original=state;expected=state;}
        public void commit()throws IOException{
            Path root=plan.root;Payload p=plan.payload;
            require(state(root,executable).fingerprint().equals(original.fingerprint()),"STALE_PREVIEW: 应用期间 Git HEAD/refs/index 被外部修改");
            SortedMap<String,String> updated=new TreeMap<>(original.refs);
            updated.keySet().removeIf(k->k.startsWith("refs/heads/vm-work/")||k.startsWith("refs/remotes/vm-sync/")||k.startsWith("refs/tags/vm-sync/")||k.startsWith("refs/vmsync/refs/"));
            for(var ref:p.git().refs().entrySet()){
                updated.put(destination(ref.getKey()),ref.getValue());
                // Every VM branch needs a checkoutable local ref, not just the current branch.
                if(ref.getKey().startsWith("refs/heads/"))updated.put("refs/heads/vm-work/"+portableSuffix(ref.getKey().substring(11)),ref.getValue());
            }
            updated.put(currentBranch(p.git()),p.head());
            Result oldHead=command(root,executable,null,List.of("rev-parse","--verify","HEAD"));
            if(oldHead.code()==0&&headValue(oldHead.output().strip()))updated.put("refs/vmsync/recovery/"+UUID.randomUUID(),oldHead.output().strip());
            changeRefs(root,executable,original.refs,updated,refs->{
                expected=new State(true,original.head,original.index,refs);
                require(state(root,executable).fingerprint().equals(expected.fingerprint()),"STALE_PREVIEW: 引用迁移期间 Git 被外部修改");
            });
            run(root,executable,"read-tree",p.head());expected=new State(true,original.head,SafeFiles.read(root.resolve(".git/index")),updated);
            run(root,executable,"symbolic-ref","HEAD",currentBranch(p.git()));expected=new State(true,SafeFiles.read(root.resolve(".git/HEAD"),4096),expected.index,updated);
        }
        public void rollback()throws IOException{
            require(state(plan.root,executable).fingerprint().equals(expected.fingerprint()),"Git 在应用期间被外部修改，拒绝覆盖；请按备份人工恢复");
            changeRefs(plan.root,executable,expected.refs,original.refs,refs->{
                expected=new State(true,expected.head,expected.index,refs);
                require(state(plan.root,executable).fingerprint().equals(expected.fingerprint()),"Git 在回滚期间被外部修改，拒绝覆盖");
            });
            SafeFiles.atomicWrite(plan.root.resolve(".git/HEAD"),original.head);
            if(original.index==null)Files.deleteIfExists(plan.root.resolve(".git/index"));else SafeFiles.atomicWrite(plan.root.resolve(".git/index"),original.index);
        }
    }
    public static Transaction begin(Plan plan,String executable,Path backup)throws IOException{
        require(state(plan.root,executable).fingerprint().equals(plan.before.fingerprint()),"STALE_PREVIEW: Git HEAD/refs/index 已改变，请重新预览");
        if(!plan.before.exists)init(plan.root,executable,false,plan.payload.head().length());
        State original=state(plan.root,executable);
        SafeFiles.atomicWrite(backup.resolve("git-HEAD.before"),original.head);
        if(original.index!=null)SafeFiles.atomicWrite(backup.resolve("git-index.before"),original.index);
        StringBuilder refs=new StringBuilder();original.refs.forEach((k,v)->refs.append(v).append(' ').append(k).append('\n'));SafeFiles.atomicWrite(backup.resolve("git-refs.before"),refs.toString().getBytes(StandardCharsets.UTF_8));
        if(plan.bundle!=null){run(plan.root,executable,"bundle","verify",plan.bundle.toString());run(plan.root,executable,"bundle","unbundle",plan.bundle.toString());}
        return new Transaction(plan,executable,original);
    }
}
