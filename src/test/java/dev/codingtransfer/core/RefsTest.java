package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;
import static dev.codingtransfer.core.TestSupport.git;

final class RefsTest {
    private static int checks;
    private static void check(boolean value,String label){TestSupport.check(value,label);checks++;System.out.println("PASS REFS "+label);}

    private static boolean row(List<GitHistory.RefChange> rows,String ref,String path,boolean working){return rows.stream().anyMatch(r->r.ref().equals(ref)&&r.path().equals(path)&&r.working()==working);}
    static void run(Path temp)throws Exception{
        caseDistinctRefs(temp);
        Path vm=Files.createDirectory(temp.resolve("refs-vm")),host=Files.createDirectory(temp.resolve("refs-host")),local=host.resolve("project");
        TestSupport.initRepository(vm);
        Files.writeString(vm.resolve("a.txt"),"base\n");Files.writeString(vm.resolve("stable.txt"),"stable\n");git(vm,"add","a.txt","stable.txt");git(vm,"commit","-qm","base");String first=git(vm,"rev-parse","HEAD");
        git(vm,"branch","feature");git(vm,"update-ref","refs/remotes/origin/feature",first);git(vm,"update-ref","refs/custom/release",first);
        Config vc=new Config(Role.VM,List.of(new Config.Mapping("repo","project",vm.toString())),List.of("target/**"),4096,"git"),hc=new Config(Role.HOST,vc.projects(),vc.exclude(),4096,"git");
        BundleSync sender=new BundleSync(vm,vc),receiver=new BundleSync(host,hc);SyncEngine engine=new SyncEngine(host,hc);
        Payload full=sender.generate("repo",false).payload();check(full.git().refs().containsKey("refs/custom/release")&&full.git().refs().containsKey("refs/remotes/origin/feature"),"entire refs manifest includes custom and remote refs");
        engine.apply(engine.preview("repo",full));sender.acceptReceipt("repo",receiver.receipt("repo"));
        check(git(local,"rev-parse","vm-work/feature").equals(first),"full creates non-current checkoutable branch");
        check(git(local,"rev-parse","refs/vmsync/refs/custom/release").equals(first),"custom ref imported into reserved HOST namespace");
        git(vm,"checkout","-q","feature");Files.writeString(vm.resolve("a.txt"),"feature change\n");Files.writeString(vm.resolve("feature.txt"),"new\n");git(vm,"add","a.txt","feature.txt");git(vm,"commit","-qm","feature commit");String feature=git(vm,"rev-parse","HEAD");git(vm,"checkout","-q","main");
        git(vm,"update-ref","refs/remotes/origin/feature",feature);git(vm,"update-ref","refs/custom/release",feature);
        List<GitHistory.RefChange> rows=sender.refChanges("repo");check(row(rows,"refs/heads/feature","a.txt",false),"non-current feature committed files visible while main checked out");
        check(!row(rows,"refs/heads/main","a.txt",false),"unchanged current committed file not added");check(rows.stream().noneMatch(GitHistory.RefChange::working),"clean current working tree stays empty");
        GitHistory.RefChange selected=rows.stream().filter(r->r.ref().equals("refs/heads/feature")&&r.path().equals("a.txt")).findFirst().orElseThrow();Change diff=GitHistory.loadChange(vm,"git",selected);
        check(new String(diff.before(),StandardCharsets.UTF_8).equals("base\n")&&new String(diff.after(),StandardCharsets.UTF_8).equals("feature change\n"),"other branch diff loads on demand");
        check(git(vm,"rev-parse","HEAD").equals(first),"refresh and diff do not checkout another VM branch");
        git(local,"checkout","-qb","host-other");Payload delta=sender.generate("repo",true).payload();check(delta.head().equals(first)&&delta.entries().isEmpty()&&delta.git().bundle().length>0,"Bundle carries non-current changes even with empty working-file delta");
        engine.apply(engine.preview("repo",delta));check(git(local,"rev-parse","refs/remotes/vm-sync/feature").equals(feature),"HOST different checkout does not prevent feature increment");
        git(local,"checkout","-q","vm-work/feature");
        check(Files.readString(local.resolve("a.txt")).equals("feature change\n")&&Files.exists(local.resolve("feature.txt")),"checkout non-current local branch exposes incremental files");
        check(git(local,"rev-parse","host-other").equals(first),"ordinary HOST branch preserved");
        git(local,"checkout","-q","vm-work/main");
        check(git(local,"rev-parse","refs/vmsync/refs/remotes/origin/feature").equals(feature),"remote refs increment imported");sender.acceptReceipt("repo",receiver.receipt("repo"));
        check(sender.refChanges("repo").isEmpty(),"all refs baseline advances after ACK");
        Files.writeString(vm.resolve("a.txt"),"committed main\n");git(vm,"add","a.txt");git(vm,"commit","-qm","main commit");Files.writeString(vm.resolve("a.txt"),"base\n");Files.writeString(vm.resolve("draft.txt"),"untracked");
        rows=sender.refChanges("repo");check(row(rows,"refs/heads/main","a.txt",false)&&row(rows,"refs/heads/main","a.txt",true),"committed change and working reversion both displayed");check(row(rows,"refs/heads/main","draft.txt",true),"untracked current file displayed");
        check(rows.stream().noneMatch(r->r.ref().equals("refs/heads/feature")),"other ref compared to its own ACK tip not current HEAD");
        String hostHead=git(local,"rev-parse","HEAD");Files.writeString(local.resolve("stable.txt"),"HOST local draft");SyncEngine.Generated hostChanges=engine.generate("repo");
        check(hostChanges.changes().size()==1&&hostChanges.changes().getFirst().path().equals("stable.txt"),"HOST refresh remains current worktree-only and ignores other refs");check(git(local,"rev-parse","HEAD").equals(hostHead),"HOST refresh does not switch branches");
        // An arbitrary non-commit ref must also be carried by the refs-based bundle.
        String blob=git(vm,"hash-object","-w","draft.txt");git(vm,"update-ref","refs/custom/blob",blob);Payload withBlob=sender.generate("repo",true).payload();engine.apply(engine.preview("repo",withBlob));check(git(local,"rev-parse","refs/vmsync/refs/custom/blob").equals(blob),"non-commit ref object also transferred");sender.acceptReceipt("repo",receiver.receipt("repo"));
        git(vm,"update-ref","-d","refs/custom/release");Payload removed=sender.generate("repo",true).payload();engine.apply(engine.preview("repo",removed));check(git(local,"for-each-ref","--format=%(refname)","refs/vmsync/refs/custom/release").isEmpty(),"deleted arbitrary ref removed from mirror");
        BitSet copied=new BitSet();copied.set(2);check(copied.nextClearBit(0)+1==1,"middle-first copy keeps first missing 1");copied.set(0);check(copied.nextClearBit(0)+1==2,"copy missing advances to 2");copied.set(1);check(copied.nextClearBit(0)+1==4,"copy missing skips previously copied 3");
        System.out.println("PASS ALL "+checks+" Refs checks");
    }

    private static void caseDistinctRefs(Path temp)throws Exception{
        Path vm=Files.createDirectory(temp.resolve("case-refs-vm")),host=Files.createDirectory(temp.resolve("case-refs-host"));
        TestSupport.initRepository(vm);Files.writeString(vm.resolve("a.txt"),"base\n");
        git(vm,"add","a.txt");git(vm,"commit","-qm","base");String head=git(vm,"rev-parse","HEAD");
        // Packed refs can retain case-distinct names even on a case-insensitive filesystem.
        StringBuilder packed=new StringBuilder();
        for(String tag:List.of("A","A".repeat(100),"A".repeat(200),"V0.3.12","_41/foo","_X","v0.3.12").stream().sorted().toList())packed.append(head).append(" refs/tags/").append(tag).append('\n');
        Files.writeString(vm.resolve(".git/packed-refs"),packed);
        git(vm,"branch","Feature");git(vm,"branch","feature_46");
        Config vc=new Config(Role.VM,List.of(new Config.Mapping("case","project",vm.toString())),List.of(),4,"git"),hc=new Config(Role.HOST,vc.projects(),vc.exclude(),4,"git");
        BundleSync sender=new BundleSync(vm,vc),receiver=new BundleSync(host,hc);SyncEngine engine=new SyncEngine(host,hc);
        Payload full=sender.generate("case",false).payload();
        check(full.git().refs().containsKey("refs/tags/V0.3.12")&&full.git().refs().containsKey("refs/tags/v0.3.12"),"case-distinct source tags remain in wire manifest");
        engine.apply(engine.preview("case",full));Path root=host.resolve("project");
        check(git(root,"rev-parse","refs/tags/vm-sync/_560.3.12").equals(head)&&git(root,"rev-parse","refs/tags/vm-sync/v0.3.12").equals(head),"case-distinct tags both imported without lock collision");
        check(git(root,"for-each-ref","--format=%(refname)","refs/tags/vm-sync/").lines().count()==7,"long source names with a common prefix import without file/directory collision");
        check(git(root,"rev-parse","refs/heads/vm-work/_46eature").equals(head)&&git(root,"rev-parse","refs/heads/vm-work/feature_5f46").equals(head),"branch names and literal escape markers remain distinct");
        sender.acceptReceipt("case",receiver.receipt("case"));
        // A previously imported single uppercase tag must migrate without sharing the new lowercase lock.
        git(root,"update-ref","-d","refs/tags/vm-sync/_560.3.12");git(root,"update-ref","-d","refs/tags/vm-sync/v0.3.12");
        git(root,"update-ref","-d","refs/tags/vm-sync/_41");git(root,"update-ref","-d","refs/tags/vm-sync/_5f41/foo");
        for(String ref:git(root,"for-each-ref","--format=%(refname)","refs/tags/vm-sync/_x/").lines().toList())git(root,"update-ref","-d",ref);
        git(root,"update-ref","-d","refs/tags/vm-sync/_5f_58");
        git(root,"update-ref","refs/tags/vm-sync/V0.3.12",head);
        git(root,"update-ref","refs/tags/vm-sync/A",head);git(root,"update-ref","refs/tags/vm-sync/_41/foo",head);
        git(root,"update-ref","refs/tags/vm-sync/_X",head);git(root,"update-ref","refs/tags/vm-sync/"+"A".repeat(100),head);git(root,"update-ref","refs/tags/vm-sync/"+"A".repeat(200),head);
        git(vm,"checkout","-q","Feature");Payload delta=sender.generate("case",true).payload();
        String legacyRefs=git(root,"show-ref"),legacyHead=Files.readString(root.resolve(".git/HEAD"));
        Path blocked=root.resolve(".git/refs/tags/vm-sync/_560.3.12.lock");Files.writeString(blocked,"");
        try{engine.apply(engine.preview("case",delta));throw new AssertionError("expected migration failure");}catch(IOException expected){check(expected.getMessage().contains("_560.3.12"),"migration reaches the intentionally blocked portable ref");}
        check(git(root,"show-ref").equals(legacyRefs)&&Files.readString(root.resolve(".git/HEAD")).equals(legacyHead),"failed portable migration restores already removed legacy ref");
        Files.delete(blocked);engine.apply(engine.preview("case",delta));
        check(git(root,"symbolic-ref","HEAD").equals("refs/heads/vm-work/_46eature"),"current branch uses the same portable mapping");
        check(git(root,"rev-parse","refs/tags/vm-sync/_560.3.12").equals(head)&&git(root,"rev-parse","refs/tags/vm-sync/v0.3.12").equals(head),"legacy uppercase mirror migrates while retaining both source tags");
        check(git(root,"rev-parse","refs/tags/vm-sync/_41").equals(head)&&git(root,"rev-parse","refs/tags/vm-sync/_5f41/foo").equals(head),"legacy parent-child names migrate without ref directory conflict");
        check(git(root,"for-each-ref","--format=%(refname)","refs/tags/vm-sync/").lines().count()==7,"case-folded legacy parent paths migrate without losing a ref");
        sender.acceptReceipt("case",receiver.receipt("case"));
        String beforeRefs=git(root,"show-ref"),beforeHead=Files.readString(root.resolve(".git/HEAD"));
        Files.writeString(vm.resolve(".git/packed-refs"),head+" refs/tags/v0.3.12\n");
        Payload removed=sender.generate("case",true).payload();
        try{engine.apply(engine.preview("case",removed),new SyncEngine.ApplyObserver(){public void afterWrite(int count){}public void afterGitCommit()throws IOException{throw new IOException("rollback case refs");}});throw new AssertionError("expected rollback");}catch(IOException expected){}
        check(git(root,"show-ref").equals(beforeRefs)&&Files.readString(root.resolve(".git/HEAD")).equals(beforeHead),"case-distinct ref deletion rolls back refs and HEAD");
        engine.apply(engine.preview("case",removed));
        check(git(root,"for-each-ref","--format=%(refname)","refs/tags/vm-sync/").equals("refs/tags/vm-sync/v0.3.12"),"delta removes only the deleted case-distinct tag");
    }
}
