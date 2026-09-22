package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** Unified diff with one exact full-file hunk. No fuzzy offsets, shell or external patch command. */
public final class TextPatch {
    private TextPatch() {}
    public static String text(byte[] bytes) {
        if (bytes == null) return "";
        try {
            String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return s.indexOf('\0') >= 0 ? null : s;
        } catch (CharacterCodingException e) { return null; }
    }
    private static int count(String s) {
        int count = 0; for (int i=0;i<s.length();i++) if (s.charAt(i)=='\n') count++;
        return count + (!s.isEmpty() && !s.endsWith("\n") ? 1 : 0);
    }
    public static byte[] create(byte[] before, byte[] after) throws IOException {
        String a = text(before), b = text(after); require(a != null && b != null, "PATCH 仅支持 UTF-8 文本");
        int old=count(a), next=count(b);
        StringBuilder s = new StringBuilder("--- baseline\n+++ working\n@@ -" + (old==0?0:1) + "," + old
            + " +" + (next==0?0:1) + "," + next + " @@\n");
        append(s, '-', a); append(s, '+', b);
        return s.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void append(StringBuilder s, char prefix, String text) {
        int start=0;
        while(start<text.length()) {
            int end=text.indexOf('\n',start);
            if(end<0){s.append(prefix).append(text,start,text.length()).append("\n\\ No newline at end of file\n");break;}
            s.append(prefix).append(text,start,end+1);start=end+1;
        }
    }
    private static final class Cursor {
        final String text; int offset;
        Cursor(String text){this.text=text;}
        boolean hasNext(){return offset<text.length();}
        String next()throws IOException{int end=text.indexOf('\n',offset);require(end>=0,"PATCH 不完整");String s=text.substring(offset,end);offset=end+1;return s;}
        boolean marker(){return text.startsWith("\\ No newline at end of file\n",offset);}
    }
    public static byte[] apply(byte[] before, byte[] patch) throws IOException {
        String s = text(patch); require(s != null, "无效 UTF-8 patch");
        require(s.endsWith("\n"), "PATCH 必须完整结束");
        Cursor lines=new Cursor(s); require(lines.next().equals("--- baseline") && lines.next().equals("+++ working"), "无效 PATCH 头");
        var matcher = java.util.regex.Pattern.compile("@@ -(0|1),([0-9]{1,8}) \\+(0|1),([0-9]{1,8}) @@").matcher(lines.next());
        require(matcher.matches(), "只接受完整单 hunk PATCH");
        int oldCount = Integer.parseInt(matcher.group(2)), newCount = Integer.parseInt(matcher.group(4));
        require(matcher.group(1).equals(oldCount == 0 ? "0" : "1") && matcher.group(3).equals(newCount == 0 ? "0" : "1"), "无效 hunk 起点");
        StringBuilder old = new StringBuilder(), next = new StringBuilder(); int removed = 0, added = 0; boolean additions = false;
        while(lines.hasNext()) {
            String line = lines.next(); require(!line.isEmpty() && (line.charAt(0) == '-' || line.charAt(0) == '+'), "无效 PATCH 内容");
            char prefix = line.charAt(0); if (prefix == '+') additions = true;
            require(prefix != '-' || !additions, "PATCH 顺序错误");
            StringBuilder target = prefix == '-' ? old : next;
            target.append(line.substring(1));
            boolean noNewline = lines.marker();
            if (noNewline) {
                lines.next();
                require(!lines.hasNext() || s.charAt(lines.offset)!=prefix, "无换行标记必须位于该侧最后一行");
            } else target.append('\n');
            if (prefix == '-') removed++; else added++;

        }
        require(removed == oldCount && added == newCount, "PATCH 行数不匹配");
        require(Arrays.equals(old.toString().getBytes(StandardCharsets.UTF_8), before), "BASE_CONTENT_MISMATCH: PATCH 原文不一致");
        return next.toString().getBytes(StandardCharsets.UTF_8);
    }
}
