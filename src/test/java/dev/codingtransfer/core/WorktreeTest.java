package dev.codingtransfer.core;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.codingtransfer.core.Model.*;
import static dev.codingtransfer.core.TestSupport.git;
import static dev.codingtransfer.core.TestSupport.config;

public final class WorktreeTest {
    private static int checks;
    static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static void check(boolean ok,String label){TestSupport.check(ok,label);checks++;System.out.println("PASS WORKTREE "+label);}
    public static void main(String[] args)throws Exception{TestRunner.main(new String[]{"worktree"});}
    static void run(Path temp)throws Exception{
        Path host=Files.createDirectories(temp.resolve("change-host"));TestSupport.initRepository(host);
        Files.writeString(host.resolve("a.txt"),"one\ntwo\nthree\nfour\nfive\n");git(host,"add","a.txt");git(host,"commit","-qm","initial");
        Files.writeString(host.resolve("a.txt"),"HOST\ntwo\nthree\nfour\nfive\n");Files.writeString(host.resolve("loose.txt"),"ignore me");
        SyncEngine engine=new SyncEngine(host,config(host,Role.HOST));Payload p=engine.generate("repo").payload();
        check(p.version()==3&&p.entries().size()==2,"tracked and untracked changes generated without acknowledged baseline");
        check(Arrays.equals(p.entries().getFirst().base(),b("one\ntwo\nthree\nfour\nfive\n")),"BASE is actual HEAD bytes");
        check(p.sourceBranch().equals("refs/heads/main"),"source branch preserved");
        Payload decoded=Codec.decode(Codec.encode(p));check(Arrays.equals(decoded.entries().getFirst().base(),p.entries().getFirst().base()),"V3 BASE round trip");
        Path vm=Files.createDirectories(temp.resolve("change-vm"));git(vm,"init","-q","-b","different");git(vm,"config","user.name","Test");git(vm,"config","user.email","test@example.invalid");
        Files.writeString(vm.resolve("a.txt"),"one\ntwo\nthree\nfour\nVM\n");git(vm,"add","a.txt");git(vm,"commit","-qm","different history");
        SyncEngine receiver=new SyncEngine(vm,config(vm,Role.VM));byte[] index=Files.readAllBytes(vm.resolve(".git/index"));String head=git(vm,"rev-parse","HEAD");
        receiver.apply(receiver.preview("repo",decoded));
        check(Files.readString(vm.resolve("a.txt")).equals("HOST\ntwo\nthree\nfour\nVM\n"),"different HEAD clean three-way merge preserves both edits");
        check(head.equals(git(vm,"rev-parse","HEAD"))&&Arrays.equals(index,Files.readAllBytes(vm.resolve(".git/index"))),"VM HEAD and index unchanged");
        check(receiver.preview("repo",decoded).changes.isEmpty(),"identical resend is no-op");
        Files.writeString(vm.resolve("a.txt"),"VM overlap\ntwo\nthree\nfour\nfive\n");
        SyncEngine.Preview conflict=receiver.preview("repo",decoded);
        check(conflict.conflicts.size()==1,"overlapping text requires resolution");
        try{receiver.apply(conflict);throw new AssertionError("unresolved apply allowed");}catch(java.io.IOException expected){check(expected.getMessage().contains("冲突"),"unresolved batch cannot apply");}
        check(Files.readString(vm.resolve("a.txt")).startsWith("VM overlap"),"cancel without resolve makes no writes");
        SyncEngine.Preview resolved=receiver.resolve(conflict,Map.of("a.txt",b("chosen\n")));
        check(Files.readString(vm.resolve("a.txt")).startsWith("VM overlap"),"resolution does not write business file");
        Files.writeString(vm.resolve("a.txt"),"external\n");
        try{receiver.apply(resolved);throw new AssertionError("stale accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("STALE_PREVIEW"),"external edits after resolution rejected");}
        String policy=config(vm,Role.VM).policy();String source=p.head();
        Payload add=Payload.worktree("repo",source,policy,"",List.of(new Entry("added.txt","-",hash(b("host")),Encoding.RAW,b("host"),null)));
        Files.writeString(vm.resolve("added.txt"),"vm");SyncEngine.Preview addConflict=receiver.preview("repo",add);check(addConflict.conflicts.size()==1,"add/add is conflict");
        receiver.apply(receiver.resolve(addConflict,Map.of("added.txt",b("both"))));check(Files.readString(vm.resolve("added.txt")).equals("both"),"explicit add/add resolution applied");
        Payload del=Payload.worktree("repo",source,policy,"",List.of(new Entry("added.txt",hash(b("base")),"-",Encoding.DELETE,new byte[0],b("base"))));
        SyncEngine.Preview deleteConflict=receiver.preview("repo",del);check(deleteConflict.conflicts.size()==1,"modified/delete is conflict");
        Map<String,byte[]> deletion=new HashMap<>();deletion.put("added.txt",null);receiver.apply(receiver.resolve(deleteConflict,deletion));check(!Files.exists(vm.resolve("added.txt")),"explicit delete resolution deletes rather than empties");
        byte[] binaryBase={0,1},binaryOurs={0,2},binaryTheirs={0,3};
        check(ThreeWayMerge.merge(vm,"git",binaryBase,binaryOurs,binaryTheirs).conflict(),"binary divergence requires explicit selection");
        check(ThreeWayMerge.merge(vm,"git",b("old"),null,b("new")).conflict(),"deleted/modified is conflict");
        check(ThreeWayMerge.merge(vm,"git",b("old"),b("old"),null).content()==null,"unchanged delete directly applies");
        SyncEngine.Preview noHeadBlock=receiver.preview("repo",add);git(vm,"commit","--allow-empty","-qm","HEAD only");receiver.apply(noHeadBlock);check(Files.readString(vm.resolve("added.txt")).equals("host"),"HEAD-only change after preview is accepted");
        try{receiver.preview("repo",new Payload(Type.WORKTREE,"repo",source,policy,List.of()));throw new AssertionError("old protocol accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("升级"),"legacy WORKTREE explicitly rejected");}
        Files.writeString(host.resolve("staged.txt"),"staged");git(host,"add","staged.txt");Files.writeString(host.resolve("staged.txt"),"final");
        Payload staged=engine.generate("repo").payload();check(staged.entries().stream().anyMatch(e->e.path().equals("staged.txt")&&Arrays.equals(e.data(),b("final"))&&e.base()==null),"staged add carries final unstaged bytes");
        git(host,"add","a.txt","staged.txt");git(host,"commit","-qm","commit edits");git(host,"checkout","-qb","other");Files.writeString(host.resolve("a.txt"),"branch-only\n");git(host,"add","a.txt");git(host,"commit","-qm","other branch");
        Files.delete(host.resolve("loose.txt"));
        check(engine.generate("repo").changes().isEmpty(),"branch changes are not HOST edits");
        Files.writeString(host.resolve("a.txt"),"next\n");Payload branchEdit=engine.generate("repo").payload();check(Arrays.equals(branchEdit.entries().getFirst().base(),b("branch-only\n")),"BASE follows selected branch HEAD");
        Files.writeString(host.resolve(".gitignore"),"*.tmp\n");Files.writeString(host.resolve("ignored.tmp"),"ignore");Files.createDirectories(host.resolve("excluded"));Files.writeString(host.resolve("excluded/skip"),"skip");git(host,"add","excluded/skip");
        check(engine.generate("repo").payload().entries().stream().map(Entry::path).collect(java.util.stream.Collectors.toSet()).equals(Set.of("a.txt",".gitignore")),"ordinary untracked included; ignored untracked and plugin exclusions not sent");
        List<String> blocks=ClipboardTransport.split(branchEdit,4);ClipboardTransport.Received received=null;for(String block:blocks)received=ClipboardTransport.receive(vm,config(vm,Role.VM),"repo",block);
        check(received!=null&&received.payload().version()==3,"V3 clipboard round trip");
        check(Arrays.equals(ThreeWayMerge.editedText("chosen\n",b("\uFEFFold\r\n")),b("\uFEFFchosen\r\n")),"manual merge preserves UTF8 BOM and CRLF");
        check(Arrays.equals(ThreeWayMerge.editedText("chosen\n",b("old\r")),b("chosen\r")),"manual merge preserves CR separators");
        check(ThreeWayMerge.mixedEndings(b("one\r\ntwo\n"))&&!ThreeWayMerge.mixedEndings(b("one\r\ntwo\r\n")),"mixed separators require explicit confirmation");
        Payload badBase=Payload.worktree("repo",source,policy,"",List.of(new Entry("x",hash(b("base")),hash(b("next")),Encoding.RAW,b("next"),b("tampered"))));
        try{Codec.encode(badBase);throw new AssertionError("tampered BASE accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("BASE_CHECKSUM"),"BASE bytes must match checksum");}
        Payload rollback=Payload.worktree("repo",source,policy,"",List.of(new Entry("r1","-",hash(b("one")),Encoding.RAW,b("one"),null),new Entry("r2","-",hash(b("two")),Encoding.RAW,b("two"),null)));
        try{receiver.apply(receiver.preview("repo",rollback),n->{if(n==2)throw new java.io.IOException("injected");});throw new AssertionError("failure swallowed");}catch(java.io.IOException expected){check(expected.getMessage().contains("APPLY_FAILED"),"WORKTREE failure runs existing transaction rollback");}
        check(!Files.exists(vm.resolve("r1"))&&!Files.exists(vm.resolve("r2")),"WORKTREE rollback removes all applied additions");
        byte[] multiBase=b("a\nkeep1\nkeep2\nb\nkeep3\nkeep4\nc\n"),multiOurs=b("ours1\nkeep1\nkeep2\nours2\nkeep3\nkeep4\nc\n"),multiTheirs=b("theirs1\nkeep1\nkeep2\ntheirs2\nkeep3\nkeep4\nc\n");
        check(ThreeWayMerge.merge(vm,"git",multiBase,multiOurs,multiTheirs).conflict(),"multiple merge-file conflicts are not process errors");
        git(host,"config","core.autocrlf","input");git(host,"config","core.safecrlf","warn");git(host,"add","a.txt");git(host,"commit","-qm","save readded");Files.writeString(host.resolve("a.txt"),"CRLF change\r\n");
        check(engine.generate("repo").payload().entries().stream().anyMatch(e->e.path().equals("a.txt")&&Arrays.equals(e.data(),b("CRLF change\r\n"))),"Git CRLF stderr warnings never contaminate path or content output");
        System.out.println("PASS ALL "+checks+" Worktree checks");
    }
}
