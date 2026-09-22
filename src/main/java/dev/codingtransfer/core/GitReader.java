package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.codingtransfer.core.Model.*;

public final class GitReader {
    private GitReader() {}
    public static String head(Path root, String executable) throws IOException {
        require(Files.isDirectory(root), "VM 仓库不存在: " + root);
        String top = run(root,executable,"rev-parse","--show-toplevel");
        require(Path.of(top).toRealPath().equals(root.toRealPath()), "VM 映射必须指向 Git 仓库根目录");
        String head = run(root,executable,"rev-parse","--verify","HEAD"); require(headValue(head), "VM 仓库必须至少有一次提交"); return head;
    }
    private static String run(Path root,String executable,String... args)throws IOException {
        List<String> cmd = new ArrayList<>(List.of(executable,"--no-optional-locks","-C",root.toString()));cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        // Ignore inherited Git repository/index overrides; never invoke a shell.
        pb.environment().keySet().removeIf(k -> k.startsWith("GIT_"));pb.environment().put("GIT_TERMINAL_PROMPT","0");
        Process process=pb.start();
        ExecutorService reader=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"vmsync-git-output");t.setDaemon(true);return t;});
        Future<byte[]> output=reader.submit(()->{try(InputStream in=process.getInputStream()){return in.readNBytes(64*1024+1);}});
        try {
            if(!process.waitFor(15,TimeUnit.SECONDS))throw new IOException("Git 查询超时");
            byte[] bytes=output.get(2,TimeUnit.SECONDS);require(bytes.length<=64*1024,"Git 输出过大");
            String text=new String(bytes,StandardCharsets.UTF_8).strip();require(process.exitValue()==0,"Git 查询失败: "+text);return text;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Git 查询已中断",e);}
        catch(ExecutionException|TimeoutException e){throw new IOException("无法读取 Git 输出",e);}
        finally{process.destroyForcibly();output.cancel(true);reader.shutdownNow();}
    }
}
