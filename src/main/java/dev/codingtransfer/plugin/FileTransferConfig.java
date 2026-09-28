package dev.codingtransfer.plugin;

import dev.codingtransfer.core.Config;
import dev.codingtransfer.core.SafeFiles;
import dev.codingtransfer.core.Model.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** The single safe config read path used by the file-transfer page. */
final class FileTransferConfig {
    static final String MISSING_PAIRING_PROMPT="当前IDEA项目尚未配置配对，请通过更多→配对设置导入并保存";
    private FileTransferConfig() {}

    static Config read(Path workspace)throws IOException {
        Path file=readPath(workspace);
        SafeFiles.noLinks(file);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))throw new MissingConfigException(file);
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Every Sync 配置路径不是普通文件: "+file);
        byte[] bytes=SafeFiles.read(file,64*1024);
        try{return Config.parse(new String(bytes,StandardCharsets.UTF_8));}
        catch(IOException corrupt){throw new IOException("Every Sync 配置损坏: "+corrupt.getMessage(),corrupt);}
    }

    static dev.codingtransfer.core.FileTransfer.Outgoing generate(Path workspace,Path source)throws IOException {
        return dev.codingtransfer.core.FileTransfer.generate(source,forSettings(workspace).chunkKb(),workspace);
    }
    static void saveChunkSize(Path workspace,int chunkKb)throws IOException {
        Config current=forSettings(workspace);
        Config updated=new Config(current.role(),current.projects(),current.exclude(),chunkKb,current.git(),current.identity());
        saveIfConfirmed(workspace,Config.parse(updated.yaml()),true);
    }

    /** First-use defaults are shown in the existing settings form and remain in memory until Save. */
    static Config forSettings(Path workspace)throws IOException {
        try{return read(workspace);}catch(MissingConfigException missing){return Config.parse(Config.TEMPLATE);}
    }
    static void saveIfConfirmed(Path workspace,Config config,boolean confirmed)throws IOException {
        if(!confirmed)return;
        if(config==null)throw new IOException("设置结果不可用");
        SafeFiles.atomicWrite(configPathForSave(workspace),config.yaml().getBytes(StandardCharsets.UTF_8));
    }
    static <T>T beforeTransferStart(Path workspace,ConfiguredAction<T> action)throws Exception {
        Config config=read(workspace);
        return action.run(config);
    }
    @FunctionalInterface interface ConfiguredAction<T>{T run(Config config)throws Exception;}
    static String startFailureMessage(Throwable failure){return failure instanceof MissingConfigException?MISSING_PAIRING_PROMPT:null;}
    static IOException missingPairingKey(){return new IOException("当前IDEA项目尚未保存配对密钥，请通过更多→配对设置导入并保存");}
    private static Path readPath(Path workspace)throws IOException {
        Path root=workspace.toRealPath(),state=root.resolve(".vmsync");SafeFiles.noLinks(state);
        if(Files.exists(state,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(state,LinkOption.NOFOLLOW_LINKS))throw new IOException("Every Sync 状态路径不是目录: "+state);
        return state.resolve("config.yaml");
    }
    private static Path configPathForSave(Path workspace)throws IOException{return SafeFiles.state(workspace).resolve("config.yaml");}

    static final class MissingConfigException extends IOException {
        private final Path path;
        MissingConfigException(Path path){super("Every Sync 配置尚未创建: "+path);this.path=path;}
        Path path(){return path;}
    }
}
