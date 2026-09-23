package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Content-only decisions. Null means absence, never an empty file. */
public final class ThreeWayMerge {
    private ThreeWayMerge(){}
    public record Conflict(String path,byte[] base,byte[] ours,byte[] theirs){
        public Conflict{base=copy(base);ours=copy(ours);theirs=copy(theirs);}
        @Override public byte[] base(){return copy(base);}
        @Override public byte[] ours(){return copy(ours);}
        @Override public byte[] theirs(){return copy(theirs);}
    }
    public record Result(byte[] content,boolean conflict){
        public Result{content=copy(content);}
        @Override public byte[] content(){return copy(content);}
    }
    private static byte[] copy(byte[] b){return b==null?null:b.clone();}
    public static boolean mixedEndings(byte[] original){
        String s=TextPatch.text(original);if(s==null)return false;
        boolean crlf=s.contains("\r\n");s=s.replace("\r\n","");
        return (crlf?1:0)+(s.contains("\r")?1:0)+(s.contains("\n")?1:0)>1;
    }
    /** IDEA normalizes its document; restore BOM and the reference's first separator. */
    public static byte[] editedText(String document,byte[] original){
        String reference=TextPatch.text(original);if(reference==null)reference="";
        String separator="\n";
        for(int i=0;i<reference.length();i++){
            if(reference.charAt(i)=='\r'){separator=i+1<reference.length()&&reference.charAt(i+1)=='\n'?"\r\n":"\r";break;}
            if(reference.charAt(i)=='\n')break;
        }
        String value=document.replace("\r\n","\n").replace('\r','\n').replace("\n",separator);
        if(reference.startsWith("\uFEFF")&&!value.startsWith("\uFEFF"))value="\uFEFF"+value;
        return value.getBytes(StandardCharsets.UTF_8);
    }
    public static Result merge(Path root,String git,byte[] base,byte[] ours,byte[] theirs)throws IOException{
        if(Arrays.equals(ours,theirs)||Arrays.equals(base,theirs))return new Result(ours,false);
        if(Arrays.equals(ours,base))return new Result(theirs,false);
        if(base==null||ours==null||theirs==null)return new Result(null,true);
        if(TextPatch.text(base)==null||TextPatch.text(ours)==null||TextPatch.text(theirs)==null)return new Result(null,true);
        byte[] merged=GitHistory.mergeFile(root,git,base,ours,theirs);
        return new Result(merged,merged==null);
    }
}
