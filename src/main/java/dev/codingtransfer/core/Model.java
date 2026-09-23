package dev.codingtransfer.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

public final class Model {
    private Model() {}
    public static final int FILE_COUNT = 10_000;
    public enum Role { HOST, VM }
    public enum Type { WORKTREE, SNAPSHOT, DELTA, ACK }
    public enum Encoding { RAW, PATCH, DELETE }
    public record Entry(String path, String before, String after, Encoding encoding, byte[] data, byte[] base) {
        public Entry {
            data = data.clone();
            base = base == null ? null : base.clone();
        }
        public Entry(String path,String before,String after,Encoding encoding,byte[] data){this(path,before,after,encoding,data,null);}
        @Override public byte[] data() { return data.clone(); }
        @Override public byte[] base() { return base == null ? null : base.clone(); }
    }
    public record GitData(String snapshot, String parent, String branch, SortedMap<String,String> refs, byte[] bundle) {
        public GitData { refs=Collections.unmodifiableSortedMap(new TreeMap<>(refs));bundle=bundle.clone(); }
        @Override public byte[] bundle(){return bundle.clone();}
        public GitData withoutBundle(){return new GitData(snapshot,parent,branch,refs,new byte[0]);}
    }
    public record Payload(Type type, String project, String head, String policy, List<Entry> entries, GitData git, String sourceBranch, int version) {
        public Payload {
            entries = List.copyOf(entries);
            sourceBranch = sourceBranch == null ? "" : sourceBranch;
        }
        public Payload(Type type,String project,String head,String policy,List<Entry> entries){this(type,project,head,policy,entries,null,"",1);}
        public Payload(Type type,String project,String head,String policy,List<Entry> entries,GitData git){this(type,project,head,policy,entries,git,"",git==null?1:2);}
        public static Payload worktree(String project,String head,String policy,String sourceBranch,List<Entry> entries){return new Payload(Type.WORKTREE,project,head,policy,entries,null,sourceBranch,3);}
    }
    public record Change(String path, byte[] before, byte[] after) {
        public Change { before = before == null ? null : before.clone(); after = after == null ? null : after.clone(); }
        @Override public byte[] before() { return before == null ? null : before.clone(); }
        @Override public byte[] after() { return after == null ? null : after.clone(); }
        public String status() { return before == null ? "A" : after == null ? "D" : "M"; }
    }
    public static String hash(byte[] bytes) {
        if (bytes == null) return "-";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static void require(boolean ok, String message) throws IOException { if (!ok) throw new IOException(message); }
    public static boolean hashValue(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    public static boolean headValue(String s) { return s != null && s.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})"); }
    public static void projectId(String s) throws IOException {
        require(s != null && s.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"), "无效 project id");
    }
}
