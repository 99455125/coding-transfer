package dev.codingtransfer.plugin;

import dev.codingtransfer.core.SafeFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded per-session transport event log. It never receives frame bodies or exception messages. */
final class TransferEventLog {
    private static final long MAX_BYTES = 256 * 1024;
    private static final int MAX_SESSIONS = 20;
    private static final DateTimeFormatter WALL = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final Object IO_LOCK = new Object();
    private static final Set<String> ACTIVE_SESSIONS = ConcurrentHashMap.newKeySet();
    private final Path file;
    private final String endpoint;
    private long startedNanos = System.nanoTime();
    private final String pluginVersion = pluginVersion();
    private final String ideaBuild = ideaBuild();

    private TransferEventLog(Path file, String endpoint) { this.file = file; this.endpoint = endpoint; }

    static TransferEventLog open(Path workspace, String sessionId, String endpoint) {
        if (!validSessionId(sessionId)) return null;
        synchronized (IO_LOCK) { try {
            Path directory = logsDirectory(workspace,true);
            TransferEventLog log = new TransferEventLog(directory.resolve(sessionId + ".log"), endpoint);
            ACTIVE_SESSIONS.add(sessionId);
            return log;
        } catch (IOException | RuntimeException ignored) { return null; } }
    }

    synchronized void event(String type, String detail) { append(type, detail, null); }

    synchronized void error(String type, Throwable error) { append(type, "", error); }

    synchronized void resetAfterClear() { startedNanos = System.nanoTime(); }

    synchronized void close() { ACTIVE_SESSIONS.remove(file.getFileName().toString().replaceFirst("\\.log$", "")); }

    record ClearResult(int deletedFiles, int skippedFiles) {}

    static ClearResult clear(Path workspace) throws IOException {
        synchronized (IO_LOCK) {
            Path directory = logsDirectory(workspace,false);
            if(directory==null)return new ClearResult(0,0);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new ClearResult(0, 0);
            SafeFiles.noLinks(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("传输日志路径不是普通目录");
            int deleted = 0, skipped = 0;
            try (var entries = Files.list(directory)) {
                for (Path entry : entries.toList()) {
                    String name = entry.getFileName().toString();
                    String sessionId = name.endsWith(".log.1") ? name.substring(0, name.length() - 6)
                            : name.endsWith(".log") ? name.substring(0, name.length() - 4) : null;
                    if (!validSessionId(sessionId) || Files.isSymbolicLink(entry)
                            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) { skipped++; continue; }
                    SafeFiles.noLinks(entry);
                    if (Files.deleteIfExists(entry)) deleted++;
                }
            }
            return new ClearResult(deleted, skipped);
        }
    }

    private static Path logsDirectory(Path workspace,boolean create) throws IOException {
        if(workspace==null)throw new IOException("项目路径不可用");
        Path root=workspace.toRealPath(),state=root.resolve(".vmsync");
        SafeFiles.noLinks(state);
        if(!Files.exists(state,LinkOption.NOFOLLOW_LINKS)){
            if(!create)return null;
            Files.createDirectories(state);SafeFiles.noLinks(state);
        }
        if(!Files.isDirectory(state,LinkOption.NOFOLLOW_LINKS))throw new IOException("Every Sync 状态路径不是普通目录");
        Path directory=state.resolve("transfer-logs");SafeFiles.noLinks(directory);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)){
            if(!create)return null;
            Files.createDirectories(directory);SafeFiles.noLinks(directory);
        }
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("传输日志路径不是普通目录");
        return directory;
    }

    private void append(String type, String detail, Throwable error) {
        synchronized (IO_LOCK) { try {
            SafeFiles.noLinks(file);SafeFiles.noLinks(file.getParent());
            if (Files.exists(file) && Files.size(file) >= MAX_BYTES) {
                Path previous=file.resolveSibling(file.getFileName()+".1");SafeFiles.noLinks(previous);Files.deleteIfExists(previous);Files.move(file,previous);
            }
            StringBuilder line=new StringBuilder(WALL.format(OffsetDateTime.now())).append(" elapsed_ms=")
                    .append((System.nanoTime()-startedNanos)/1_000_000L).append(" endpoint=").append(endpoint)
                    .append(" plugin_version=").append(pluginVersion).append(" idea_build=").append(ideaBuild).append(" event=").append(type);
            if(detail!=null&&!detail.isBlank())line.append(' ').append(detail);
            if(error!=null)appendSafeThrowableChain(line,error);
            line.append('\n');
            Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            if (!pruned) { prune(file.getParent()); pruned = true; }
        } catch (IOException | RuntimeException ignored) { /* Diagnostics must never block a transfer. */ } }
    }

    private boolean pruned;

    private static void appendSafeThrowableChain(StringBuilder line,Throwable error){
        final int maxCauses=5,maxFramesPerThrowable=24;
        Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        Throwable current=error;int depth=0;
        while(current!=null&&depth<maxCauses&&seen.add(current)){
            if(depth==0)line.append(" exception=").append(current.getClass().getName());
            else line.append("\n  cause[").append(depth).append("]=").append(current.getClass().getName());
            StackTraceElement[] frames=current.getStackTrace();int count=Math.min(frames.length,maxFramesPerThrowable);
            for(int i=0;i<count;i++){StackTraceElement frame=frames[i];line.append("\n  at ").append(frame.getClassName()).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());}
            if(frames.length>count)line.append("\n  frames_truncated=").append(frames.length-count);
            current=current.getCause();depth++;
        }
        if(current!=null)line.append("\n  causes_truncated=true");
    }

    private void prune(Path directory) throws IOException {
        try(var files=Files.list(directory)){
            List<Path> logs=files.filter(path->{String name=path.getFileName().toString();return name.endsWith(".log")&&validSessionId(name.substring(0,name.length()-4))&&Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS);})
                    .sorted(Comparator.comparingLong(TransferEventLog::modified).reversed()).toList();
            List<Path> inactive=logs.stream().filter(path->!ACTIVE_SESSIONS.contains(path.getFileName().toString().substring(0,path.getFileName().toString().length()-4))).toList();
            int keepInactive=Math.max(0,MAX_SESSIONS-(logs.size()-inactive.size()));
            for(int i=keepInactive;i<inactive.size();i++){Path base=inactive.get(i),rotated=base.resolveSibling(base.getFileName()+".1");SafeFiles.noLinks(base);Files.deleteIfExists(base);if(Files.exists(rotated,LinkOption.NOFOLLOW_LINKS)&&Files.isRegularFile(rotated,LinkOption.NOFOLLOW_LINKS)){SafeFiles.noLinks(rotated);Files.deleteIfExists(rotated);}}
        }
    }
    private static boolean validSessionId(String value){if(value==null)return false;try{return UUID.fromString(value).toString().equals(value);}catch(IllegalArgumentException e){return false;}}
    private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(IOException e){return 0;}}
    private static String pluginVersion(){try{var descriptor=com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.shie.tools.git-change-export"));return descriptor==null?"unknown":descriptor.getVersion();}catch(RuntimeException e){return "unknown";}}
    private static String ideaBuild(){try{return com.intellij.ide.plugins.PluginManagerCore.getBuildNumber().asString();}catch(RuntimeException e){return "unknown";}}
}
