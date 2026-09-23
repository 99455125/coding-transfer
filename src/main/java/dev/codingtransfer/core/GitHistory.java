package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.GitCommandRunner.*;
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
    public record Tips(String head,String branch,SortedMap<String,String> refs){}
    public record RefChange(String ref,String path,String status,String before,String after,boolean working){
        public String label(){return "["+ref+" · "+(working?"working tree":"committed")+"] "+path;}
    }
    private static String tree(Path root,String executable,String object)throws IOException{
        if(object==null)return null;
        Result r=command(root,executable,null,List.of("rev-parse","--verify",object+"^{tree}"));
        return r.code()==0?r.output().strip():null; // Non-tree refs still travel in the Bundle; they have no file list.
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
        List<RefChange> rows=new ArrayList<>(trackedChanges(root,executable,tips,config));String ref=tips.branch().isEmpty()?"HEAD":tips.branch();
        for(String path:run(root,executable,"ls-files","--others","-z").split("\0"))if(!path.isEmpty()&&!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,"A",null,null,true));}
        return rows;
    }
    static List<RefChange> hostChanges(Path root,String executable,Tips tips,Config config)throws IOException{
        Map<String,RefChange> rows=new TreeMap<>();
        for(RefChange row:trackedChanges(root,executable,tips,config))rows.put(row.path(),row);
        String ref=tips.branch().isEmpty()?"HEAD":tips.branch();
        Result untracked=command(root,executable,null,List.of("ls-files","--others","--exclude-standard","-z"));
        require(untracked.code()==0,"Git ls-files 失败: "+untracked.error().strip());
        // NUL-delimited filenames must retain leading whitespace.
        for(String path:untracked.output().split("\0"))if(!path.isEmpty()&&!config.excluded(path)){
            SafeFiles.relative(path);rows.putIfAbsent(path,new RefChange(ref,path,"A",null,null,true));
        }
        return List.copyOf(rows.values());
    }
    static List<RefChange> trackedChanges(Path root,String executable,Tips tips,Config config)throws IOException{
        List<RefChange> rows=new ArrayList<>();String ref=tips.branch().isEmpty()?"HEAD":tips.branch();
        String[] fields=run(root,executable,"diff","--no-ext-diff","--no-textconv","--no-renames","--name-status","-z",tips.head(),"--").split("\0");
        for(int i=0;i+1<fields.length;i+=2){
            String status=fields[i],path=fields[i+1];require(Set.of("A","M","D").contains(status),"暂不支持的 Git 工作区状态 "+status+": "+path);
            if(!config.excluded(path)){SafeFiles.relative(path);rows.add(new RefChange(ref,path,status,tips.head(),null,true));}
        }
        return List.copyOf(rows);
    }
    private static Change change(Path root,String executable,RefChange row,boolean label)throws IOException{
        SafeFiles.relative(row.path());byte[] before=row.status().equals("A")?null:blob(root,executable,row.before(),row.path());
        byte[] after=row.status().equals("D")?null:row.working()?SafeFiles.read(SafeFiles.resolve(root,row.path())):blob(root,executable,row.after(),row.path());
        return new Change(label?row.label():row.path(),before,after);
    }
    public static Change loadChange(Path root,String executable,RefChange row)throws IOException{return change(root,executable,row,true);}
    static Change loadTrackedChange(Path root,String executable,RefChange row)throws IOException{
        Change result=change(root,executable,row,false);
        // A path deleted from the index may have been recreated before sending.
        // It was tracked at HEAD: send its actual final worktree bytes, not the index deletion.
        Path file=SafeFiles.resolve(root,row.path());
        if(row.status().equals("D")&&Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new Change(row.path(),result.before(),SafeFiles.read(file));
        return result;
    }
    private static byte[] blob(Path root,String executable,String object,String path)throws IOException{
        require(headValue(object),"无效 diff 对象");Result r=command(root,executable,null,List.of("cat-file","blob",object+":"+path));
        require(r.code()==0,"Git diff 内容读取失败: "+path+" "+r.error().strip());return r.bytes();
    }
    static byte[] mergeFile(Path root,String executable,byte[] base,byte[] ours,byte[] theirs)throws IOException{
        require(base!=null&&ours!=null&&theirs!=null,"三方文本合并需要完整内容");
        Path dir=SafeFiles.state(root).resolve("merge-staging").resolve(UUID.randomUUID().toString());SafeFiles.noLinks(dir);Files.createDirectories(dir);
        Path baseFile=dir.resolve("base"),oursFile=dir.resolve("ours"),theirsFile=dir.resolve("theirs");
        try{
            SafeFiles.atomicWrite(baseFile,base);SafeFiles.atomicWrite(oursFile,ours);SafeFiles.atomicWrite(theirsFile,theirs);
            Result result=command(root,executable,null,List.of("merge-file","--stdout","--diff3","-L","OURS","-L","BASE","-L","THEIRS",oursFile.toString(),baseFile.toString(),theirsFile.toString()));
            if(result.code()==0)return result.bytes();if(result.code()>0&&result.code()<=127&&result.error().isBlank())return null;throw new IOException("Git merge-file 失败: "+result.error().strip());
        }finally{
            Files.deleteIfExists(baseFile);Files.deleteIfExists(oursFile);Files.deleteIfExists(theirsFile);Files.deleteIfExists(dir);
        }
    }
    public static Tips tips(Path root,String executable)throws IOException{
        String head=GitReader.head(root,executable);guardRepository(root,executable);
        String branch=run(root,executable,"rev-parse","--symbolic-full-name","HEAD");if(branch.equals("HEAD"))branch="";else validRef(branch,true);
        SortedMap<String,String> refs=refs(root,executable,"refs/");
        require(refs.size()<=1000,"最多支持 1000 个 refs");
        for(var ref:refs.entrySet()){validRef(ref.getKey(),false);run(root,executable,"cat-file","-e",ref.getValue()+"^{object}");}
        return new Tips(head,branch,refs);
    }
    static SortedMap<String,String> refs(Path root,String executable,String... prefixes)throws IOException{
        List<String> args=new ArrayList<>(List.of("for-each-ref","--format=%(objectname) %(refname)"));args.addAll(List.of(prefixes));
        String text=run(root,executable,null,args);SortedMap<String,String> refs=new TreeMap<>();
        if(!text.isEmpty())for(String line:text.split("\n")){String[] fields=line.split(" ",2);require(fields.length==2&&headValue(fields[0]),"无效 refs 输出");refs.put(fields[1],fields[0]);}return refs;
    }
    static void guardRepository(Path root,String executable)throws IOException{
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
        if(result.code()!=0){require(baseline!=null&&result.error().contains("Refusing to create empty bundle"),"Bundle 生成失败: "+result.error().strip());return new byte[0];}
        byte[] bytes=SafeFiles.read(output);require(now.equals(tips(root,executable)),"Git refs 在打包期间改变，请重试");return bytes;
    }
}
