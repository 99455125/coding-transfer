package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static dev.codingtransfer.core.Model.*;

/** Intentionally small, fail-closed YAML subset, matching the documented configuration. */
public record Config(Role role, List<Mapping> projects, List<String> exclude, int chunkKb, String git, String identity, int focusDelayMs) {
    public static final int DEFAULT_FOCUS_DELAY_MS=300,MIN_FOCUS_DELAY_MS=50,MAX_FOCUS_DELAY_MS=60000;
    public record Mapping(String id, String local, String vm) {
        @Override public String toString() { return id; }
    }
    public Config(Role role,List<Mapping> projects,List<String> exclude,int chunkKb,String git){this(role,projects,exclude,chunkKb,git,"",DEFAULT_FOCUS_DELAY_MS);}
    public Config(Role role,List<Mapping> projects,List<String> exclude,int chunkKb,String git,String identity){this(role,projects,exclude,chunkKb,git,identity,DEFAULT_FOCUS_DELAY_MS);}
    public Config { projects = List.copyOf(projects); exclude = List.copyOf(exclude); identity=identity==null?"":identity; }
    public static final String TEMPLATE = """
            # 两端项目 id 和 exclude 保持一致；path 只填写本端路径。
            role: HOST
            identity: ''
            git: git
            projects:
              - id: ri-core
                path: shie-ri-core
            exclude:
              - .idea/**
              - target/**
              - build/**
              - node_modules/**
              - "*.log"
            clipboard:
              chunk-size-kb: 128
              focus-delay-ms: 300
            """;
    public static Config parse(String text) throws IOException {
        require(text.length() <= 64 * 1024, "配置过大");
        String role = null, git = "git", identity = "", section = ""; int chunk = 128,focusDelay=DEFAULT_FOCUS_DELAY_MS;
        List<Mapping> mappings = new ArrayList<>(); List<String> exclude = new ArrayList<>();
        Map<String,String> mapping = null; Set<String> top = new HashSet<>(); Set<String> clipboardKeys=new HashSet<>();
        for (String raw : text.split("\\R")) {
            require(!raw.contains("\t"), "YAML 请使用空格缩进");
            String line = raw.strip(); if (line.isEmpty() || line.startsWith("#")) continue;
            if (!raw.startsWith(" ")) {
                if (mapping != null) { mappings.add(mapping(mapping)); mapping = null; }
                int colon = line.indexOf(':'); require(colon > 0, "配置行缺少冒号: " + line);
                String key = line.substring(0, colon), value = scalar(line.substring(colon + 1));
                require(top.add(key), "配置重复: " + key);
                section = key;
                switch (key) {
                    case "role" -> role = value;
                    case "identity" -> identity = value;
                    case "git" -> git = value;
                    case "projects", "exclude", "clipboard" -> require(value.isEmpty(), "列表必须分行: " + key);
                    default -> throw new IOException("不支持的配置项: " + key);
                }
                continue;
            }
            switch (section) {
                case "projects" -> {
                    if (line.startsWith("- ")) {
                        if (mapping != null) mappings.add(mapping(mapping));
                        mapping = new HashMap<>(); line = line.substring(2).strip();
                    }
                    require(mapping != null, "projects 请以 - id: 开始");
                    int pos = line.indexOf(':'); require(pos > 0, "无效项目映射");
                    String key = line.substring(0, pos);
                    require(Set.of("id", "path", "local", "vm").contains(key), "未知映射项: " + key);
                    require(mapping.putIfAbsent(key, scalar(line.substring(pos + 1))) == null, "重复映射项 " + key);
                }
                case "exclude" -> {
                    require(line.startsWith("- "), "exclude 必须为列表");
                    String pattern = scalar(line.substring(2));
                    require(!pattern.isBlank() && pattern.length() <= 256 && !pattern.contains("\\")
                            && !pattern.startsWith("/") && !pattern.contains(".."), "无效排除模式: " + pattern);
                    exclude.add(pattern);
                }
                case "clipboard" -> {
                    int pos=line.indexOf(':');require(pos>0,"无效 clipboard 项");String key=line.substring(0,pos);require(clipboardKeys.add(key),"重复 clipboard 项: "+key);
                    require(Set.of("chunk-size-kb","focus-delay-ms").contains(key),"未知 clipboard 项: "+key);
                    int value;try{value=Integer.parseInt(scalar(line.substring(pos+1)));}catch(NumberFormatException e){throw new IOException(key+" 必须是整数");}
                    if(key.equals("chunk-size-kb"))chunk=value;else focusDelay=value;
                }
                default -> throw new IOException("无效缩进行: " + line);
            }
        }
        if (mapping != null) mappings.add(mapping(mapping));
        require("HOST".equals(role) || "VM".equals(role), "role 必须是 HOST 或 VM");
        require(identity.isEmpty() || identity.matches("[A-Za-z0-9_-]{1,128}"), "identity 格式无效");
        require(!mappings.isEmpty() && mappings.size() <= 100, "请配置 1–100 个项目");
        require(chunk >= 4 && chunk <= TransferLimits.MAX_CHUNK_KB, "chunk-size-kb 范围为 4–1048576");
        require(focusDelay>=MIN_FOCUS_DELAY_MS&&focusDelay<=MAX_FOCUS_DELAY_MS,"focus-delay-ms 范围为 50–60000");
        require(!git.isBlank() && !git.contains("\n"), "Git 可执行文件不能为空");
        List<Mapping> active=new ArrayList<>();
        for(Mapping m:mappings){
            String selected="HOST".equals(role)?m.local():m.vm();
            require(selected!=null,"项目缺少本端路径: "+m.id());
            String path=mappingPath(selected);active.add(new Mapping(m.id(),path,path));
        }
        mappings=active;
        Set<String> ids = new HashSet<>(), locals = new HashSet<>(), vms = new HashSet<>();
        for (Mapping m : mappings) {
            require(ids.add(m.id().toLowerCase(Locale.ROOT)), "项目 id 重复");
            require(locals.add(m.local().toLowerCase(Locale.ROOT)), "HOST 映射重复");
            require(vms.add(m.vm().replace('\\','/').toLowerCase(Locale.ROOT)), "VM 映射重复");
        }
        List<String> vmPaths = new ArrayList<>(vms);
        for (String a : vmPaths) for (String b : vmPaths)
            require(a.equals(b) || !a.startsWith(b + "/"), "VM 项目路径不能嵌套");
        return new Config(Role.valueOf(role), mappings, exclude.stream().distinct().sorted().toList(), chunk, git, identity, focusDelay);
    }
    private static String scalar(String s) throws IOException {
        s = s.strip();
        if (s.startsWith("\"") || s.startsWith("'")) {
            require(s.length() >= 2 && s.charAt(s.length()-1) == s.charAt(0), "引号未闭合");
            s = s.substring(1, s.length()-1);
        }
        return s;
    }
    private static Mapping mapping(Map<String,String> m) throws IOException {
        require(m.containsKey("id"), "每个项目必须配置 id");
        require(!m.containsKey("path")||(!m.containsKey("local")&&!m.containsKey("vm")),"path 不能与旧 local/vm 配置混用");
        projectId(m.get("id"));
        return new Mapping(m.get("id"),m.getOrDefault("path",m.get("local")),m.getOrDefault("path",m.get("vm")));
    }
    // Trusted local mappings are not payload paths: ../ and absolute roots are allowed here only.
    private static String mappingPath(String value)throws IOException {
        String path=value.replace('\\','/');
        require(!path.isBlank() && path.length()<=4096 && path.chars().noneMatch(c->c<32||c==127),"无效映射目录");
        require(!path.matches("[A-Za-z]:[^/].*") && !path.matches("[A-Za-z]:"),"不支持盘符相对路径，请填写绝对路径");
        for(String component:path.split("/"))
            require(!component.equalsIgnoreCase(".git")&&!component.equalsIgnoreCase(".vmsync"),"映射不能指向 .git / .vmsync 元数据目录");
        while(path.length()>1&&path.endsWith("/")&&!path.matches("[A-Za-z]:/"))path=path.substring(0,path.length()-1);
        return path;
    }
    private static boolean absoluteMapping(String path){return path.startsWith("/")||path.matches("[A-Za-z]:/.*");}
    public Mapping project(String id) throws IOException {
        return projects.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow(() -> new IOException("未登记项目: " + id));
    }
    public String policy() { return hash(("v2-hard-excludes:.git,.vmsync,.gitattributes\n" + String.join("\n", exclude)).getBytes(StandardCharsets.UTF_8)); }
    public String yaml() {
        StringBuilder out = new StringBuilder("role: " + role + "\nidentity: '" + identity + "'\ngit: '" + git + "'\nprojects:\n");
        for (Mapping m : projects) out.append("  - id: ").append(m.id()).append("\n    path: '").append(role==Role.HOST?m.local():m.vm()).append("'\n");
        out.append("exclude:\n"); for (String p : exclude) out.append("  - '").append(p).append("'\n");
        return out.append("clipboard:\n  chunk-size-kb: ").append(chunkKb).append("\n  focus-delay-ms: ").append(focusDelayMs).append('\n').toString();
    }
    public boolean excluded(String path) {
        for (String component : path.split("/")) if (component.equalsIgnoreCase(".git") || component.equalsIgnoreCase(".vmsync") || component.equalsIgnoreCase(".gitattributes")) return true;
        for (String glob : exclude) {
            String pat = glob.endsWith("/**") ? glob.substring(0, glob.length()-3) : glob;
            if (matches(pat, path)) return true;
            int slash = path.lastIndexOf('/');
            while (slash >= 0) { if (matches(pat, path.substring(0, slash))) return true; slash = path.lastIndexOf('/', slash - 1); }
        }
        return false;
    }
    private static boolean matches(String glob, String path) {
        StringBuilder regex = new StringBuilder("^");
        if (!glob.contains("/")) regex.append("(?:.*/)?");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') { if (i + 1 < glob.length() && glob.charAt(i+1) == '*') { regex.append(".*"); i++; } else regex.append("[^/]*"); }
            else if (c == '?') regex.append("[^/]"); else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return path.matches(regex.append('$').toString());
    }
    public Path root(Path workspace, Mapping m) throws IOException {
        require(project(m.id()).equals(m), "项目配置已改变");
        Path base = workspace.toRealPath();
        Path selected=resolveMapping(base,m);
        List<Path> roots=new ArrayList<>();
        for(Mapping candidate:projects){
            Path candidateRoot=resolveMapping(base,candidate);
            for(Path previous:roots)require(!candidateRoot.startsWith(previous)&&!previous.startsWith(candidateRoot),"本端项目映射重复或嵌套: "+candidateRoot+" / "+previous);
            roots.add(candidateRoot);
        }
        return selected;
    }
    private Path resolveMapping(Path base,Mapping mapping)throws IOException {
        String configured=mappingPath(role==Role.HOST?mapping.local():mapping.vm());
        if(configured.equals("~")||configured.startsWith("~/"))
            configured=System.getProperty("user.home")+configured.substring(1);
        Path path;
        try{path=Path.of(configured);}catch(InvalidPathException e){throw new IOException("无效映射目录: "+configured,e);}
        if(absoluteMapping(configured))require(path.isAbsolute(),"当前系统不支持该绝对路径: "+configured);
        Path root=(path.isAbsolute()?path:base.resolve(path)).normalize();
        require(root.getParent()!=null,"请指定业务项目目录，不能映射文件系统根目录");
        for(Path component:root)require(!component.toString().equalsIgnoreCase(".git")&&!component.toString().equalsIgnoreCase(".vmsync"),"映射不能指向元数据目录");
        SafeFiles.noLinks(root);return root;
    }
}
