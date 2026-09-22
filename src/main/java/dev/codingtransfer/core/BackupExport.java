package dev.codingtransfer.core;

import java.nio.file.*;
import static dev.codingtransfer.core.Model.*;

/** Manual crash-recovery helper: exports a backup into a NEW directory, never applies to a repository. */
public final class BackupExport {
    private BackupExport() {}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: java -cp coding-transfer.jar dev.codingtransfer.core.BackupExport before.gz NEW_DIRECTORY");
        Path input=Path.of(args[0]).toAbsolutePath().normalize(),output=Path.of(args[1]).toAbsolutePath().normalize();
        export(input,output);System.out.println("Backup exported to: "+output+". Compare changes.tsv before restoring files manually.");
    }
    public static void export(Path input,Path output)throws Exception{
        Payload payload=Codec.decode(SafeFiles.read(input));require(payload.type()==Type.SNAPSHOT,"只接受快照备份");
        SafeFiles.noLinks(output);require(!Files.exists(output,LinkOption.NOFOLLOW_LINKS),"输出目录必须尚不存在，禁止覆盖现有目录");
        require(output.getParent()!=null&&Files.isDirectory(output.getParent()),"输出目录的父目录必须存在");
        Files.createDirectory(output);
        for(Entry entry:payload.entries())SafeFiles.atomicWrite(SafeFiles.resolve(output,entry.path()),entry.data());
    }
}
