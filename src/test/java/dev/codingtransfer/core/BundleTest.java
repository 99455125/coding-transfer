package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

final class BundleTest {
    private static int checks;
    private interface Checked{void run()throws Exception;}
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;System.out.println("PASS BUNDLE "+name);}
    private static void fails(String name,Checked work)throws Exception{try{work.run();}catch(IOException e){check(true,name+" ["+e.getMessage().split("\n")[0]+"]");return;}throw new AssertionError("Expected failure: "+name);}
    private static String git(Path root,String... args)throws Exception{
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString()));command.addAll(List.of(args));Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String text=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();if(p.waitFor()!=0)throw new IOException(text);return text;
    }
    private static void write(Path root,String name,String value)throws IOException{Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,value);}
    private static void init(Path root)throws Exception{Files.createDirectories(root);git(root,"init","-q","-b","main");git(root,"config","user.name","Test");git(root,"config","user.email","test@example.invalid");}
    private static void commit(Path root,String message)throws Exception{git(root,"add","a.txt","stable.txt");git(root,"commit","-qm",message);}
    private static Config cfg(Role role,Path vm){return new Config(role,List.of(new Config.Mapping("demo","project",vm.toString())),List.of("target/**","*.log"),4,"git");}
    private static Payload wire(Path workspace,Config cfg,Payload p)throws Exception{
        List<String> chunks=ClipboardTransport.split(p,4);ClipboardTransport.Received result=null;for(String chunk:chunks.reversed())result=ClipboardTransport.receive(workspace,cfg,"demo",chunk);check(result!=null&&result.payload()!=null,"v2 transport "+p.type());return result.payload();
    }
    static void run(Path temp)throws Exception{
        Path vm=temp.resolve("bundle-vm"),host=Files.createDirectories(temp.resolve("bundle-host")),root=host.resolve("project");init(vm);init(host);
        write(host,"outer.txt","outer");git(host,"add","outer.txt");git(host,"commit","-qm","outer");String outerHead=git(host,"rev-parse","HEAD"),outerIndex=hash(Files.readAllBytes(host.resolve(".git/index")));
        write(vm,"a.txt","one\n");write(vm,"stable.txt","original\r\n");commit(vm,"initial");String first=git(vm,"rev-parse","HEAD");git(vm,"branch","feature");git(vm,"tag","-a","v1","-m","version one");write(vm,"dirty.txt","VM draft");
        Config vc=cfg(Role.VM,vm),hc=cfg(Role.HOST,vm);BundleSync sender=new BundleSync(vm,vc),receiver=new BundleSync(host,hc);SyncEngine engine=new SyncEngine(host,hc);
        fails("increment requires acknowledged full",()->sender.generate("demo",true));
        Payload full=wire(host,hc,sender.generate("demo",false).payload());check(full.git().bundle().length>0,"real full bundle");
        engine.apply(engine.preview("demo",full));check(git(root,"rev-parse","HEAD").equals(first),"HOST actual HEAD and history imported");
        check(git(root,"rev-parse","refs/remotes/vm-sync/feature").equals(first),"all VM local branches mirrored");check(git(root,"rev-parse","refs/tags/vm-sync/v1").equals(git(vm,"rev-parse","v1")),"annotated tag retained");
        check(Files.readString(root.resolve("dirty.txt")).equals("VM draft"),"VM dirty file alongside history");
        sender.acceptReceipt("demo",wire(vm,vc,receiver.receipt("demo")));sender.acceptReceipt("demo",receiver.receipt("demo"));
        write(root,"stable.txt","HOST staged edit");git(root,"add","stable.txt");write(root,"extra.txt","HOST extra");write(root,"target/keep","excluded");
        write(vm,"a.txt","two\n");commit(vm,"second");String second=git(vm,"rev-parse","HEAD");
        Payload delta=wire(host,hc,sender.generate("demo",true).payload());check(delta.type()==Type.DELTA&&delta.entries().size()==1,"increment only changed file");
        SyncEngine.Preview plan=engine.preview("demo",delta);check(plan.changes.stream().anyMatch(c->c.path().equals("stable.txt")),"preview discards local edit outside delta");engine.apply(plan);
        check(git(root,"rev-parse","HEAD").equals(second)&&git(root,"rev-list","--count","HEAD").equals("2"),"increment advances Git history");
        check(Files.readString(root.resolve("stable.txt")).equals("original\r\n")&&!Files.exists(root.resolve("extra.txt")),"all local edits and extras discarded");
        check(git(root,"diff","--cached","--name-only").isEmpty(),"index aligned with VM HEAD");check(Files.readString(root.resolve("target/keep")).equals("excluded"),"excluded workspace file retained");
        fails("repeated delta rejected",()->engine.preview("demo",delta));fails("old receipt cannot confirm new pending",()->sender.acceptReceipt("demo",new Payload(Type.ACK,"demo",first,hc.policy(),List.of(),new GitData(full.git().snapshot(),"-","",new TreeMap<>(),new byte[0]))));
        sender.acceptReceipt("demo",receiver.receipt("demo"));write(vm,"dirty.txt","VM draft 2");Payload dirty=sender.generate("demo",true).payload();check(dirty.git().bundle().length==0,"same-history delta supports empty object bundle");engine.apply(engine.preview("demo",dirty));sender.acceptReceipt("demo",receiver.receipt("demo"));
        git(vm,"checkout","-q","feature");write(vm,"a.txt","feature divergent\n");commit(vm,"feature work");String feature=git(vm,"rev-parse","HEAD");Payload branch=sender.generate("demo",true).payload();engine.apply(engine.preview("demo",branch));check(git(root,"rev-parse","HEAD").equals(feature)&&git(root,"symbolic-ref","--short","HEAD").equals("vm-work/feature"),"divergent branch switches against project ACK baseline");sender.acceptReceipt("demo",receiver.receipt("demo"));
        git(vm,"tag","-d","v1");Payload refsOnly=sender.generate("demo",true).payload();engine.apply(engine.preview("demo",refsOnly));check(git(root,"tag","-l","vm-sync/v1").isEmpty(),"deleted VM mirror tag removed");sender.acceptReceipt("demo",receiver.receipt("demo"));
        String beforeHead=git(root,"rev-parse","HEAD"),beforeIndex=hash(Files.readAllBytes(root.resolve(".git/index")));write(vm,"dirty.txt","rollback test");Payload rollback=sender.generate("demo",true).payload();SyncEngine.Preview failPlan=engine.preview("demo",rollback);
        fails("injected apply rolls back",()->engine.apply(failPlan,n->{throw new IOException("injected");}));check(Files.readString(root.resolve("dirty.txt")).equals("VM draft 2")&&git(root,"rev-parse","HEAD").equals(beforeHead)&&hash(Files.readAllBytes(root.resolve(".git/index"))).equals(beforeIndex),"rollback restores files HEAD index");
        engine.apply(engine.preview("demo",rollback));sender.acceptReceipt("demo",receiver.receipt("demo"));
        Payload restart=new BundleSync(vm,vc).generate("demo",true).payload();check(restart.entries().isEmpty(),"ACK persists across engine restart");
        Payload bad=new Payload(restart.type(),restart.project(),restart.head(),restart.policy(),restart.entries(),new GitData(restart.git().snapshot(),restart.git().parent(),restart.git().branch(),restart.git().refs(),new byte[]{1,2,3}));fails("corrupt bundle fails before apply",()->engine.preview("demo",bad));
        SyncEngine.Preview stale=engine.preview("demo",restart);git(root,"branch","user-local");fails("Git refs changed after preview rejected",()->engine.apply(stale));
        engine.apply(engine.preview("demo",restart));check(git(root,"rev-parse","user-local").equals(beforeHead),"ordinary HOST branch preserved");
        sender.acceptReceipt("demo",receiver.receipt("demo"));
        write(vm,"a.txt","third commit\n");commit(vm,"third");Payload last=sender.generate("demo",true).payload();
        String priorRefs=git(root,"show-ref"),priorHead=Files.readString(root.resolve(".git/HEAD")),priorIndex=hash(Files.readAllBytes(root.resolve(".git/index"))),priorBaseline=hash(Files.readAllBytes(host.resolve(".vmsync/baselines/demo.gz")));
        fails("failure after Git commit rolls back metadata",()->engine.apply(engine.preview("demo",last),new SyncEngine.ApplyObserver(){public void afterWrite(int count){} public void afterGitCommit()throws IOException{throw new IOException("after Git commit");}}));
        check(git(root,"show-ref").equals(priorRefs)&&Files.readString(root.resolve(".git/HEAD")).equals(priorHead)&&hash(Files.readAllBytes(root.resolve(".git/index"))).equals(priorIndex)&&hash(Files.readAllBytes(host.resolve(".vmsync/baselines/demo.gz"))).equals(priorBaseline),"post-Git failure restores refs HEAD index baseline");
        check(Files.readString(root.resolve("a.txt")).equals("feature divergent\n"),"post-Git failure restores business files");
        write(root,".git/hooks/reference-transaction","#!/bin/sh\necho hook > '"+temp.resolve("hook-ran")+"'\n");root.resolve(".git/hooks/reference-transaction").toFile().setExecutable(true);
        engine.apply(engine.preview("demo",last));check(!Files.exists(temp.resolve("hook-ran")),"Git hooks never execute");sender.acceptReceipt("demo",receiver.receipt("demo"));
        Path missing=Files.createDirectories(temp.resolve("bundle-missing"));Config mc=cfg(Role.HOST,vm);SafeFiles.atomicWrite(missing.resolve(".vmsync/baselines/demo.gz"),Files.readAllBytes(host.resolve(".vmsync/baselines/demo.gz")));init(missing.resolve("project"));
        Payload next=sender.generate("demo",true).payload();fails("missing Git prerequisite rejected despite file baseline",()->new SyncEngine(missing,mc).preview("demo",next));
        git(vm,"checkout","--detach",first);Payload older=sender.generate("demo",true).payload();engine.apply(engine.preview("demo",older));check(git(root,"rev-parse","HEAD").equals(first),"increment supports historical detached checkout");sender.acceptReceipt("demo",receiver.receipt("demo"));
        Payload replacement=sender.generate("demo",false).payload();engine.apply(engine.preview("demo",replacement));check(git(root,"rev-parse","HEAD").equals(first),"full reinitialization retained after increments");
        write(root,"a.txt","HOST proposal\n");String vmIndex=hash(Files.readAllBytes(vm.resolve(".git/index")));
        Payload proposal=new SyncEngine(host,hc).generate("demo").payload();check(proposal.type()==Type.WORKTREE&&proposal.entries().size()==1,"HOST worktree generation after bundle baseline");
        SyncEngine vmEngine=new SyncEngine(vm,vc);vmEngine.apply(vmEngine.preview("demo",proposal));
        check(Files.readString(vm.resolve("a.txt")).equals("HOST proposal\n")&&hash(Files.readAllBytes(vm.resolve(".git/index"))).equals(vmIndex),"reverse HOST→VM applies without changing VM index");
        check(git(host,"rev-parse","HEAD").equals(outerHead)&&hash(Files.readAllBytes(host.resolve(".git/index"))).equals(outerIndex)&&Files.readString(host.resolve("outer.txt")).equals("outer"),"outer HOST Git HEAD index and document untouched");
        check(git(vm,"rev-parse","HEAD").equals(first),"VM Git never changed by sync");
        System.out.println("PASS ALL "+checks+" Bundle checks");
    }
}
