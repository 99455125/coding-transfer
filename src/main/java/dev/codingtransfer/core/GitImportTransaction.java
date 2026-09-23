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
    private static String destination(String source){return source.startsWith("refs/heads/")?"refs/remotes/vm-sync/"+source.substring(11):source.startsWith("refs/tags/")?"refs/tags/vm-sync/"+source.substring(10):"refs/vmsync/refs/"+source.substring(5);}
    private static String currentBranch(GitData data){return "refs/heads/vm-work/"+(data.branch().isEmpty()?"detached":data.branch().substring(11));}
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
    private static void changeRefs(Path root,String executable,SortedMap<String,String> before,SortedMap<String,String> after)throws IOException{
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
                if(ref.getKey().startsWith("refs/heads/"))updated.put("refs/heads/vm-work/"+ref.getKey().substring(11),ref.getValue());
            }
            updated.put(currentBranch(p.git()),p.head());
            Result oldHead=command(root,executable,null,List.of("rev-parse","--verify","HEAD"));
            if(oldHead.code()==0&&headValue(oldHead.output().strip()))updated.put("refs/vmsync/recovery/"+UUID.randomUUID(),oldHead.output().strip());
            changeRefs(root,executable,original.refs,updated);expected=new State(true,original.head,original.index,updated);
            run(root,executable,"read-tree",p.head());expected=new State(true,original.head,SafeFiles.read(root.resolve(".git/index")),updated);
            run(root,executable,"symbolic-ref","HEAD",currentBranch(p.git()));expected=new State(true,SafeFiles.read(root.resolve(".git/HEAD"),4096),expected.index,updated);
        }
        public void rollback()throws IOException{
            require(state(plan.root,executable).fingerprint().equals(expected.fingerprint()),"Git 在应用期间被外部修改，拒绝覆盖；请按备份人工恢复");
            changeRefs(plan.root,executable,expected.refs,original.refs);
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
