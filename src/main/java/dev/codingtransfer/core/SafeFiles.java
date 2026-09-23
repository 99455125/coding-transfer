package dev.codingtransfer.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

public final class SafeFiles {
    private SafeFiles() {}
    /** Delete only the known temporary file of one locally generated send session. */
    public static void clearOutgoing(Path workspace,String kind,String session)throws IOException {
        require(kind.equals("file-outgoing")||kind.equals("git-outgoing"),"无效发送缓存类型");
        require(session!=null&&session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效发送会话");
        Path dir=state(workspace).resolve(kind).resolve(session);noLinks(dir);if(!Files.exists(dir))return;
        String name=kind.equals("file-outgoing")?"content.bin":"history.bundle";
        List<Path> files;try(var stream=Files.list(dir)){files=stream.toList();}
        for(Path file:files){noLinks(file);require(file.getFileName().toString().equals(name)&&Files.isRegularFile(file),"发送会话含未知文件，拒绝清理");}
        for(Path file:files)Files.delete(file);Files.delete(dir);
    }
    public static void validateNames(Collection<String> names) throws IOException {
        Map<String,String> spelling=new HashMap<>();Set<String> files=new HashSet<>();
        for(String name:names){
            relative(name);require(files.add(name.toLowerCase(Locale.ROOT)),"重复/大小写冲突路径: "+name);
            String prefix="";for(String component:name.split("/")){
                prefix=prefix.isEmpty()?component:prefix+"/"+component;
                String old=spelling.putIfAbsent(prefix.toLowerCase(Locale.ROOT),prefix);
                require(old==null||old.equals(prefix),"目录/文件大小写冲突: "+prefix);
            }
        }
        for(String name:files)for(int i=name.indexOf('/');i>=0;i=name.indexOf('/',i+1))require(!files.contains(name.substring(0,i)),"文件/目录路径冲突");
    }
    public static void relative(String name) throws IOException {
        require(name != null && !name.isEmpty() && name.length() <= 1024 && !name.startsWith("/") && !name.contains("\\"), "非法相对路径: " + name);
        for (String p : name.split("/", -1)) {
            require(!p.isEmpty() && !p.equals(".") && !p.equals("..") && !p.endsWith(".") && !p.endsWith(" ")
                && p.chars().noneMatch(c -> c < 32 || c == 127 || "<>:\"|?*".indexOf(c) >= 0), "非法路径分段: " + name);
            require(!p.equalsIgnoreCase(".git") && !p.equalsIgnoreCase(".vmsync") && !p.toLowerCase(Locale.ROOT).matches("(?:con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\\..*)?"), "保留路径: " + name);
        }
    }
    public static void noLinks(Path path) throws IOException {
        Path abs = path.toAbsolutePath().normalize(), p = abs.getRoot();
        for (Path component : abs) {
            p = p.resolve(component);
            require(!Files.isSymbolicLink(p), "不支持符号链接: " + p);
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!attrs.isOther() && (attrs.isRegularFile() || attrs.isDirectory()), "不支持特殊文件/junction: " + p);
                require(p.toRealPath().equals(p), "路径重定向或大小写不一致: " + p);
            }
        }
    }
    public static Path resolve(Path root, String relative) throws IOException {
        relative(relative); Path p = root.resolve(relative).normalize();
        require(p.startsWith(root) && !p.equals(root), "路径越界"); noLinks(p); return p;
    }
    public static byte[] read(Path file) throws IOException {
        noLinks(file); require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "不是普通文件: " + file);
        return Files.readAllBytes(file);
    }
    public static byte[] read(Path file, int limit) throws IOException {
        noLinks(file); require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), "不是普通文件: " + file);
        require(Files.size(file) <= limit, "文件超过限制: " + file);
        try (InputStream in = Files.newInputStream(file)) { byte[] data = in.readNBytes(limit + 1); require(data.length <= limit, "文件超过限制: " + file); return data; }
    }
    public static SortedMap<String,byte[]> scan(Path root, Config config) throws IOException {
        noLinks(root); SortedMap<String,byte[]> result = new TreeMap<>();
        if (!Files.exists(root)) { require(config.role() == Role.HOST, "VM 项目不存在: " + root); return result; }
        require(Files.isDirectory(root), "项目路径不是目录");
        Set<String> folded = new HashSet<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private String name(Path p) { return root.relativize(p).toString().replace('\\','/'); }
            private boolean skip(Path p) throws IOException {
                if (p.equals(root)) return false;
                String n = name(p);
                if (p.getFileName().toString().equalsIgnoreCase(".git")) {
                    require(p.getParent().equals(root), "禁止嵌套 Git: " + p); return true;
                }
                if (config.excluded(n)) return true;
                relative(n); noLinks(p);
                require(folded.add(n.toLowerCase(Locale.ROOT)), "大小写路径冲突: " + n); return false;
            }
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return skip(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (!skip(f)) {
                    require(attrs.isRegularFile(), "不支持链接/特殊文件: " + f);
                    byte[] bytes = read(f);
                    require(result.size() < FILE_COUNT, "项目超过 10000 文件限制");
                    result.put(name(f), bytes);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }
    public static void atomicWrite(Path path, byte[] data) throws IOException {
        noLinks(path); noLinks(path.getParent()); Files.createDirectories(path.getParent()); noLinks(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), ".vmsync-", ".tmp");
        try {
            Files.write(tmp, data);
            if (Files.exists(path) && Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class))
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(path));
            noLinks(path);
            try { Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
    public static Path state(Path workspace) throws IOException {
        Path p = workspace.toRealPath().resolve(".vmsync"); noLinks(p); Files.createDirectories(p);
        Path ignore=p.resolve(".gitignore");noLinks(ignore);
        String rules=Files.exists(ignore)?new String(read(ignore,64*1024),java.nio.charset.StandardCharsets.UTF_8):"";
        String updated=rules;
        for(String rule:List.of("baselines/","inbox/","file-inbox/","file-outgoing/","backups/","locks/","bundle-state/","git-staging/","git-outgoing/","merge-staging/","disabled-hooks/","*.tmp"))
            if(!Arrays.asList(updated.split("\n")).contains(rule))updated+=(updated.isEmpty()||updated.endsWith("\n")?"":"\n")+rule+"\n";
        if(!updated.equals(rules))atomicWrite(ignore,updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return p;
    }
    public static void same(Map<String,byte[]> expected, Map<String,byte[]> actual) throws IOException {
        require(expected.keySet().equals(actual.keySet()), "STALE_PREVIEW: 文件集合已改变，请重新预览");
        for (String p : expected.keySet()) require(Arrays.equals(expected.get(p), actual.get(p)), "STALE_PREVIEW: 文件已改变: " + p);
    }
}
