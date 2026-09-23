package dev.codingtransfer.plugin;

import dev.codingtransfer.core.FileTransfer;
import dev.codingtransfer.core.SafeFiles;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Role-independent file clipboard UI. Source and destination are chosen locally, never by a payload. */
final class FileTransferPanel extends JPanel implements Disposable {
    private final Project project;
    private final Path workspace;
    private boolean busy,disposed;
    private Path selected;
    private FileTransfer.Outgoing outgoing;
    private FileTransfer.Received incoming;
    private int part;
    private final java.util.BitSet copied=new java.util.BitSet();
    private final java.util.Set<String> sendCaches=new java.util.HashSet<>();
    private final JButton clearSend=new JButton("清空会话");
    private final JButton choose=new JButton("选择单个文件"),previous=new JButton("上一块"),copy=new JButton("复制"),next=new JButton("下一块");
    private final JButton paste=new JButton("读取剪贴板"),save=new JButton("选择路径保存"),clear=new JButton("清空会话");
    private final JSpinner chunk=new JSpinner(new SpinnerNumberModel(128,4,4096,4));
    private final JBTextArea source=note("未选择文件"),sendInfo=note(""),receiveInfo=note(""),status=note(""),sendStatus=note(""),receiveStatus=note("");
    private final JTabbedPane modes=new JBTabbedPane();
    private final JLabel partLabel=new JLabel("尚未生成");
    private final JProgressBar progress=new JProgressBar();
    FileTransferPanel(Project project){
        super(new BorderLayout());this.project=project;workspace=project.getBasePath()==null?null:Path.of(project.getBasePath());setBorder(JBUI.Borders.empty(12));
        JPanel send=vertical();send.add(row(chunk,new JLabel("KiB"),choose,clearSend));send.add(sendStatus);send.add(partLabel);send.add(row(previous,copy,next));send.add(source);send.add(sendInfo);
        JPanel receive=vertical();receive.add(row(paste,save,clear));receive.add(receiveStatus);receive.add(progress);receive.add(receiveInfo);
        modes.addTab("发送",new JBScrollPane(send));modes.addTab("接收",new JBScrollPane(receive));add(modes,BorderLayout.CENTER);
        choose.addActionListener(e->choose());chunk.addChangeListener(e->{outgoing=null;copied.clear();update();});
        JFormattedTextField chunkInput=((JSpinner.DefaultEditor)chunk.getEditor()).getTextField();chunkInput.setFocusLostBehavior(JFormattedTextField.COMMIT);
        chunkInput.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){try{chunk.commitEdit();}catch(java.text.ParseException ex){error(ex);}}});
        previous.addActionListener(e->{part--;update();});next.addActionListener(e->{part++;update();});
        copy.addActionListener(e->{try{CopyPasteManager.getInstance().setContents(new StringSelection(outgoing.chunks().get(part)));copied.set(part);part=Math.min(copied.nextClearBit(0),outgoing.chunks().size()-1);update();sendStatus.setText("已生成 · 文件 1 个");}catch(Exception ex){error(ex);}});
        paste.addActionListener(e->{try{receive(CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor));}catch(Exception ex){error(ex);}});
        save.addActionListener(e->save());clear.addActionListener(e->clear());clearSend.addActionListener(e->clearSend());update();
    }
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;}
    private static JPanel row(Component... items){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(6),JBUI.scale(6)));for(Component item:items)p.add(item);return p;}
    private static JBTextArea note(String value){JBTextArea p=new JBTextArea(value);p.setEditable(false);p.setOpaque(false);p.setLineWrap(true);p.setWrapStyleWord(true);p.setBorder(JBUI.Borders.empty(6,0));return p;}
    private void message(String value){status.setText(value);(modes.getSelectedIndex()==1?receiveStatus:sendStatus).setText(value);}
    private void error(Throwable e){message(e.getMessage()==null?e.toString():e.getMessage());Messages.showErrorDialog(project,status.getText(),"文件传输");}
    private void choose(){
        JFileChooser dialog=new JFileChooser(workspace.toFile());dialog.setDialogTitle("选择要发送的单个文件");dialog.setFileSelectionMode(JFileChooser.FILES_ONLY);dialog.setMultiSelectionEnabled(false);
        if(dialog.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){selected=dialog.getSelectedFile().toPath();source.setText(selected.toString());outgoing=null;copied.clear();sendInfo.setText("");generate();}
    }
    private void generate(){
        try{chunk.commitEdit();}catch(java.text.ParseException e){error(e);return;}
        Path file=selected;int size=((Number)chunk.getValue()).intValue();outgoing=null;copied.clear();update();
        job("读取文件并生成分块",()->FileTransfer.generate(file,size,workspace),result->{outgoing=result;sendCaches.add(result.metadata().session());part=0;sendInfo.setText(info(result.metadata()));message("已生成 · 文件 1 个");});
    }
    private static String info(FileTransfer.Metadata m){return "文件名："+m.filename()+"\n大小："+m.size()+" bytes\nSHA-256："+m.sha256();}
    private void receive(String value){
        if(value==null||value.isBlank()){error(new IllegalArgumentException("剪贴板没有文件分块文本"));return;}
        FileTransfer.Received previous=incoming;incoming=null;update();
        job("校验并接收文件分块",()->FileTransfer.receive(workspace,value),result->{
            incoming=result;progress.setMaximum(result.metadata().count());progress.setValue(result.received());
            receiveInfo.setText(info(result.metadata())+"\n会话："+result.metadata().session()+"\n已接收："+result.received()+" / "+result.metadata().count()+"\n最小未粘贴块号："+(result.firstMissing()==0?"无（已收齐）":result.firstMissing()));
            message(result.complete()?"已接收 · 文件 1 个 · 校验通过":"接收中");
        },()->{incoming=previous;});
    }
    private void save(){
        FileTransfer.Received received=incoming;
        JFileChooser dialog=new JFileChooser(workspace.toFile());dialog.setDialogTitle("选择接收文件的保存路径");dialog.setFileSelectionMode(JFileChooser.FILES_ONLY);dialog.setMultiSelectionEnabled(false);
        // Filename is only a suggestion; no transmitted source or destination path is used.
        String suggestion=received.metadata().filename().replaceAll("[<>:\"/\\\\|?*]","_");
        dialog.setSelectedFile(new java.io.File(workspace.toFile(),suggestion));
        if(dialog.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path target=dialog.getSelectedFile().toPath();
        job("检查保存目标",()->FileTransfer.destination(target),destination->{
            if(!destination.previousHash().equals("-")&&Messages.showYesNoDialog(project,"目标文件已存在，确认覆盖？\n"+destination.path()+"\n此操作不会自动备份原文件。","覆盖目标文件",Messages.getWarningIcon())!=Messages.YES){message("已取消覆盖，接收文件仍可另存。");return;}
            job("保存已校验文件",()->{FileTransfer.save(received,destination);return destination.path();},saved->{VirtualFileManager.getInstance().asyncRefresh(()->{});message("已保存："+saved);});
        });
    }
    private void clear(){
        String session=incoming.metadata().session();
        if(Messages.showYesNoDialog(project,"删除当前文件会话的分块和校验缓存？已另存的目标文件不受影响。","清理文件会话",Messages.getQuestionIcon())!=Messages.YES)return;
        job("清理文件会话",()->{FileTransfer.clear(workspace,session);return true;},unused->{incoming=null;progress.setValue(0);receiveInfo.setText("当前文件会话已清理");message("分块缓存已永久删除；已保存的文件不受影响。");});
    }
    private void clearSend(){
        if(Messages.showYesNoDialog(project,"清空发送分块和临时快照？源文件及接收端已保存文件不受影响。","清空发送会话",Messages.getQuestionIcon())!=Messages.YES)return;
        java.util.Set<String> caches=java.util.Set.copyOf(sendCaches);
        job("清理发送缓存",()->{for(String cache:caches)SafeFiles.clearOutgoing(workspace,"file-outgoing",cache);return true;},unused->{
            sendCaches.removeAll(caches);outgoing=null;selected=null;copied.clear();part=0;source.setText("未选择文件");sendInfo.setText("");sendStatus.setText("发送会话已清空");
        });
    }
    private void update(){
        boolean ready=!busy&&!disposed&&workspace!=null;choose.setEnabled(ready);chunk.setEnabled(ready);paste.setEnabled(ready);
        clearSend.setEnabled(ready&&(selected!=null||!sendCaches.isEmpty()));
        boolean sent=ready&&outgoing!=null;copy.setEnabled(sent);previous.setEnabled(sent&&part>0);next.setEnabled(sent&&part+1<outgoing.chunks().size());
        partLabel.setText(outgoing==null?"尚未生成":"第 "+(part+1)+" / "+outgoing.chunks().size()+" 块 · 已复制 "+copied.cardinality()+" / "+outgoing.chunks().size()+" · 最小未复制块号："+(copied.nextClearBit(0)>=outgoing.chunks().size()?"无":copied.nextClearBit(0)+1));save.setEnabled(ready&&incoming!=null&&incoming.complete());clear.setEnabled(ready&&incoming!=null);
    }
    private <T> void job(String title,Callable<T> work,Consumer<T> success){job(title,work,success,()->{});}
    private <T> void job(String title,Callable<T> work,Consumer<T> success,Runnable failureAction){
        if(busy||disposed)return;busy=true;update();message(title+"…");
        new Task.Backgroundable(project,title,false){
            T result;Exception failure;
            @Override public void run(ProgressIndicator indicator){indicator.setIndeterminate(true);try{result=work.call();}catch(Exception e){failure=e;}}
            @Override public void onSuccess(){if(disposed||project.isDisposed())return;busy=false;try{if(failure!=null){failureAction.run();error(failure);}else success.accept(result);}catch(Exception e){error(e);}finally{update();}}
            @Override public void onThrowable(Throwable e){if(!disposed){busy=false;failureAction.run();update();error(e);}}
            @Override public void onCancel(){if(!disposed){busy=false;failureAction.run();update();message("操作已取消");}}
        }.queue();
    }
    @Override public void dispose(){disposed=true;outgoing=null;incoming=null;}
}
