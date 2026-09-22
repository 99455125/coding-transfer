package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static dev.codingtransfer.core.Model.*;

/** Integration tests use disposable local repositories, never the user's repositories. */
public final class CoreTest {
    private static int checks;
    private static Path temp;
    private static final String HEAD="0123456789abcdef0123456789abcdef01234567";
    private interface Checked {void run() throws Exception;}
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    private static void fails(String name,String contains,Checked work)throws Exception{
        try{work.run();}catch(IOException e){check(e.getMessage()!=null&&e.getMessage().contains(contains),name+" ["+e.getMessage().split("\n")[0]+"]");return;}throw new AssertionError("Expected failure: "+name);
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static void write(Path root,String path,byte[] data)throws IOException{Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.write(p,data);}
    private static String git(Path root,String... arguments)throws Exception{
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString()));command.addAll(List.of(arguments));
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();if(p.waitFor()!=0)throw new IOException(out);return out;
    }
    private static Config config(Role role,Path repo)throws Exception{
        return Config.parse("role: "+role+"\ngit: git\nprojects:\n  - id: ri-core\n    local: projects/ri-core\n    vm: '"+repo+"'\n  - id: pangu\n    local: projects/pangu\n    vm: '"+repo.resolveSibling("pangu")+"'\nexclude:\n  - .idea/**\n  - target/**\n  - build/**\n  - node_modules/**\n  - '*.log'\nclipboard:\n  chunk-size-kb: 4\n");
    }
    private static Payload payload(Config c,List<Entry> entries){return new Payload(Type.SNAPSHOT,"ri-core",HEAD,c.policy(),entries);}
    private static Entry raw(String path,byte[] b){return new Entry(path,"-",hash(b),Encoding.RAW,b);}
    public static void main(String[] args)throws Exception{
        temp=Files.createTempDirectory("coding-transfer-test-").toRealPath();System.out.println("Test workspace: "+temp);
        try{configuration();patches();codec();transport();integration();recovery();flexibleMappings();System.out.println("PASS ALL "+checks+" checks");BundleTest.run(temp);FileTransferTest.run(temp);RefsTest.run(temp);}
        finally{try(var paths=Files.walk(temp)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static void configuration()throws Exception{
        Config c=config(Role.HOST,temp.resolve("vm"));check(c.projects().size()==2,"multiple project mappings");check(Config.parse(c.yaml()).equals(c),"configuration round trip");
        check(c.excluded("module/target/X.class")&&c.excluded("a/debug.log")&&!c.excluded("src/X.java"),"recursive directory and filename exclusions");
        check(c.excluded(".git/config")&&c.excluded(".vmsync/config.yaml"),"mandatory state/Git exclusions");
        check(Config.parse(c.yaml().replace("projects/ri-core","services/ri-core")).project("ri-core").local().equals("services/ri-core"),"HOST directory name and depth unrestricted");
        fails("unknown setting rejected","不支持",()->Config.parse(c.yaml()+"execute: rm\n"));
        fails("chunk size lower bound","4–4096",()->Config.parse(c.yaml().replace("chunk-size-kb: 4","chunk-size-kb: 1")));
        fails("chunk size upper bound","4–4096",()->Config.parse(c.yaml().replace("chunk-size-kb: 4","chunk-size-kb: 4097")));
        fails("duplicate id rejected","id 重复",()->Config.parse(c.yaml().replace("id: pangu","id: ri-core")));
        fails("unknown project rejected","未登记",()->c.project("not-registered"));
        for(String path:List.of("../secret","/absolute","a/../../b","a\\b","a//b",".git/config","x/.GiT/config",".vmsync/state","nul.txt","com1.java","a:b","a. ","a/./b"))fails("reject path "+path,"路径",()->SafeFiles.relative(path));
        SafeFiles.relative("src/main/java/中文.java");check(true,"portable unicode path accepted");
        Path scan=Files.createDirectories(temp.resolve("scan"));write(scan,"src/X.java",bytes("x"));write(scan,"target/out",bytes("skip"));write(scan,"app.log",bytes("skip"));
        check(SafeFiles.scan(scan,c).keySet().equals(Set.of("src/X.java")),"only managed files scanned");
        Files.createSymbolicLink(scan.resolve("escape"),temp);fails("symlink scan rejected","链接",()->SafeFiles.scan(scan,c));Files.delete(scan.resolve("escape"));
        write(scan,".git/config",bytes("keep metadata"));check(SafeFiles.scan(scan,c).keySet().equals(Set.of("src/X.java")),"HOST root Git metadata excluded");
        write(scan,"child/.git/config",bytes("nested"));fails("nested repository still rejected","Git",()->SafeFiles.scan(scan,c));Files.delete(scan.resolve("child/.git/config"));Files.delete(scan.resolve("child/.git"));Files.delete(scan.resolve("child"));
        Files.delete(scan.resolve(".git/config"));Files.delete(scan.resolve(".git"));
        Files.createSymbolicLink(scan.resolve("linked-state"),temp);fails("symlink write rejected","链接",()->SafeFiles.atomicWrite(scan.resolve("linked-state/x"),bytes("x")));Files.delete(scan.resolve("linked-state"));
    }
    private static void patches()throws Exception{
        List<String[]> cases=List.of(new String[]{"old\n","new\n"},new String[]{"a\r\nb\r\n","a\r\nc\r\n"},new String[]{"a","b"},new String[]{"","new"},new String[]{"before",""},new String[]{"\n","\n\n"},new String[]{"中文\r\n末行","中文\n末行\n"},new String[]{"a\r","b\r"});
        for(int i=0;i<cases.size();i++){byte[] a=bytes(cases.get(i)[0]),b=bytes(cases.get(i)[1]);check(Arrays.equals(b,TextPatch.apply(a,TextPatch.create(a,b))),"exact unified patch case "+i);}
        byte[] patch=TextPatch.create(bytes("a\n"),bytes("b\n"));fails("patch cannot apply to different original","原文",()->TextPatch.apply(bytes("c\n"),patch));
        fails("truncated patch rejected","完整",()->TextPatch.apply(bytes("a\n"),Arrays.copyOf(patch,patch.length-1)));
        fails("invalid hunk counts rejected","行数",()->TextPatch.apply(bytes("a\n"),bytes(new String(patch,StandardCharsets.UTF_8).replace("-1,1","-1,2"))));
        check(TextPatch.text(new byte[]{(byte)0xff})==null&&TextPatch.text(new byte[]{0})==null,"binary detection");
    }
    private static void codec()throws Exception{
        Config c=config(Role.HOST,temp.resolve("vm"));byte[] data={0,1,(byte)255,4};Payload p=payload(c,List.of(raw("binary.bin",data),raw("empty",new byte[0])));
        Payload decoded=Codec.decode(Codec.encode(p));check(decoded.entries().size()==2&&Arrays.equals(decoded.entries().getFirst().data(),data),"binary and empty codec round trip");
        byte[] original=decoded.entries().getFirst().data();original[0]=100;check(decoded.entries().getFirst().data()[0]==0,"defensive content arrays");
        byte[] encoded=Codec.encode(p);encoded[encoded.length-5]^=1;fails("gzip corruption rejected","",()->Codec.decode(encoded));
        fails("bad raw hash rejected","CHECKSUM",()->Codec.encode(payload(c,List.of(new Entry("a","-",hash(bytes("wrong")),Encoding.RAW,bytes("right"))))));
        fails("duplicate path rejected","重复",()->Codec.encode(payload(c,List.of(raw("a",data),raw("a",data)))));
        fails("case collision rejected","大小写",()->Codec.encode(payload(c,List.of(raw("A",data),raw("a",data)))));
        fails("directory casing collision rejected","大小写",()->Codec.encode(payload(c,List.of(raw("Src/A",data),raw("src/B",data)))));
        fails("file/dir collision rejected","文件/目录",()->Codec.encode(payload(c,List.of(raw("a",data),raw("a/b",data)))));
        fails("traversal in payload rejected","路径",()->Codec.encode(payload(c,List.of(raw("../outside",data)))));
        fails("Git file in payload rejected","路径",()->Codec.encode(payload(c,List.of(raw(".git/hooks/post-commit",data)))));
        fails("snapshot deletion entry rejected","SNAPSHOT",()->Codec.encode(payload(c,List.of(new Entry("a",hash(data),"-",Encoding.DELETE,new byte[0])))));
        Codec.validate(payload(c,List.of(raw("big",new byte[16*1024*1024+1]))));check(true,"former 16 MiB file limit removed");
    }
    private static void transport()throws Exception{
        Config c=config(Role.HOST,temp.resolve("vm"));Path workspace=Files.createDirectories(temp.resolve("transport-host"));
        byte[] random=new byte[20_000];new Random(42).nextBytes(random);Payload p=payload(c,List.of(raw("random.bin",random)));
        List<String> parts=ClipboardTransport.split(p,4);check(parts.size()>3,"payload chunked");
        ClipboardTransport.Received first=ClipboardTransport.receive(workspace,c,"ri-core",parts.getLast());check(first.received()==1&&first.payload()==null&&first.firstMissing()==1,"out of order initial part persisted");
        ClipboardTransport.Received dup=ClipboardTransport.receive(workspace,c,"ri-core",parts.getLast().replace("\n","\r\n"));check(dup.received()==1,"identical CRLF duplicate tolerated");
        check(ClipboardTransport.receive(workspace,c,"ri-core",parts.get(2)).firstMissing()==1,"project middle block does not advance first missing");
        check(ClipboardTransport.receive(workspace,c,"ri-core",parts.get(0)).firstMissing()==2,"project missing advances after first block");
        check(ClipboardTransport.receive(workspace,c,"ri-core",parts.get(1)).firstMissing()==4,"project missing skips previously received block");
        fails("wrong selected project rejected","项目不匹配",()->ClipboardTransport.receive(workspace,c,"pangu",parts.getFirst()));
        fails("wrong receiver role rejected","角色",()->ClipboardTransport.receive(workspace,config(Role.VM,temp.resolve("vm")),"ri-core",parts.getFirst()));
        String altered=parts.getFirst().substring(0,parts.getFirst().length()-1)+"!";fails("corrupt chunk rejected","Base64",()->ClipboardTransport.receive(workspace,c,"ri-core",altered));
        String dataChanged=parts.getFirst().substring(0,parts.getFirst().length()-1)+(parts.getFirst().endsWith("A")?"B":"A");fails("chunk checksum rejected","CHECKSUM",()->ClipboardTransport.receive(workspace,c,"ri-core",dataChanged));
        fails("duplicate header rejected","重复",()->ClipboardTransport.receive(workspace,c,"ri-core",parts.getFirst().replace("TYPE:SNAPSHOT","TYPE:SNAPSHOT\nTYPE:SNAPSHOT")));
        fails("unknown header rejected","未知",()->ClipboardTransport.receive(workspace,c,"ri-core",parts.getFirst().replace("TYPE:SNAPSHOT","TYPE:SNAPSHOT\nCOMMAND:anything")));
        ClipboardTransport.Received done=null;for(int i=0;i<parts.size()-1;i++)done=ClipboardTransport.receive(workspace,Config.parse(c.yaml()),"ri-core",parts.get(i));
        check(done!=null&&done.payload()!=null&&done.firstMissing()==0&&Arrays.equals(done.payload().entries().getFirst().data(),random),"restart/reload and unordered reassembly");
        String replacement=ClipboardTransport.split(payload(c,List.of(raw("different",bytes("new")))),4).getFirst();String oldSession=first.session();String newSession=replacement.split("SESSION:")[1].split("\n")[0];
        String mixed=replacement.replace(newSession,oldSession);fails("mixed session blocked","混合",()->ClipboardTransport.receive(workspace,c,"ri-core",mixed));
        ClipboardTransport.clear(workspace,oldSession);check(!Files.exists(workspace.resolve(".vmsync/inbox").resolve(oldSession)),"explicit session cleanup");
        fails("cleanup traversal blocked","会话",()->ClipboardTransport.clear(workspace,"../../outside"));
        String forged=parts.getFirst().replace("HEAD:"+HEAD,"HEAD:"+"f".repeat(40));Path forgedWorkspace=Files.createDirectories(temp.resolve("forged"));
        for(int i=0;i<parts.size()-1;i++)ClipboardTransport.receive(forgedWorkspace,c,"ri-core",parts.get(i).replace("HEAD:"+HEAD,"HEAD:"+"f".repeat(40)));
        fails("header/body mismatch blocked","包体",()->ClipboardTransport.receive(forgedWorkspace,c,"ri-core",parts.getLast().replace("HEAD:"+HEAD,"HEAD:"+"f".repeat(40))));
        Config other=new Config(c.role(),c.projects(),List.of("other/**"),c.chunkKb(),c.git());Path policyWorkspace=Files.createDirectories(temp.resolve("policy"));
        String single=ClipboardTransport.split(payload(c,List.of(raw("a",bytes("x")))),4).getFirst();
        fails("receiver policy mismatch blocked","EXCLUDE",()->ClipboardTransport.receive(policyWorkspace,other,"ri-core",single));
        byte[] large=new byte[4*1024*1024];new Random(7).nextBytes(large);List<String> largeParts=ClipboardTransport.split(payload(c,List.of(raw("large.bin",large))),4096);
        check(largeParts.getFirst().length()>4*1024*1024,"project supports 4096 KiB data blocks plus header");
        ClipboardTransport.Received largeResult=null;for(String block:largeParts)largeResult=ClipboardTransport.receive(policyWorkspace,c,"ri-core",block);
        check(largeResult!=null&&largeResult.firstMissing()==0&&Arrays.equals(largeResult.payload().entries().getFirst().data(),large),"project 4096 KiB chunks round trip");
    }
    private static void integration()throws Exception{
        Path vm=Files.createDirectories(temp.resolve("vm-repo")),host=Files.createDirectories(temp.resolve("host-workspace"));
        git(vm,"init");git(vm,"config","user.name","Transfer Test");git(vm,"config","user.email","transfer@example.invalid");
        write(vm,"src/Main.java",bytes("class Main {\r\n}\r\n"));write(vm,"delete.txt",bytes("remove later\n"));write(vm,"empty",new byte[0]);write(vm,"binary.bin",new byte[]{0,1,2});
        git(vm,"add",".");git(vm,"commit","-m","initial");String initial=git(vm,"rev-parse","HEAD");
        write(vm,"untracked.txt",bytes("dirty snapshot\n"));write(vm,"target/ignored",bytes("build"));
        write(host,"AGENTS.md",bytes("AI only"));write(host,"requirements/001/plan.md",bytes("plan"));write(host,"docs/design.md",bytes("docs"));write(host,"projects/pangu/Other.java",bytes("other project"));
        git(host,"init");git(host,"config","user.name","Host Test");git(host,"config","user.email","host@example.invalid");git(host,"add",".");git(host,"commit","-m","outer history");
        Config hc=config(Role.HOST,vm),vc=config(Role.VM,vm);SyncEngine he=new SyncEngine(host,hc),ve=new SyncEngine(vm,vc);
        fails("HOST requires baseline","NO_BASELINE",()->he.generate("ri-core"));
        SyncEngine.Generated snap=ve.generate("ri-core");check(snap.payload().head().equals(initial)&&snap.payload().entries().size()==5,"VM snapshot includes dirty untracked, excludes target/Git");
        Path mirror=host.resolve("projects/ri-core");SyncEngine.Preview initialPreview=he.preview("ri-core",Codec.decode(Codec.encode(snap.payload())));he.apply(initialPreview);
        check(Arrays.equals(Files.readAllBytes(mirror.resolve("src/Main.java")),Files.readAllBytes(vm.resolve("src/Main.java"))),"initial authoritative snapshot applied");
        check(!Files.exists(mirror.resolve(".git")),"HOST project has no nested Git");
        check(he.generate("ri-core").changes().isEmpty(),"HOST baseline produces zero changes");
        write(mirror,"src/Main.java",bytes("class Main {\r\n  int value = 1;\r\n}\r\n"));write(mirror,"new/中文.java",bytes("// 中文\n"));write(mirror,"binary.bin",new byte[]{0,9,8,7});Files.delete(mirror.resolve("delete.txt"));
        write(host,"AGENTS.md",bytes("changed AI only"));write(host,"projects/pangu/Other.java",bytes("changed other"));
        git(host,"add",".");git(host,"commit","-m","AI outer commit does not become VM BASE");
        SyncEngine.Generated change=he.generate("ri-core");check(change.payload().head().equals(initial)&&change.changes().size()==4,"HOST worktree ignores outer commits and other projects");
        check(change.payload().entries().stream().anyMatch(e->e.encoding()==Encoding.PATCH)&&change.payload().entries().stream().anyMatch(e->e.encoding()==Encoding.DELETE),"worktree contains patch and deletion");
        byte[] indexBefore=Files.readAllBytes(vm.resolve(".git/index"));String headBefore=GitReader.head(vm,"git");
        SyncEngine.Preview preview=ve.preview("ri-core",Codec.decode(Codec.encode(change.payload())));Path backup=ve.apply(preview);
        check(Arrays.equals(Files.readAllBytes(vm.resolve("src/Main.java")),Files.readAllBytes(mirror.resolve("src/Main.java"))),"HOST changes applied preserving exact CRLF");
        check(!Files.exists(vm.resolve("delete.txt"))&&Files.exists(vm.resolve("new/中文.java")),"add/delete applied");
        check(Arrays.equals(indexBefore,Files.readAllBytes(vm.resolve(".git/index")))&&headBefore.equals(GitReader.head(vm,"git")),"VM index and HEAD unchanged");
        check(Files.exists(backup.resolve("before.gz"))&&Files.readString(backup.resolve("status.txt")).equals("APPLIED"),"durable pre/post backup and journal");
        fails("repeated worktree not blindly reapplied","BASE_CONTENT",()->ve.preview("ri-core",change.payload()));
        check(he.generate("ri-core").changes().size()==4,"sending does not advance HOST baseline");
        git(vm,"add",".");git(vm,"commit","-m","VM owns formal commit");
        fails("HEAD mismatch rejected","BASE_MISMATCH",()->ve.preview("ri-core",change.payload()));
        write(mirror,"local-only.txt",bytes("will remove"));write(mirror,"src/Main.java",bytes("conflicting local draft"));write(mirror,"target/keep",bytes("preserve build"));
        SyncEngine.Preview authoritative=he.preview("ri-core",ve.generate("ri-core").payload());he.apply(authoritative);
        check(!Files.exists(mirror.resolve("local-only.txt"))&&Arrays.equals(Files.readAllBytes(vm.resolve("src/Main.java")),Files.readAllBytes(mirror.resolve("src/Main.java"))),"VM snapshot overrides HOST conflicts and removes extras");
        check(Files.readString(mirror.resolve("target/keep")).equals("preserve build"),"excluded local files preserved");
        check(Files.readString(host.resolve("AGENTS.md")).equals("changed AI only")&&Files.readString(host.resolve("projects/pangu/Other.java")).equals("changed other"),"AI root and unrelated project untouched");
        check(he.generate("ri-core").changes().isEmpty(),"new VM snapshot advances baseline");
        write(mirror,"src/Main.java",bytes("host edit\n"));Payload nextChange=he.generate("ri-core").payload();write(vm,"src/Main.java",bytes("parallel VM edit\n"));
        fails("same HEAD dirty VM target protected","BASE_CONTENT",()->ve.preview("ri-core",nextChange));
        he.apply(he.preview("ri-core",ve.generate("ri-core").payload()));
        write(mirror,"src/Main.java",bytes("host edit again\n"));Payload pending=he.generate("ri-core").payload();SyncEngine.Preview stale=ve.preview("ri-core",pending);write(vm,"untracked.txt",bytes("changed since preview"));
        fails("stale preview refused before writes","STALE_PREVIEW",()->ve.apply(stale));
        check(Files.readString(vm.resolve("src/Main.java")).equals("parallel VM edit\n"),"stale rejection makes no target writes");
        git(vm,"add",".");git(vm,"commit","-m","save disposable test work");git(vm,"checkout","--detach",initial);
        he.apply(he.preview("ri-core",ve.generate("ri-core").payload()));check(he.generate("ri-core").payload().head().equals(initial),"VM historical checkout snapshot accepted as authority");
        Payload excluded=payload(hc,List.of(raw("target/blocked",bytes("x"))));fails("excluded payload refused","排除",()->he.preview("ri-core",excluded));
        fails("snapshot cannot be applied on VM","方向",()->ve.preview("ri-core",snap.payload()));
        fails("project mismatch cannot be applied","项目",()->he.preview("pangu",snap.payload()));
        write(mirror,"obstacle",bytes("file"));Payload fileToDir=payload(hc,List.of(raw("obstacle/child",bytes("x"))));fails("file to directory blocked before apply","目录",()->he.preview("ri-core",fileToDir));
        Files.delete(mirror.resolve("obstacle"));Files.createDirectories(mirror.resolve("obstacle"));Payload dirToFile=payload(hc,List.of(raw("obstacle",bytes("x"))));fails("directory to file blocked before apply","目录",()->he.preview("ri-core",dirToFile));
    }
    private static void recovery()throws Exception{
        Path host=Files.createDirectories(temp.resolve("recover-host")),mirror=Files.createDirectories(host.resolve("projects/ri-core"));
        Config c=config(Role.HOST,temp.resolve("recover-vm"));SyncEngine engine=new SyncEngine(host,c);
        Payload first=payload(c,List.of(raw("a.txt",bytes("old a")),raw("b.txt",bytes("old b"))));engine.apply(engine.preview("ri-core",first));
        byte[] baseline=Files.readAllBytes(host.resolve(".vmsync/baselines/ri-core.gz"));
        Payload next=payload(c,List.of(raw("a.txt",bytes("new a")),raw("new.txt",bytes("new file"))));
        SyncEngine.Preview preview=engine.preview("ri-core",next);
        fails("mid-apply failure surfaces backup path","APPLY_FAILED",()->engine.apply(preview,completed->{if(completed==2)throw new IOException("injected failure");}));
        check(Files.readString(mirror.resolve("a.txt")).equals("old a")&&Files.readString(mirror.resolve("b.txt")).equals("old b")&&!Files.exists(mirror.resolve("new.txt")),"rollback restores modifications and deletions");
        check(Arrays.equals(baseline,Files.readAllBytes(host.resolve(".vmsync/baselines/ri-core.gz"))),"failed apply does not advance baseline");
        Payload additions=payload(c,List.of(raw("a.txt",bytes("old a")),raw("b.txt",bytes("old b")),raw("new.txt",bytes("added"))));
        fails("new-file failure rolled back","APPLY_FAILED",()->engine.apply(engine.preview("ri-core",additions),completed->{throw new IOException("after addition");}));
        check(!Files.exists(mirror.resolve("new.txt")),"rollback removes newly created file");
        fails("concurrent modification during rollback protected","人工恢复",()->engine.apply(engine.preview("ri-core",next),completed->{write(mirror,"a.txt",bytes("external edit"));throw new IOException("external changed file");}));
        check(Files.readString(mirror.resolve("a.txt")).equals("external edit"),"rollback never overwrites external edit");
        write(mirror,"a.txt",bytes("old a"));
        Path lock=mirror.resolve(".vmsync/locks/apply.lock");
        try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.WRITE);var held=channel.lock()){
            fails("concurrent apply blocked by project lock","另一个同步",()->engine.apply(engine.preview("ri-core",next)));
        }
        check(Arrays.equals(baseline,Files.readAllBytes(host.resolve(".vmsync/baselines/ri-core.gz"))),"all rejected applies preserve baseline");
        engine.apply(engine.preview("ri-core",next));check(engine.generate("ri-core").changes().isEmpty(),"successful retry after rollback");
        Path exported=temp.resolve("exported-backup");BackupExport.export(host.resolve(".vmsync/baselines/ri-core.gz"),exported);
        check(Files.readString(exported.resolve("a.txt")).equals("new a"),"manual recovery exports verified bytes");
        fails("backup export never overwrites existing directory","禁止覆盖",()->BackupExport.export(host.resolve(".vmsync/baselines/ri-core.gz"),exported));
        Path symlinkWorkspace=Files.createDirectories(temp.resolve("state-symlink"));Files.createSymbolicLink(symlinkWorkspace.resolve(".vmsync"),temp);
        fails("symlink state directory rejected","链接",()->SafeFiles.state(symlinkWorkspace));Files.delete(symlinkWorkspace.resolve(".vmsync"));
        byte[] a=bytes("\n".repeat(50000)),b=bytes("\n".repeat(50001));check(Arrays.equals(TextPatch.apply(a,TextPatch.create(a,b)),b),"many short lines patch round trip without line array");
    }
    private static Config singleHost(Path vm,String local)throws Exception {
        return Config.parse("role: HOST\ngit: git\nprojects:\n  - id: ri-core\n    local: '"+local+"'\n    vm: '"+vm+"'\nexclude:\n  - target/**\nclipboard:\n  chunk-size-kb: 4\n");
    }
    private static void flexibleMappings()throws Exception {
        Path workspace=Files.createDirectories(temp.resolve("flex-workspace")),vm=temp.resolve("flex-vm"),outside=temp.resolve("external code/业务项目");
        Config absolute=singleHost(vm,outside.toString());
        check(absolute.root(workspace,absolute.project("ri-core")).equals(outside),"absolute HOST mapping outside workspace");
        check(Config.parse(absolute.yaml()).equals(absolute),"absolute path configuration round trip");
        Config relative=singleHost(vm,"services/team/ri-core");
        check(relative.root(workspace,relative.project("ri-core")).equals(workspace.resolve("services/team/ri-core")),"arbitrary relative HOST depth");
        Config sibling=singleHost(vm,"../external code/业务项目");
        check(sibling.root(workspace,sibling.project("ri-core")).equals(outside),"trusted sibling mapping supports parent segments");
        Config own=singleHost(vm,".");check(own.root(workspace,own.project("ri-core")).equals(workspace),"opened project itself can be HOST target");
        Config home=singleHost(vm,"~/coding-transfer-test-mapping");
        check(home.root(workspace,home.project("ri-core")).equals(Path.of(System.getProperty("user.home")).resolve("coding-transfer-test-mapping")),"tilde HOST directory expansion");
        Config foreign=Config.parse(absolute.yaml().replace("role: HOST","role: VM"));
        Config otherOS=singleHost(vm,"D:/work/ri-core");
        if(!System.getProperty("os.name").startsWith("Windows"))fails("foreign drive path cannot become relative local folder","当前系统",()->otherOS.root(workspace,otherOS.project("ri-core")));
        fails("mapping cannot target metadata","元数据",()->singleHost(vm,outside+"/.git"));
        fails("payload traversal protection unchanged","路径",()->SafeFiles.resolve(outside,"../outside"));
        Config fsRoot=singleHost(vm,"/");fails("filesystem root mapping refused","文件系统根",()->fsRoot.root(workspace,fsRoot.project("ri-core")));
        Config base=config(Role.HOST,vm);
        Config duplicate=new Config(Role.HOST,List.of(new Config.Mapping("a","module",vm.toString()),new Config.Mapping("b",workspace.resolve("module").toString(),vm.resolveSibling("flex-vm-2").toString())),base.exclude(),4,"git");
        fails("relative and absolute aliases rejected","重复或嵌套",()->duplicate.root(workspace,duplicate.project("a")));
        Config nested=new Config(Role.HOST,List.of(new Config.Mapping("a","module",vm.toString()),new Config.Mapping("b","module/child",vm.resolveSibling("flex-vm-2").toString())),base.exclude(),4,"git");
        fails("overlapping registered HOST roots rejected","重复或嵌套",()->nested.root(workspace,nested.project("a")));
        write(workspace,"AGENTS.md",bytes("root AI document"));write(workspace,"sibling/Untouched.java",bytes("untouched"));
        SyncEngine engine=new SyncEngine(workspace,absolute);Payload first=payload(absolute,List.of(raw("src/Main.java",bytes("baseline\r\n"))));
        engine.apply(engine.preview("ri-core",first));
        check(Files.readString(outside.resolve("src/Main.java")).equals("baseline\r\n"),"snapshot creates and applies to external directory");
        check(!Files.exists(workspace.resolve("projects")),"no forced projects directory created");
        write(outside,"src/Main.java",bytes("new edit\r\n"));Payload work=engine.generate("ri-core").payload();
        check(work.entries().size()==1&&work.entries().getFirst().path().equals("src/Main.java")&&work.type()==Type.WORKTREE,"external directory produces project-relative WORKTREE");
        write(outside,"extra.txt",bytes("delete on snapshot"));write(outside,"target/keep",bytes("excluded"));write(outside,".git/config",bytes("local git metadata"));
        engine.apply(engine.preview("ri-core",first));
        check(!Files.exists(outside.resolve("extra.txt"))&&Files.readString(outside.resolve("target/keep")).equals("excluded"),"external snapshot authoritative replacement respects exclusions");
        check(Files.readString(outside.resolve(".git/config")).equals("local git metadata"),"external HOST root .git preserved");
        check(Files.readString(workspace.resolve("AGENTS.md")).equals("root AI document")&&Files.readString(workspace.resolve("sibling/Untouched.java")).equals("untouched"),"outside mapped root AI/sibling files untouched");
        Path secondWorkspace=Files.createDirectories(temp.resolve("second-IDE-workspace"));SyncEngine second=new SyncEngine(secondWorkspace,absolute);
        try(var channel=java.nio.channels.FileChannel.open(outside.resolve(".vmsync/locks/apply.lock"),StandardOpenOption.WRITE);var held=channel.lock()){
            fails("same target locked across different IDEA workspaces","另一个同步",()->second.apply(second.preview("ri-core",first)));
        }
        write(workspace,"SelectedRoot.java",bytes("old"));SyncEngine self=new SyncEngine(workspace,own);
        // Selecting '.' explicitly opts in to the workspace's own files; metadata remains excluded.
        Payload selfSnapshot=payload(own,List.of(raw("SelectedRoot.java",bytes("new"))));self.apply(self.preview("ri-core",selfSnapshot));
        check(Files.readString(workspace.resolve("SelectedRoot.java")).equals("new")&&Files.exists(workspace.resolve(".vmsync/baselines/ri-core.gz")),"workspace-root mapping preserves runtime state");
    }
}
