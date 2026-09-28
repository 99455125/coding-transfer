package dev.codingtransfer.plugin;

import dev.codingtransfer.core.Config;
import dev.codingtransfer.core.SafeFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Focused file-page config loading checks; no IDEA UI or system clipboard is used. */
public final class FileTransferConfigTest {
    private FileTransferConfigTest() {}
    public static void main(String[] args)throws Exception {
        missingConfigIsAFirstUseCaseAndSettingsDefaultsAreNotPersisted();
        validConfigStillLoads();
        configuredChunkSizeControlsGeneratedFile();
        invalidConfigKindsRemainDistinct();
        System.out.println("PASS file transfer config checks");
    }
    private static void missingConfigIsAFirstUseCaseAndSettingsDefaultsAreNotPersisted()throws Exception {
        Path workspace=Files.createTempDirectory("file-transfer-config-missing-");
        try{
            Path file=workspace.resolve(".vmsync/config.yaml");
            expect(FileTransferConfig.MissingConfigException.class,()->FileTransferConfig.read(workspace),"missing config has a dedicated exception");
            Config defaults=FileTransferConfig.forSettings(workspace);
            check(defaults.identity().isEmpty(),"first-use settings start without a pairing identity or key");
            FileTransferConfig.saveIfConfirmed(workspace,defaults,false);
            check(!Files.exists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!Files.exists(workspace.resolve(".vmsync")),"opening settings and canceling writes no state or config files");
            java.util.concurrent.atomic.AtomicReference<String> outgoing=new java.util.concurrent.atomic.AtomicReference<>("generated-file-snapshot");
            int[] clipboardWrites={0};
            expect(FileTransferConfig.MissingConfigException.class,()->FileTransferConfig.beforeTransferStart(workspace,config->{outgoing.set(null);clipboardWrites[0]++;return config;}),"transfer start is rejected before its configured action");
            check("generated-file-snapshot".equals(outgoing.get())&&clipboardWrites[0]==0,"missing config preserves generated snapshot and never reaches clipboard publication");
        }finally{deleteTree(workspace);}
    }
    private static void configuredChunkSizeControlsGeneratedFile()throws Exception {
        Path workspace=Files.createTempDirectory("file-chunk-config-").toRealPath();
        try{
            Path source=workspace.resolve("sample.bin");Files.write(source,new byte[300_000]);
            FileTransferConfig.saveIfConfirmed(workspace,Config.parse(Config.TEMPLATE),true);
            Config original=FileTransferConfig.read(workspace);
            FileTransferConfig.saveChunkSize(workspace,256);
            var first=FileTransferConfig.generate(workspace,source);
            check(first.metadata().chunkBytes()==196608&&first.metadata().count()==2,"file generation uses configured 256 KiB instead of legacy 128");
            Config changed=FileTransferConfig.read(workspace);
            check(changed.role()==original.role()&&changed.projects().equals(original.projects())&&changed.exclude().equals(original.exclude())&&changed.identity().equals(original.identity())&&changed.git().equals(original.git()),"chunk setting preserves other settings");
            FileTransferConfig.saveChunkSize(workspace,512);
            var second=FileTransferConfig.generate(workspace,source);
            check(second.metadata().count()==1&&first.metadata().count()==2,"next generation reloads settings without changing existing transfer snapshot");
            expect(IOException.class,()->FileTransferConfig.saveChunkSize(workspace,1),"invalid chunk size rejected");
            check(FileTransferConfig.read(workspace).chunkKb()==512,"invalid input leaves saved setting intact");
        }finally{deleteTree(workspace);}
    }

    private static void validConfigStillLoads()throws Exception {
        Path workspace=Files.createTempDirectory("file-transfer-config-valid-");
        try{
            Path file=SafeFiles.state(workspace).resolve("config.yaml");Files.writeString(file,Config.TEMPLATE);
            Config config=FileTransferConfig.read(workspace);
            check(config.identity().isEmpty()&&config.projects().size()==1&&config.chunkKb()==128,"existing config fields remain compatible");
        }finally{deleteTree(workspace);}
    }
    private static void invalidConfigKindsRemainDistinct()throws Exception {
        Path workspace=Files.createTempDirectory("file-transfer-config-invalid-");
        try{
            Path file=SafeFiles.state(workspace).resolve("config.yaml");Files.writeString(file,"not a valid config\n");
            String corruptContents=Files.readString(file);
            IOException corrupt=expect(IOException.class,()->FileTransferConfig.forSettings(workspace),"corrupt config blocks opening defaults");
            check(corrupt.getMessage().contains("配置损坏")&&Files.readString(file).equals(corruptContents),"corrupt config has a distinct diagnostic and is not replaced");
            Files.delete(file);Files.createDirectory(file);
            IOException directory=expect(IOException.class,()->FileTransferConfig.forSettings(workspace),"config directory blocks opening defaults");
            check(directory.getMessage().contains("不是普通文件")&&Files.isDirectory(file),"directory config has a distinct diagnostic and is not replaced");
            Files.delete(file);Path target=workspace.resolve("target.yaml");Files.writeString(target,Config.TEMPLATE);Files.createSymbolicLink(file,target);
            IOException link=expect(IOException.class,()->FileTransferConfig.forSettings(workspace),"config link blocks opening defaults");
            check(link.getMessage().contains("符号链接")&&Files.isSymbolicLink(file)&&Files.readString(target).equals(Config.TEMPLATE),"config link keeps SafeFiles protection and is not replaced");
            check(FileTransferConfig.MISSING_PAIRING_PROMPT.equals(FileTransferConfig.startFailureMessage(new FileTransferConfig.MissingConfigException(file))),"missing config transfer prompt is precise");
            check(FileTransferConfig.missingPairingKey().getMessage().contains("尚未保存配对密钥"),"missing PasswordSafe key has a distinct diagnostic");
        }finally{deleteTree(workspace);}
    }
    private static IOException expect(Class<? extends IOException> type,Throwing action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(IOException failure){if(!type.isInstance(failure))throw failure;return failure;}}
    private static void deleteTree(Path root)throws Exception{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    @FunctionalInterface private interface Throwing{void run()throws Exception;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
