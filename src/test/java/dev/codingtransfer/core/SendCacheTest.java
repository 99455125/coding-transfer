package dev.codingtransfer.core;

import java.nio.file.*;
import java.util.UUID;

/** Isolated sender cleanup checks; never touches user repositories. */
public final class SendCacheTest {
    public static void main(String[] args)throws Exception {
        Path workspace=Files.createTempDirectory("send-cache-test-").toRealPath();
        Path source=workspace.resolve("source.bin");Files.writeString(source,"source");
        FileTransfer.Outgoing outgoing=FileTransfer.generate(source,4,workspace);
        Path pending=SafeFiles.state(workspace).resolve("bundle-state/pending/project.gz");
        Files.createDirectories(pending.getParent());Files.writeString(pending,"pending");
        Path baseline=SafeFiles.state(workspace).resolve("baselines/project.gz");
        Files.createDirectories(baseline.getParent());Files.writeString(baseline,"baseline");
        SafeFiles.clearOutgoing(workspace,"file-outgoing",outgoing.metadata().session());
        check(!Files.exists(outgoing.snapshot().getParent()),"file snapshot removed");
        check(Files.readString(source).equals("source"),"source preserved");
        String session=UUID.randomUUID().toString();Path dir=SafeFiles.state(workspace).resolve("git-outgoing").resolve(session);
        Files.createDirectories(dir);Files.writeString(dir.resolve("history.bundle"),"bundle");
        SafeFiles.clearOutgoing(workspace,"git-outgoing",session);
        check(!Files.exists(dir),"bundle temporary cache removed");
        check(Files.readString(pending).equals("pending")&&Files.readString(baseline).equals("baseline"),"pending ACK and baseline preserved");
        SafeFiles.clearOutgoing(workspace,"git-outgoing",session);
        Files.createDirectories(dir);Files.writeString(dir.resolve("unexpected"),"keep");
        try{SafeFiles.clearOutgoing(workspace,"git-outgoing",session);throw new AssertionError("unknown file accepted");}catch(java.io.IOException expected){}
        check(Files.exists(dir.resolve("unexpected")),"unknown file preserved");
        try{SafeFiles.clearOutgoing(workspace,"git-outgoing","../baselines");throw new AssertionError("traversal accepted");}catch(java.io.IOException expected){}
        System.out.println("PASS sender cache cleanup checks");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
