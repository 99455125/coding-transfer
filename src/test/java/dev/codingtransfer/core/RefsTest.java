package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

final class RefsTest {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS REFS "+label);}
    private static String git(Path root,String... args)throws Exception{
        List<String> cmd=new ArrayList<>(List.of("git","-C",root.toString()));cmd.addAll(List.of(args));Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();String result=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();if(p.waitFor()!=0)throw new IOException(result);return result;
    }
    private static boolean row(List<GitHistory.RefChange> rows,String ref,String path,boolean working){return rows.stream().anyMatch(r->r.ref().equals(ref)&&r.path().equals(path)&&r.working()==working);}
    static void run(Path temp)throws Exception{
        Path vm=Files.createDirectory(temp.resolve("refs-vm")),host=Files.createDirectory(temp.resolve("refs-host")),local=host.resolve("project");
        git(vm,"init","-q","-b","main");git(vm,"config","user.name","Test");git(vm,"config","user.email","test@example.invalid");
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
}
