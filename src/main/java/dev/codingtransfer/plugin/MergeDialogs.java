package dev.codingtransfer.plugin;

import com.intellij.diff.*;
import com.intellij.diff.merge.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.testFramework.BinaryLightVirtualFile;
import dev.codingtransfer.core.ThreeWayMerge;
import dev.codingtransfer.core.TextPatch;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** All merge outputs are in-memory virtual files; never point the editor at business files. */
final class MergeDialogs {
    static void resolve(Project project,List<ThreeWayMerge.Conflict> conflicts,Consumer<Map<String,byte[]>> done,Runnable cancel,Consumer<Exception> failure){
        next(project,conflicts,0,new HashMap<>(),done,cancel,failure);
    }
    private static byte[] bytes(byte[] value){return value==null?new byte[0]:value;}
    private static boolean text(byte[] value){return value==null||TextPatch.text(value)!=null;}
    private static void next(Project project,List<ThreeWayMerge.Conflict> conflicts,int index,Map<String,byte[]> results,Consumer<Map<String,byte[]>> done,Runnable cancel,Consumer<Exception> failure){
        if(project.isDisposed()){cancel.run();return;}
        if(index==conflicts.size()){done.accept(results);return;}
        ThreeWayMerge.Conflict c=conflicts.get(index);
        try{
            boolean isText=text(c.base())&&text(c.ours())&&text(c.theirs());
            BinaryLightVirtualFile output=new BinaryLightVirtualFile(c.path(),PlainTextFileType.INSTANCE,bytes(c.ours()));output.setCharset(StandardCharsets.UTF_8);
            List<byte[]> contents=List.of(bytes(c.ours()),bytes(c.base()),bytes(c.theirs()));
            List<String> titles=List.of(c.ours()==null?"OURS · VM 已删除":"OURS · VM 当前",c.base()==null?"BASE · HOST 原先不存在":"BASE · HOST 修改前",c.theirs()==null?"THEIRS · HOST 删除":"THEIRS · HOST 修改后");
            ConflictType type=c.base()==null?ConflictType.ADDED_ADDED:c.ours()==null?ConflictType.DELETED_MODIFIED:c.theirs()==null?ConflictType.MODIFIED_DELETED:ConflictType.DEFAULT;
            TextMergeRequest[] textRequest=new TextMergeRequest[1];
            com.intellij.util.Consumer<MergeResult> callback=result->{
                // Continue only after the current merge window has completed its own close lifecycle.
                ApplicationManager.getApplication().invokeLater(()->{
                    if(result==MergeResult.CANCEL||project.isDisposed()){cancel.run();return;}
                    try{
                        byte[] resolved;
                        if(result==MergeResult.LEFT)resolved=c.ours();
                        else if(result==MergeResult.RIGHT)resolved=c.theirs();
                        else if(isText){
                            String value=textRequest[0].getOutputContent().getDocument().getText();
                            byte[] original=bytes(c.ours()!=null?c.ours():c.theirs());
                            if(ThreeWayMerge.mixedEndings(original)&&Messages.showYesNoDialog(project,"原文件包含混合换行；手工合并结果将统一为原文件首个换行格式。是否继续？",c.path(),Messages.getQuestionIcon())!=Messages.YES){cancel.run();return;}
                            resolved=ThreeWayMerge.editedText(value,original);
                            if(value.isEmpty()&&(c.ours()==null||c.theirs()==null)){
                                int choice=Messages.showDialog(project,c.path(),"确认合并结果",new String[]{"删除文件","保留空文件","取消本次同步"},2,Messages.getQuestionIcon());
                                if(choice<0||choice==2){cancel.run();return;}if(choice==0)resolved=null;
                            }
                        }else throw new IllegalStateException("二进制冲突必须明确选择 OURS 或 THEIRS");
                        results.put(c.path(),resolved);next(project,conflicts,index+1,results,done,cancel,failure);
                    }catch(Exception e){failure.accept(e);}
                });
            };
            MergeRequest request;
            if(isText){textRequest[0]=DiffRequestFactory.getInstance().createTextMergeRequest(project,output,contents,type,"Every Sync · "+c.path(),titles,callback);request=textRequest[0];}
            else request=DiffRequestFactory.getInstance().createBinaryMergeRequest(project,output,contents,"Every Sync · "+c.path(),titles,callback);
            DiffManager.getInstance().showMerge(project,request);
        }catch(Exception e){failure.accept(e);}
    }
}
