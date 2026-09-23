package dev.codingtransfer.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

final class FileTransferTest {
    private static int checks;
    private interface Checked{void run()throws Exception;}
    private static void check(boolean ok,String name){TestSupport.check(ok,name);checks++;System.out.println("PASS FILE "+name);}
    private static void fails(String name,Checked action)throws Exception{try{action.run();}catch(IOException e){check(true,name);return;}throw new AssertionError("Expected failure: "+name);}
    static void run(Path temp)throws Exception{
        Path sender=Files.createDirectory(temp.resolve("file-sender")),receiver=Files.createDirectory(temp.resolve("file-receiver"));
        Path file=sender.resolve("中文 plugin 二进制.jar");byte[] data=new byte[24000];new Random(93).nextBytes(data);Files.write(file,data);
        FileTransfer.Outgoing sent=FileTransfer.generate(file,4);check(sent.chunks().size()>1,"single binary file chunked");
        check(sent.chunks().getFirst().contains("FILENAME:中文 plugin 二进制.jar\n"),"filename carried in every header");
        check(!sent.chunks().getFirst().contains(sender.toString()),"sender directory never transmitted");
        check(sent.metadata().size()==data.length&&sent.metadata().sha256().equals(hash(data)),"raw file size and SHA-256");
        FileTransfer.Received received=FileTransfer.receive(receiver,sent.chunks().getLast());check(!received.complete()&&received.received()==1&&received.firstMissing()==1,"out of order block persisted");
        received=FileTransfer.receive(receiver,sent.chunks().getLast().replace("\n","\r\n"));check(received.received()==1,"duplicate CRLF block idempotent");
        FileTransfer.Received partial=received;fails("partial cannot be saved",()->FileTransfer.save(partial,FileTransfer.destination(receiver.resolve("partial.bin"))));
        for(String block:sent.chunks())received=FileTransfer.receive(receiver,block);
        check(received.complete()&&received.firstMissing()==0&&Arrays.equals(Files.readAllBytes(received.verifiedFile()),data),"binary round trip including non-UTF8 bytes");
        check(!Files.exists(receiver.resolve(sent.metadata().filename())),"completion never writes a default target automatically");
        Path target=receiver.resolve("用户指定的新名字.bin");FileTransfer.save(received,FileTransfer.destination(target));check(Arrays.equals(Files.readAllBytes(target),data),"receiver chooses directory and different filename");
        FileTransfer.Received restored=FileTransfer.receive(receiver,sent.chunks().getFirst());check(restored.complete(),"recreate receiver from persisted chunks");
        Path existing=receiver.resolve("existing.zip");Files.writeString(existing,"old");FileTransfer.Destination confirmed=FileTransfer.destination(existing);check(!confirmed.previousHash().equals("-"),"existing file requires explicit overwrite decision");
        FileTransfer.save(restored,confirmed);check(Arrays.equals(Files.readAllBytes(existing),data),"confirmed existing file replaced exactly");
        FileTransfer.Destination stale=FileTransfer.destination(existing);Files.writeString(existing,"external edit");fails("changed overwrite target rejected",()->FileTransfer.save(restored,stale));check(Files.readString(existing).equals("external edit"),"concurrent target edit retained");
        Path appeared=receiver.resolve("appeared");FileTransfer.Destination absent=FileTransfer.destination(appeared);Files.writeString(appeared,"new owner");fails("newly appeared target not overwritten",()->FileTransfer.save(restored,absent));
        fails("directory target rejected",()->FileTransfer.destination(receiver));
        Path link=receiver.resolve("linked");Files.createSymbolicLink(link,existing);fails("symlink target rejected",()->FileTransfer.destination(link));
        fails("session cache cannot be save target",()->FileTransfer.save(restored,FileTransfer.destination(restored.verifiedFile())));
        String first=sent.chunks().getFirst();fails("project protocol rejected",()->FileTransfer.receive(receiver,first.replace("VMSYNC-FILE/1","VMSYNC/2")));
        fails("filename traversal rejected",()->FileTransfer.receive(receiver,first.replace("FILENAME:中文 plugin 二进制.jar","FILENAME:../escape")));
        fails("absolute filename rejected",()->FileTransfer.receive(receiver,first.replace("FILENAME:中文 plugin 二进制.jar","FILENAME:/escape")));
        fails("backslash filename rejected",()->FileTransfer.receive(receiver,first.replace("FILENAME:中文 plugin 二进制.jar","FILENAME:C:\\escape")));
        fails("duplicate header rejected",()->FileTransfer.receive(receiver,first.replace("SIZE:","SIZE:24000\nSIZE:")));
        fails("unknown destination header rejected",()->FileTransfer.receive(receiver,first.replace("SIZE:","TARGET:/tmp/escape\nSIZE:")));
        fails("mixed filename session rejected",()->FileTransfer.receive(receiver,first.replace("FILENAME:中文 plugin 二进制.jar","FILENAME:other.bin")));
        fails("inconsistent size and part count rejected",()->FileTransfer.receive(receiver,first.replace("SIZE:24000","SIZE:67108865")));
        fails("wrong part count rejected",()->FileTransfer.receive(receiver,first.replace("PARTS:8","PARTS:9")));
        int payload=first.indexOf("\nDATA:\n")+7;String corrupt=first.substring(0,payload)+(first.charAt(payload)=='A'?"B":"A")+first.substring(payload+1);
        fails("block checksum rejected",()->FileTransfer.receive(receiver,corrupt));
        fails("truncated block rejected",()->FileTransfer.receive(receiver,first.substring(0,first.length()-4)));
        Path badReceiver=Files.createDirectory(temp.resolve("file-bad-receiver"));
        fails("whole file checksum rejected",()->{for(String block:sent.chunks())FileTransfer.receive(badReceiver,block.replace("SHA256:"+sent.metadata().sha256(),"SHA256:"+"0".repeat(64)));});
        Files.write(restored.verifiedFile(),new byte[]{1});fails("verified cache rechecked before saving",()->FileTransfer.save(restored,FileTransfer.destination(receiver.resolve("bad.bin"))));
        FileTransfer.clear(receiver,sent.metadata().session());check(!Files.exists(restored.verifiedFile().getParent())&&Files.exists(target),"clear removes cache only not saved file");
        fails("clear traversal rejected",()->FileTransfer.clear(receiver,"../"));
        Path empty=sender.resolve("empty.bin");Files.write(empty,new byte[0]);FileTransfer.Outgoing zero=FileTransfer.generate(empty,4);check(zero.chunks().size()==1,"empty file has one block");
        FileTransfer.Received emptyResult=FileTransfer.receive(receiver,zero.chunks().getFirst());FileTransfer.save(emptyResult,FileTransfer.destination(receiver.resolve("empty-output")));check(Files.size(receiver.resolve("empty-output"))==0,"empty file saved exactly");
        fails("directory source rejected",()->FileTransfer.generate(sender,4));fails("chunk lower limit",()->FileTransfer.generate(file,1));fails("chunk upper limit",()->FileTransfer.generate(file,4097));
        check(Files.readString(receiver.resolve(".vmsync/.gitignore")).contains("file-inbox/"),"file inbox ignored by outer Git");
        check(!Files.exists(receiver.resolve(".vmsync/config.yaml")),"no role or project configuration required");
        System.out.println("PASS ALL "+checks+" File checks");
    }
    static void runLarge(Path temp)throws Exception{
        Path sender=Files.createDirectory(temp.resolve("large-sender")),receiver=Files.createDirectory(temp.resolve("large-receiver"));
        Path big=sender.resolve("oversize.bin");try(RandomAccessFile out=new RandomAccessFile(big.toFile(),"rw")){out.setLength(65L*1024*1024+1);}
        FileTransfer.Outgoing large=FileTransfer.generate(big,4096);check(large.metadata().size()==65L*1024*1024+1,"former 64 MiB cap removed, disk-backed 4096 KiB chunks");
        FileTransfer.Received largeResult=FileTransfer.receive(receiver,large.chunks().get(2));check(largeResult.firstMissing()==1,"middle-first file chunk points to block 1");
        largeResult=FileTransfer.receive(receiver,large.chunks().get(0));check(largeResult.firstMissing()==2,"missing file chunk advances to block 2");
        largeResult=FileTransfer.receive(receiver,large.chunks().get(1));check(largeResult.firstMissing()==4,"already pasted later file chunk skipped");
        for(String block:large.chunks())largeResult=FileTransfer.receive(receiver,block);
        check(largeResult.complete()&&largeResult.firstMissing()==0,"large file all chunks completed");
        Path bigTarget=receiver.resolve("large-output.bin");FileTransfer.save(largeResult,FileTransfer.destination(bigTarget));check(Files.size(bigTarget)==large.metadata().size(),"large file streamed to chosen target");
        System.out.println("PASS ALL large file checks");
    }
}
