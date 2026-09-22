package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.codingtransfer.core.Model.*;

/** Fixed local Git commands only. Bundle input cannot supply commands, repository paths or refspecs. */
public final class GitHistory {
    private GitHistory() {}
    public static void validRef(String ref,boolean branch)throws IOException{
        require(ref!=null&&ref.getBytes(StandardCharsets.UTF_8).length<1000&&(branch?ref.startsWith("refs/heads/"):ref.startsWith("refs/")),"不支持的 Git ref");
        require(!ref.contains("..")&&!ref.contains("@{")&&!ref.contains("//")&&!ref.endsWith("/")&&!ref.endsWith(".")
            &&ref.chars().noneMatch(c->c<=32||c==127||"~^:?*[\\".indexOf(c)>=0),"无效 Git ref: "+ref);
        for(String part:ref.split("/"))require(!part.startsWith(".")&&!part.endsWith(".lock"),"无效 Git ref: "+ref);
    }
    private record Result(int code,String output,byte[] bytes){}
    private static Result command(Path root,String executable,byte[] input,List<String> arguments)throws IOException{
        Path hooks=root.resolve(".vmsync/disabled-hooks");SafeFiles.noLinks(hooks);Files.createDirectories(hooks);
        try(var files=Files.list(hooks)){require(files.findAny().isEmpty(),"禁用 hooks 的目录必须为空");}
        List<String> cmd=new ArrayList<>(List.of(executable,"--no-optional-locks","-c","core.hooksPath="+hooks,"-c","core.fsmonitor=false","-c","core.splitIndex=false","-c","core.sparseCheckout=false","-c","gc.auto=0","-c","maintenance.auto=false","-c","protocol.allow=never","-C",root.toString()));cmd.addAll(arguments);
        ProcessBuilder builder=new ProcessBuilder(cmd).redirectErrorStream(true);builder.environment().keySet().removeIf(k->k.startsWith("GIT_"));builder.environment().put("GIT_TERMINAL_PROMPT","0");builder.environment().put("LC_ALL","C");
        Process p=builder.start();ExecutorService io=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"vmsync-git");t.setDaemon(true);return t;});
        Future<byte[]> out=io.submit(()->{try(InputStream in=p.getInputStream()){return in.readAllBytes();}});
        Future<?> writer=io.submit(()->{try(OutputStream stdin=p.getOutputStream()){if(input!=null)stdin.write(input);}return null;});
        try{
            if(!p.waitFor(120,TimeUnit.SECONDS))throw new IOException("Git 命令超时: "+arguments.getFirst());
            writer.get(2,TimeUnit.SECONDS);byte[] bytes=out.get(2,TimeUnit.SECONDS);
            return new Result(p.exitValue(),new String(bytes,StandardCharsets.UTF_8),bytes);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Git 操作已中断",e);}
        catch(ExecutionException|TimeoutException e){throw new IOException("Git IO 失败",e);}
        finally{p.destroyForcibly();out.cancel(true);writer.cancel(true);io.shutdownNow();}
    }
    static String run(Path root,String executable,String... args)throws IOException{return run(root,executable,null,List.of(args));}
    private static String run(Path root,String executable,byte[] input,List<String> args)throws IOException{
        Result r=command(root,executable,input,args);require(r.code==0,"Git "+args.getFirst()+" 失败: "+r.output.strip());return r.output.strip();
    }
    public record Tips(String head,String branch,SortedMap<String,String> refs){}
    public record RefChange(String ref,String path,String status,String before,String after,boolean working){
        public String label(){return "["+ref+" · "+(working?"working tree":"committed")+"] "+path;}
    }
    private static String tree(Path root,String executable,String object)throws IOException{
        if(object==null)return null;
        Result r=command(root,executable,null,List.of("rev-parse","--verify",object+"^{tree}"));
        return r.code==0?r.output.strip():null; // Non-tree refs still travel in the Bundle; they have no file list.
    }
    static List<RefChange> refChanges(Path root,String executable,String ref,String old,String now,Config config)throws IOException{
        String a=tree(root,executable,old),b=tree(root,executable,now);List<RefChange> rows=new ArrayList<>();
        if(Objects.equals(a,b))return rows;
        if(a==null||b==null){
            String listing=run(root,executable,"ls-tree","-rz","--name-only","--full-tree",a==null?b:a);
            for(String path:listing.split("\0"))if(!path.isEmpty()&&!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,a==null?"A":"D",a,b,false));}
        }else{
            String[] fields=run(root,executable,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-status","-z",a,b,"--").split("\0");
            for(int i=0;i+1<fields.length;i+=2){String path=fields[i+1];if(!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,fields[i],a,b,false));}}
        }
        return rows;
    }
    static List<RefChange> workingChanges(Path root,String executable,Tips tips,Config config)throws IOException{
        List<RefChange> rows=new ArrayList<>();String ref=tips.branch().isEmpty()?"HEAD":tips.branch();
        String[] fields=run(root,executable,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-status","-z",tips.head(),"--").split("\0");
        for(int i=0;i+1<fields.length;i+=2){String path=fields[i+1];if(!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,fields[i],tips.head(),null,true));}}
        for(String path:run(root,executable,"ls-files","--others","-z").split("\0"))if(!path.isEmpty()&&!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,"A",null,null,true));}
        return rows;
    }
    public static Change loadChange(Path root,String executable,RefChange row)throws IOException{
        SafeFiles.relative(row.path());byte[] before=row.status().equals("A")?null:blob(root,executable,row.before(),row.path());
        byte[] after=row.status().equals("D")?null:row.working()?SafeFiles.read(SafeFiles.resolve(root,row.path())):blob(root,executable,row.after(),row.path());
        return new Change(row.label(),before,after);
    }
    private static byte[] blob(Path root,String executable,String object,String path)throws IOException{
        require(headValue(object),"无效 diff 对象");Result r=command(root,executable,null,List.of("cat-file","blob",object+":"+path));
        require(r.code==0,"Git diff 内容读取失败: "+path);return r.bytes;
    }
    public static Tips tips(Path root,String executable)throws IOException{
        String head=GitReader.head(root,executable);guardRepository(root,executable);
        String branch=run(root,executable,"rev-parse","--symbolic-full-name","HEAD");if(branch.equals("HEAD"))branch="";else validRef(branch,true);
        SortedMap<String,String> refs=refs(root,executable,"refs/");
        require(refs.size()<=1000,"最多支持 1000 个 refs");
        for(var ref:refs.entrySet()){validRef(ref.getKey(),false);run(root,executable,"cat-file","-e",ref.getValue()+"^{object}");}
        return new Tips(head,branch,refs);
    }
    private static SortedMap<String,String> refs(Path root,String executable,String... prefixes)throws IOException{
        List<String> args=new ArrayList<>(List.of("for-each-ref","--format=%(objectname) %(refname)"));args.addAll(List.of(prefixes));
        String text=run(root,executable,null,args);SortedMap<String,String> refs=new TreeMap<>();
        if(!text.isEmpty())for(String line:text.split("\n")){String[] fields=line.split(" ",2);require(fields.length==2&&headValue(fields[0]),"无效 refs 输出");refs.put(fields[1],fields[0]);}return refs;
    }
    private static void guardRepository(Path root,String executable)throws IOException{
        Path git=root.resolve(".git");SafeFiles.noLinks(git);require(Files.isDirectory(git),"Bundle 同步暂不支持 linked worktree / .git 文件，请使用独立仓库");
        require(Path.of(run(root,executable,"rev-parse","--show-toplevel")).toRealPath().equals(root.toRealPath()),"映射必须是独立仓库根");
        require(!Files.exists(git.resolve("shallow")),"浅克隆仓库请先补全历史");
        for(String name:List.of("index.lock","MERGE_HEAD","CHERRY_PICK_HEAD","REVERT_HEAD","rebase-merge","rebase-apply"))require(!Files.exists(git.resolve(name)),"请先结束 Git 操作: "+name);
        SafeFiles.noLinks(git.resolve("objects"));SafeFiles.noLinks(git.resolve("index"));SafeFiles.noLinks(git.resolve("HEAD"));
    }
    public static byte[] bundle(Path root,String executable,Path output,Tips now,Payload baseline)throws IOException{
        List<String> args=new ArrayList<>(List.of("bundle","create","--quiet",output.toString(),"HEAD","--all"));
        if(baseline!=null){
            Set<String> have=new LinkedHashSet<>(baseline.git().refs().values());have.add(baseline.head());
            for(String hash:have){run(root,executable,"cat-file","-e",hash+"^{object}");args.add("^"+hash);}
        }
        Result result=command(root,executable,null,args);
        if(result.code!=0){require(baseline!=null&&result.output.contains("Refusing to create empty bundle"),"Bundle 生成失败: "+result.output.strip());return new byte[0];}
        byte[] bytes=SafeFiles.read(output);require(now.equals(tips(root,executable)),"Git refs 在打包期间改变，请重试");return bytes;
    }
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
            if(oldHead.code==0&&headValue(oldHead.output.strip()))updated.put("refs/vmsync/recovery/"+UUID.randomUUID(),oldHead.output.strip());
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
