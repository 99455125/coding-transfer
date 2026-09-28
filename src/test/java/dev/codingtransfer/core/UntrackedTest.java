package dev.codingtransfer.core;

import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

public final class UntrackedTest {
    private static int checks;
    private static void check(boolean ok,String label){TestSupport.check(ok,label);checks++;System.out.println("PASS UNTRACKED "+label);}
    public static void main(String[] args)throws Exception{TestRunner.main(new String[]{"worktree"});}
    static void run(Path temp)throws Exception{
        Path host=Files.createDirectories(temp.resolve("untracked-host"));
        TestSupport.initRepository(host);
        Files.writeString(host.resolve("tracked.txt"),"base");Files.writeString(host.resolve(".gitignore"),"*.log\nignored/\n");
        TestSupport.git(host,"add","tracked.txt",".gitignore");TestSupport.git(host,"commit","-qm","base");
        byte[] index=Files.readAllBytes(host.resolve(".git/index"));String head=TestSupport.git(host,"rev-parse","HEAD");
        Files.createDirectories(host.resolve("src"));Files.writeString(host.resolve("src/New.java"),"class New {}\n");
        Files.write(host.resolve("empty"),new byte[0]);Files.write(host.resolve("data.bin"),new byte[]{0,4,2});
        Files.writeString(host.resolve("debug.log"),"ignored");Files.createDirectories(host.resolve("ignored"));Files.writeString(host.resolve("ignored/file"),"ignored");
        Files.createDirectories(host.resolve("excluded"));Files.writeString(host.resolve("excluded/file"),"excluded");
        SyncEngine sender=new SyncEngine(host,TestSupport.config(host,Role.HOST));Payload p=sender.generate("repo").payload();
        check(p.entries().stream().map(Entry::path).collect(java.util.stream.Collectors.toSet()).equals(Set.of("src/New.java","empty","data.bin")),"new files included without add; ignored, excluded and metadata omitted");
        check(p.entries().stream().allMatch(e->e.base()==null&&e.before().equals("-")&&e.encoding()==Encoding.RAW),"new files encoded as Add with absent BASE");
        check(Arrays.equals(index,Files.readAllBytes(host.resolve(".git/index")))&&head.equals(TestSupport.git(host,"rev-parse","HEAD")),"generation leaves HOST index and HEAD unchanged");
        Path vm=Files.createDirectories(temp.resolve("untracked-vm"));TestSupport.initRepository(vm);TestSupport.git(vm,"commit","--allow-empty","-qm","different base");
        SyncEngine receiver=new SyncEngine(vm,TestSupport.config(vm,Role.VM));byte[] vmIndex=Files.readAllBytes(vm.resolve(".git/index"));
        receiver.apply(receiver.preview("repo",Codec.decode(Codec.encode(p))));
        check(Files.readString(vm.resolve("src/New.java")).equals("class New {}\n")&&Files.size(vm.resolve("empty"))==0&&Arrays.equals(Files.readAllBytes(vm.resolve("data.bin")),new byte[]{0,4,2}),"new text, binary and empty files applied on VM");
        check(Arrays.equals(vmIndex,Files.readAllBytes(vm.resolve(".git/index"))),"new files remain unstaged on VM");
        check(receiver.preview("repo",p).changes.isEmpty(),"same Add received twice is no-op");
        Files.writeString(vm.resolve("src/New.java"),"VM own content");SyncEngine.Preview conflict=receiver.preview("repo",p);
        check(conflict.conflicts.size()==1&&conflict.conflicts.getFirst().path().equals("src/New.java"),"existing different VM file enters Add/Add conflict");
        try{receiver.apply(conflict);throw new AssertionError("unresolved Add overwrote VM");}catch(java.io.IOException expected){}
        check(Files.readString(vm.resolve("src/New.java")).equals("VM own content"),"unresolved new-file conflict cannot overwrite VM");
        TestSupport.git(host,"rm","--cached","tracked.txt");Files.writeString(host.resolve("tracked.txt"),"recreated");Payload recreated=sender.generate("repo").payload();
        check(recreated.entries().stream().filter(e->e.path().equals("tracked.txt")).count()==1&&recreated.entries().stream().filter(e->e.path().equals("tracked.txt")).findFirst().orElseThrow().base()!=null&&Arrays.equals(recreated.entries().stream().filter(e->e.path().equals("tracked.txt")).findFirst().orElseThrow().data(),"recreated".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"untracked enumeration does not duplicate staged-deleted HEAD path or lose BASE");
        Files.writeString(host.resolve(" new.txt"),"leading space");
        check(sender.generate("repo").payload().entries().stream().anyMatch(e->e.path().equals(" new.txt")),"leading whitespace in new filename preserved");
        System.out.println("PASS ALL "+checks+" Untracked checks");
    }
}
