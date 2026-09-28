package dev.codingtransfer.plugin;

import dev.codingtransfer.core.FileTransfer;
import dev.codingtransfer.core.FileReceiveConfirmation;
import dev.codingtransfer.core.SafeFiles;
import dev.codingtransfer.core.TransferLimits;
import dev.codingtransfer.core.ReliableTransferEngine;
import dev.codingtransfer.core.TransferSessionStore;
import dev.codingtransfer.core.TransferCompletionStore;
import dev.codingtransfer.core.EncryptedFrameCodec;
import dev.codingtransfer.core.Config;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
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
    private final JButton sendAuto=new JButton("发送"),receiveAuto=new JButton("接收"),cancelAuto=new JButton("暂停"),continueAuto=new JButton("继续确认"),endAuto=new JButton("结束会话"),recoverAuto=new JButton("恢复完成确认"),more=new JButton("更多");
    private final TransferCoordinator coordinator=ApplicationManager.getApplication().getService(TransferCoordinator.class);
    private ReliableTransferEngine engine;private String outerSession,transferId,savedSession;private byte[] pairingKey;private boolean transferActive,pendingConfirmation,sendingAuto;
    private TransferCompletionStore.Record recoverable;
    private final JBTextArea source=note("未选择文件"),sendInfo=note(""),receiveInfo=note(""),status=note("");
    private final TransferStatusHistory statusHistory=new TransferStatusHistory();
    private String statusSessionKey=java.util.UUID.randomUUID().toString();
    private final JTabbedPane modes=new JBTabbedPane();
    private final JLabel partLabel=new JLabel("尚未生成");
    private final JProgressBar progress=new JProgressBar();
    FileTransferPanel(Project project){
        super(new BorderLayout());this.project=project;workspace=project.getBasePath()==null?null:Path.of(project.getBasePath());setBorder(JBUI.Borders.empty(12));
        JPanel toolbar=vertical();toolbar.add(row(sendAuto,receiveAuto,cancelAuto,continueAuto,endAuto,recoverAuto,more));toolbar.add(status);add(toolbar,BorderLayout.NORTH);
        JPanel send=vertical();send.add(row(choose,clearSend));send.add(source);send.add(sendInfo);
        JPanel receive=vertical();receive.add(row(save,clear));receive.add(progress);receive.add(receiveInfo);
        modes.addTab("发送",new JBScrollPane(send));modes.addTab("接收",new JBScrollPane(receive));add(modes,BorderLayout.CENTER);
        choose.addActionListener(e->choose());
        sendAuto.addActionListener(e->startAutoSend());receiveAuto.addActionListener(e->startAutoReceive());cancelAuto.addActionListener(e->{coordinator.stop(this);message("传输已暂停；可继续确认或结束会话");update();});
        continueAuto.addActionListener(e->continueAuto());
        endAuto.addActionListener(e->endAuto());recoverAuto.addActionListener(e->recoverCompleted());more.addActionListener(e->showMore());
        previous.addActionListener(e->{part--;update();});next.addActionListener(e->{part++;update();});
        copy.addActionListener(e->{int selected=part;String value=outgoing.chunks().get(selected);coordinator.writeClipboardTextAsync(value).whenComplete((unused,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null){error(failure);return;}copied.set(selected);part=Math.min(copied.nextClearBit(0),outgoing.chunks().size()-1);update();status.setText("已生成 · 文件 1 个");}));});
        paste.addActionListener(e->coordinator.readClipboardTextAsync().whenComplete((text,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null)error(failure);else if(text==null)error(new IOException("剪贴板没有文本"));else receive(text);})));
        save.addActionListener(e->save());clear.addActionListener(e->clear());clearSend.addActionListener(e->clearSend());update();restoreContext();
    }
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;}
    private static JPanel row(Component... items){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(6),JBUI.scale(6)));for(Component item:items)p.add(item);return p;}
    private static JBTextArea note(String value){JBTextArea p=new JBTextArea(value);p.setEditable(false);p.setOpaque(false);p.setLineWrap(true);p.setWrapStyleWord(true);p.setBorder(JBUI.Borders.empty(6,0));return p;}
    private void message(String value){status.setText(value);}
    private void error(Throwable e){message(e.getMessage()==null?e.toString():e.getMessage());String diagnostic=PairingDiagnostic.copyText();if(diagnostic!=null&&diagnostic.contains("phase=SEND_LOAD\n")){int choice=Messages.showDialog(project,status.getText(),"文件传输",new String[]{"复制诊断","关闭"},1,Messages.getErrorIcon());if(choice==0)coordinator.writeClipboardTextAsync(diagnostic).whenComplete((unused,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null)error(failure);}));}else Messages.showErrorDialog(project,status.getText(),"文件传输");}
    private void choose(){
        if(transferActive||pendingConfirmation||busy)return;
        JFileChooser dialog=new JFileChooser(workspace.toFile());dialog.setDialogTitle("选择要发送的单个文件");dialog.setFileSelectionMode(JFileChooser.FILES_ONLY);dialog.setMultiSelectionEnabled(false);
        if(dialog.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){selected=dialog.getSelectedFile().toPath();source.setText(selected.toString());outgoing=null;copied.clear();sendInfo.setText("");generate();}
    }
    private void generate(){generate(false);}
    private void generate(boolean sendAfter){
        if(transferActive||pendingConfirmation||busy)return;
        Path file=selected;outgoing=null;copied.clear();update();
        job("读取文件并生成分块",()->FileTransferConfig.generate(workspace,file),result->{outgoing=result;sendCaches.add(result.metadata().session());part=0;sendInfo.setText(info(result.metadata()));message("已生成 · 文件 1 个");if(sendAfter)startAutoSend();});
    }
    private static String info(FileTransfer.Metadata m){return "文件名："+m.filename()+"\n大小："+m.size()+" bytes\nSHA-256："+m.sha256();}
    private Config pairing()throws Exception{
        PairingDiagnostic.clear();pairingKey=null;engine=null;
        if(workspace==null)throw new IOException("请先打开本地 IDEA 项目");
        return FileTransferConfig.beforeTransferStart(workspace,config->{
            pairingKey=new PairingSecretService(workspace).loadForSend(config.identity());
            if(pairingKey==null)throw FileTransferConfig.missingPairingKey();
            engine=ReliableTransferEngine.sender(config.identity(),pairingKey,new TransferSessionStore(workspace));
            PairingDiagnostic.clear();return config;
        });
    }
    private void autoStartError(Exception failure){String prompt=FileTransferConfig.startFailureMessage(failure);if(prompt!=null)error(new IOException(prompt));else error(failure);}

    private String dataFrame(int index)throws Exception{
        byte[] payload=outgoing.chunks().get(index-1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return engine.prepare(new ReliableTransferEngine.Message(outerSession,"file",transferId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.DATA,index,outgoing.metadata().count(),payload));
    }
    private void startAutoSend(){
        if(transferActive||pendingConfirmation||busy)return;
        if(outgoing==null){error(new IOException("请先选择并生成文件快照"));return;}
        try{Config config=pairing();if(selected!=null&&outgoing.metadata().chunkBytes()!=config.chunkKb()*1024/4*3){generate(true);return;}beginStatusSession();outerSession=java.util.UUID.randomUUID().toString();transferId=outgoing.metadata().session();
            String invite=engine.prepare(new ReliableTransferEngine.Message(outerSession,"file",transferId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.INVITE,0,outgoing.metadata().count(),new byte[0]));
            sendingAuto=true;pendingConfirmation=false;transferActive=true;coordinator.startSend(this,config.identity(),invite,this::senderFrame,listener(),logContext("FILE_SENDER"));message("等待接收端确认；会自动发送各分块");update();
        }catch(Exception ex){autoStartError(ex);}
    }
    private void startAutoReceive(){
        if(transferActive||pendingConfirmation||busy)return;
        try{Config c=pairing();beginStatusSession();engine=ReliableTransferEngine.receiver(c.identity(),pairingKey,new TransferSessionStore(workspace));sendingAuto=false;pendingConfirmation=false;transferActive=true;coordinator.startReceive(this,c.identity(),this::receiverFrame,listener(),logContext("FILE_RECEIVER"));message("等待加密文件邀请");update();}catch(Exception ex){autoStartError(ex);}
    }
    private void beginStatusSession(){statusSessionKey=java.util.UUID.randomUUID().toString();statusHistory.begin(statusSessionKey);}
    private TransferCoordinator.LogContext logContext(String endpoint){
        if(workspace==null||pairingKey==null)return null;byte[] key=pairingKey.clone();String identity;boolean host;
        try{Config pairingConfig=FileTransferConfig.read(workspace);identity=pairingConfig.identity();host=pairingConfig.role()==dev.codingtransfer.core.Model.Role.HOST;}catch(Exception e){return null;}
        return new TransferCoordinator.LogContext(workspace,endpoint,statusSessionKey,frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(identity,key,frame);return new TransferCoordinator.FrameInfo(m.session(),m.type().name(),m.index(),m.total());},()->TransferLogSettings.enabled(project,workspace),host);
    }
    private TransferCoordinator.Listener listener(){return new TransferCoordinator.Listener(){public void onStatus(String text){message(text);update();}public void onStatus(TransferCoordinator.StatusEvent event){if(event.complete())statusHistory.complete(event.sessionKey(),event.eventKey(),event.text(),java.time.Instant.now());else statusHistory.update(event.sessionKey(),event.eventKey(),event.text(),java.time.Instant.now());status.setText(statusHistory.display());update();}public void onStopped(TransferCoordinator.StopReason reason){transferActive=false;pendingConfirmation=(reason==TransferCoordinator.StopReason.RETRY_EXHAUSTED||reason==TransferCoordinator.StopReason.USER_PAUSED)&&(outerSession!=null||sendingAuto);if(reason==TransferCoordinator.StopReason.USER_PAUSED&&outerSession==null&&!sendingAuto)engine=null;if(reason==TransferCoordinator.StopReason.USER_ENDED){detachSessionContext();engine=null;outerSession=null;transferId=null;sendingAuto=false;pendingConfirmation=false;}update();if(reason==TransferCoordinator.StopReason.COMPLETED)releaseSavedFile();}};}
    private void endAuto(){if(transferActive){coordinator.endSession(this);return;}detachSessionContext();engine=null;outerSession=null;transferId=null;sendingAuto=false;pendingConfirmation=false;message("自动监听已结束；必要恢复数据仍保留");update();}
    private void detachSessionContext(){if(outerSession==null||workspace==null)return;try{new TransferSessionStore(workspace).clearContext(outerSession);}catch(IOException e){message("会话上下文未能解除："+e.getMessage());}}
    private void showMore(){
        JPopupMenu menu=new JPopupMenu();JMenuItem size=new JMenuItem("分块大小…");size.addActionListener(e->editChunkSize());menu.add(size);
        JMenuItem pairing=new JMenuItem("配对设置…");pairing.addActionListener(e->openPairingSettings());menu.add(pairing);menu.addSeparator();
        TransferLogMenu.add(menu,project,workspace,coordinator,text->Messages.showInfoMessage(project,text,"传输日志"),failure->Messages.showErrorDialog(project,failure.getMessage()==null?failure.toString():failure.getMessage(),"传输日志"));menu.show(more,0,more.getHeight());
    }
    private void editChunkSize(){
        try{
            Config current=FileTransferConfig.forSettings(workspace);
            JSpinner choice=new JSpinner(new SpinnerNumberModel(current.chunkKb(),4,TransferLimits.MAX_CHUNK_KB,4));
            if(JOptionPane.showConfirmDialog(this,new Object[]{new JLabel("每块大小（KB）"),choice},"文件传输设置",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
            choice.commitEdit();int value=((Number)choice.getValue()).intValue();
            FileTransferConfig.saveChunkSize(workspace,value);
            if(value!=current.chunkKb()&&selected!=null)generate();
        }catch(Exception ex){error(ex);}
    }
    private void openPairingSettings(){
        if(workspace==null){error(new IOException("请先打开本地 IDEA 项目"));return;}
        try{
            Config current=FileTransferConfig.forSettings(workspace);
            SettingsDialog dialog=new SettingsDialog(project,current,ignored->{},workspace);
            boolean confirmed=dialog.showAndGet();FileTransferConfig.saveIfConfirmed(workspace,dialog.result(),confirmed);
            if(confirmed&&selected!=null&&current.chunkKb()!=dialog.result().chunkKb())generate();
        }catch(Exception ex){error(ex);}
    }
    private void recoverCompleted(){
        try{if(recoverable==null)throw new IOException("没有可恢复的完成确认");Config c=FileTransferConfig.read(workspace);pairingKey=new PairingSecretService(workspace).load(c.identity());if(pairingKey==null)throw FileTransferConfig.missingPairingKey();outerSession=recoverable.session();transferId=recoverable.transferId();
            engine=ReliableTransferEngine.receiver(c.identity(),pairingKey,new TransferSessionStore(workspace));TransferCompletionStore completions=new TransferCompletionStore(workspace);transferActive=true;pendingConfirmation=false;coordinator.startReceive(this,c.identity(),frame->{ReliableTransferEngine.Message m=TransferCoordinator.decodePeerFrame(c.identity(),pairingKey,frame);TransferCompletionStore.Record record=completions.find(m.session()).orElseThrow(()->new IOException("没有此会话的持久完成记录"));if(m.type()!=ReliableTransferEngine.Type.FINAL||!m.scopeId().equals("file")||m.kind()!=ReliableTransferEngine.Kind.FILE||m.direction()!=ReliableTransferEngine.Direction.FILE||record.phase()!=TransferCompletionStore.Phase.COMPLETE||record.response().length==0||!record.scopeId().equals(m.scopeId())||!record.transferId().equals(m.transferId()))throw new IOException("最终确认与已完成文件不匹配");return new TransferCoordinator.Outcome(new String(record.response(),java.nio.charset.StandardCharsets.UTF_8),TransferCompletionText.format("恢复并重放已接收文件的最终确认",m.total()),true);},listener(),logContext("FILE_RECEIVER"));message("正在监听并恢复已完成文件的最终确认");update();
        }catch(Exception ex){error(ex);}
    }
    private void restoreContext(){
        if(workspace==null)return;try{recoverable=new TransferCompletionStore(workspace).findLatest(ReliableTransferEngine.Kind.FILE,"file",ReliableTransferEngine.Direction.FILE,TransferCompletionStore.Phase.COMPLETE).orElse(null);if(recoverable!=null&&recoverable.response().length==0)recoverable=null;Config c=FileTransferConfig.read(workspace);var store=new TransferSessionStore(workspace);var context=store.currentContext();if(context==null||context.kind()!=ReliableTransferEngine.Kind.FILE){restoreCompletedFile();update();return;}var completion=new TransferCompletionStore(workspace).find(context.session()).orElse(null);if(completion!=null&&completion.phase()==TransferCompletionStore.Phase.COMPLETE){store.clearSession(context.session());restoreCompletedFile();update();return;}
            pairingKey=new PairingSecretService(workspace).load(c.identity());if(pairingKey==null){message("当前IDEA项目尚未保存配对密钥，请通过更多→配对设置导入并保存");return;}outerSession=context.session();transferId=context.transferId();
            try{outgoing=FileTransfer.loadOutgoing(workspace,transferId);selected=null;sendingAuto=true;sendInfo.setText(info(outgoing.metadata())+"\n已恢复发送快照");}
            catch(IOException noOutgoing){sendingAuto=false;try{incoming=FileTransfer.resume(workspace,transferId);progress.setMaximum(incoming.metadata().count());progress.setValue(incoming.received());receiveInfo.setText(info(incoming.metadata())+"\n已恢复接收进度");}catch(IOException ignored){}}
            engine=sendingAuto?ReliableTransferEngine.sender(c.identity(),pairingKey,new TransferSessionStore(workspace)):ReliableTransferEngine.receiver(c.identity(),pairingKey,new TransferSessionStore(workspace));pendingConfirmation=true;message("发现未完成的文件传输；点击继续确认以恢复监听");update();
        }catch(FileTransferConfig.MissingConfigException ignored){restoreCompletedFile();}
        catch(Exception failure){message("文件传输恢复失败："+(failure.getMessage()==null?failure.toString():failure.getMessage()));}
    }
    private void restoreCompletedFile(){
        job("恢复已接收文件",()->FileTransfer.latestVerified(workspace),result->{
            if(result==null){message("");return;}incoming=result;progress.setMaximum(result.metadata().count());progress.setValue(result.received());
            receiveInfo.setText(info(result.metadata())+"\n已接收并校验，可选择路径保存");modes.setSelectedIndex(1);
        },()->{});
    }
    private void continueAuto(){
        try{if(!pendingConfirmation||outerSession==null)throw new IOException("没有待继续的文件传输");Config c=FileTransferConfig.read(workspace);pairingKey=new PairingSecretService(workspace).load(c.identity());if(pairingKey==null)throw FileTransferConfig.missingPairingKey();engine=sendingAuto?ReliableTransferEngine.sender(c.identity(),pairingKey,new TransferSessionStore(workspace)):ReliableTransferEngine.receiver(c.identity(),pairingKey,new TransferSessionStore(workspace));String frame=null;try{frame=new TransferSessionStore(workspace).readFrame(outerSession);}catch(IOException missing){var r=new TransferCompletionStore(workspace).find(outerSession).orElse(null);if(r!=null&&r.response().length>0)frame=new String(r.response(),java.nio.charset.StandardCharsets.UTF_8);}transferActive=true;pendingConfirmation=false;if(frame==null)coordinator.continueListening(this,c.identity(),sendingAuto?this::senderFrame:this::receiverFrame,listener(),logContext(sendingAuto?"FILE_SENDER":"FILE_RECEIVER"));else coordinator.continueConfirmation(this,c.identity(),frame,sendingAuto?this::senderFrame:this::receiverFrame,listener(),logContext(sendingAuto?"FILE_SENDER":"FILE_RECEIVER"));update();}catch(Exception ex){error(ex);}
    }
    private TransferCoordinator.Outcome senderFrame(String frame)throws Exception{
        ReliableTransferEngine.Message m=ReliableTransferEngine.decode(FileTransferConfig.read(workspace).identity(),pairingKey,frame);
        if(!m.session().equals(outerSession)||!m.scopeId().equals("file")||!m.transferId().equals(transferId)||m.kind()!=ReliableTransferEngine.Kind.FILE||m.direction()!=ReliableTransferEngine.Direction.FILE||m.total()!=outgoing.metadata().count())throw new TransferCoordinator.UnprocessableFrame("对端消息与当前文件传输不匹配");
        if(java.util.Set.of(ReliableTransferEngine.Type.INVITE,ReliableTransferEngine.Type.DATA,ReliableTransferEngine.Type.FINAL).contains(m.type()))throw new TransferCoordinator.OwnFrame();
        return switch(m.type()){
            case READY->{var result=engine.acceptReadyResult(frame,outerSession);if(result.disposition()==ReliableTransferEngine.ReadyDisposition.DUPLICATE)yield new TransferCoordinator.Outcome(result.awaitingDataIndex()<=m.total()?dataFrame(result.awaitingDataIndex()):null,"继续发送最小未确认块",false).phase("WAIT_ACK_DATA_"+result.awaitingDataIndex());yield new TransferCoordinator.Outcome(dataFrame(1),"接收端已就绪，发送第 1 / "+m.total()+" 块",false).retrying("WAIT_ACK_DATA_1");}
            case BLOCK_ACK->{var result=engine.acceptBlockAckResult(frame);if(result.nextIndex()<=m.total())yield new TransferCoordinator.Outcome(dataFrame(result.nextIndex()),"已确认 "+result.acknowledgedIndex()+" / "+m.total()+" 块",false).retrying("WAIT_ACK_DATA_"+result.nextIndex());yield new TransferCoordinator.Outcome(null,"文件块已全部确认，等待接收端完成校验",false).phase("WAIT_FILE_VERIFIED");}
            case MISSING->{int index=engine.acceptMissing(frame);yield new TransferCoordinator.Outcome(dataFrame(index),"正在补传第 "+index+" 块",false).retrying("WAIT_ACK_DATA_"+index);}
            case BUSINESS_ACK->{if(!java.util.Arrays.equals(m.payload(),businessPayload()))throw new IOException("接收回执中的文件身份/校验值不匹配");engine.acceptBusinessAck(frame,outerSession,"file",transferId);String fin=engine.prepareCompletion(new ReliableTransferEngine.Message(outerSession,"file",transferId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,ReliableTransferEngine.Type.FINAL,0,m.total(),new byte[0]));yield new TransferCoordinator.Outcome(fin,"接收端已收齐并校验文件，确认传输完成",false).retrying("WAIT_FINAL_ACK");}
            case FINAL_ACK->{new TransferCompletionStore(workspace).advance(new TransferCompletionStore.Record(outerSession,"file",transferId,ReliableTransferEngine.Kind.FILE,ReliableTransferEngine.Direction.FILE,TransferCompletionStore.Phase.COMPLETE,new byte[0],System.currentTimeMillis()));new TransferSessionStore(workspace).clearSession(outerSession);String cleanupWarning="";try{SafeFiles.clearOutgoing(workspace,"file-outgoing",transferId);outgoing=null;selected=null;}catch(IOException cleanup){cleanupWarning="；发送快照未能清理："+cleanup.getMessage();}yield new TransferCoordinator.Outcome(null,TransferCompletionText.format("文件传输完成",m.total())+cleanupWarning,true);}
            default->throw new TransferCoordinator.UnprocessableFrame("当前文件发送状态不接受 "+m.type());
        };
    }
    private byte[] businessPayload(){return (outgoing.metadata().filename()+"\n"+outgoing.metadata().size()+"\n"+outgoing.metadata().sha256()).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private TransferCoordinator.Outcome receiverFrame(String frame)throws Exception{
        Config c=FileTransferConfig.read(workspace);ReliableTransferEngine.Message m=TransferCoordinator.decodePeerFrame(c.identity(),pairingKey,frame);
        if(m.type()==ReliableTransferEngine.Type.INVITE){if(m.kind()!=ReliableTransferEngine.Kind.FILE||m.direction()!=ReliableTransferEngine.Direction.FILE||!m.scopeId().equals("file"))throw new TransferCoordinator.UnprocessableFrame("邀请类型不匹配");if(outerSession!=null&&!outerSession.equals(m.session()))throw new TransferCoordinator.UnprocessableFrame("已有其他文件传输会话正在进行");if(outerSession==null)engine=ReliableTransferEngine.receiver(c.identity(),pairingKey,new TransferSessionStore(workspace));var accepted=engine.acceptInviteResult(frame);if(accepted.disposition()==ReliableTransferEngine.InviteDisposition.ALREADY_ADVANCED)return new TransferCoordinator.Outcome(null,"忽略已推进会话的重复邀请",false).phase("WAIT_DATA");if(outerSession==null){outerSession=m.session();transferId=m.transferId();}return new TransferCoordinator.Outcome(accepted.readyFrame(),"已接收 0 / "+m.total()+" 块 · 等待数据",false).replayable("WAIT_DATA_1");}
        if(m.session().equals(outerSession)&&m.scopeId().equals("file")&&m.transferId().equals(transferId)&&m.kind()==ReliableTransferEngine.Kind.FILE&&m.direction()==ReliableTransferEngine.Direction.FILE&&m.type()==ReliableTransferEngine.Type.DATA){
            {String replay=FileReceiveConfirmation.replayData(workspace,outerSession,transferId,c.identity(),pairingKey);if(replay!=null){if(ReliableTransferEngine.decode(c.identity(),pairingKey,replay).total()!=m.total())throw new IOException("重复文件块数量与已确认会话不匹配");return new TransferCoordinator.Outcome(replay,"已接收 "+m.total()+" / "+m.total()+" 块 · 重发接收确认",false).retrying("WAIT_FINAL_ACK").replayable("WAIT_FINAL_ACK");}}
            String ack=engine.acceptData(frame,data->{FileTransfer.Received r=FileTransfer.receive(workspace,new String(data.payload(),java.nio.charset.StandardCharsets.UTF_8),transferId,data.index(),data.total());if(!r.metadata().session().equals(transferId)||r.metadata().count()!=m.total())throw new IOException("文件分块与邀请中的会话/数量不匹配");incoming=r;});
            FileTransfer.Received r=incoming;
            if(r!=null)ApplicationManager.getApplication().invokeLater(()->{
                progress.setMaximum(r.metadata().count());progress.setValue(r.received());
                receiveInfo.setText(info(r.metadata())+"\n已接收 "+r.received()+" / "+r.metadata().count()+" 块");
            });
            if(r!=null&&r.complete()){
                String confirmation=FileReceiveConfirmation.confirmVerified(workspace,outerSession,transferId,r,c.identity(),pairingKey);
                ApplicationManager.getApplication().invokeLater(()->{progress.setMaximum(r.metadata().count());progress.setValue(r.received());receiveInfo.setText(info(r.metadata())+"\n已接收 "+r.received()+" / "+r.metadata().count()+" 块 · 校验通过，可选择路径保存");save.setEnabled(true);});
                return new TransferCoordinator.Outcome(confirmation,"已接收 "+m.total()+" / "+m.total()+" 块 · 校验通过，等待最终确认",false).retrying("WAIT_FINAL_ACK").replayable("WAIT_FINAL_ACK");
            }
            return new TransferCoordinator.Outcome(ack,"已接收 "+(r==null?0:r.received())+" / "+m.total()+" 块",false).replayable("WAIT_DATA_"+(m.index()+1));
        }
        if(m.session().equals(outerSession)&&m.scopeId().equals("file")&&m.transferId().equals(transferId)&&m.kind()==ReliableTransferEngine.Kind.FILE&&m.direction()==ReliableTransferEngine.Direction.FILE&&m.type()==ReliableTransferEngine.Type.FINAL){
            String response=FileReceiveConfirmation.acceptFinal(workspace,frame,c.identity(),pairingKey);
            recoverable=new TransferCompletionStore(workspace).find(outerSession).orElseThrow();
            ApplicationManager.getApplication().invokeLater(()->{receiveInfo.setText(TransferCompletionText.format("文件传输完成",m.total())+"；已校验，可选择路径保存");save.setEnabled(incoming!=null&&incoming.complete());});
            return new TransferCoordinator.Outcome(response,TransferCompletionText.format("文件传输完成",m.total())+"；接收缓存保留供稍后保存",true).replayable("WAIT_FINAL_ACK");
        }
        if(m.session().equals(outerSession)&&m.scopeId().equals("file")&&m.transferId().equals(transferId)&&m.kind()==ReliableTransferEngine.Kind.FILE&&m.direction()==ReliableTransferEngine.Direction.FILE
                &&java.util.Set.of(ReliableTransferEngine.Type.READY,ReliableTransferEngine.Type.BLOCK_ACK,ReliableTransferEngine.Type.MISSING,ReliableTransferEngine.Type.BUSINESS_ACK,ReliableTransferEngine.Type.FINAL_ACK).contains(m.type()))throw new TransferCoordinator.OwnFrame();
        throw new TransferCoordinator.UnprocessableFrame("当前文件接收状态不接受 "+m.type());
    }
    private void receive(String value){
        if(transferActive||pendingConfirmation||busy)return;
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
            job("保存已校验文件",()->{FileTransfer.save(received,destination);return destination.path();},saved->{VirtualFileManager.getInstance().asyncRefresh(()->{});savedSession=received.metadata().session();message("已保存："+saved);releaseSavedFile();});
        });
    }
    private void releaseSavedFile(){
        if(savedSession==null||transferActive||pendingConfirmation||busy)return;
        String session=savedSession;
        job("清理已保存文件缓存",()->{FileTransfer.clear(workspace,session);return true;},unused->{
            savedSession=null;if(incoming!=null&&incoming.metadata().session().equals(session))incoming=null;
            receiveInfo.setText("文件已保存；接收缓存已清理");
        });
    }
    private void clear(){
        if(transferActive||pendingConfirmation||busy||incoming==null)return;
        String session=incoming.metadata().session();
        if(Messages.showYesNoDialog(project,"删除当前文件会话的分块和校验缓存？已另存的目标文件不受影响。","清理文件会话",Messages.getQuestionIcon())!=Messages.YES)return;
        job("清理文件会话",()->{FileTransfer.clear(workspace,session);return true;},unused->{incoming=null;progress.setValue(0);receiveInfo.setText("当前文件会话已清理");message("分块缓存已永久删除；已保存的文件不受影响。");});
    }
    private void clearSend(){
        if(transferActive||pendingConfirmation||busy)return;
        if(Messages.showYesNoDialog(project,"清空发送分块和临时快照？源文件及接收端已保存文件不受影响。","清空发送会话",Messages.getQuestionIcon())!=Messages.YES)return;
        java.util.Set<String> caches=java.util.Set.copyOf(sendCaches);
        job("清理发送缓存",()->{for(String cache:caches)SafeFiles.clearOutgoing(workspace,"file-outgoing",cache);return true;},unused->{
            sendCaches.removeAll(caches);outgoing=null;selected=null;copied.clear();part=0;source.setText("未选择文件");sendInfo.setText("");status.setText("发送会话已清空");
        });
    }
    private void update(){
        boolean ready=!busy&&!disposed&&workspace!=null;boolean foreignContext=false;if(ready)try{var context=new TransferSessionStore(workspace).currentContext();foreignContext=context!=null&&context.kind()!=ReliableTransferEngine.Kind.FILE;}catch(IOException ignored){foreignContext=true;}boolean locked=transferActive||pendingConfirmation||foreignContext;choose.setEnabled(ready&&!locked);paste.setEnabled(false);
        sendAuto.setEnabled(ready&&!locked&&outgoing!=null);receiveAuto.setEnabled(ready&&!locked&&incoming==null);cancelAuto.setEnabled(ready&&transferActive);cancelAuto.setVisible(transferActive);
        more.setEnabled(ready&&!locked);clearSend.setEnabled(ready&&!locked&&(selected!=null||!sendCaches.isEmpty()));
        boolean sent=ready&&!locked&&outgoing!=null;copy.setEnabled(sent);previous.setEnabled(sent&&part>0);next.setEnabled(sent&&part+1<outgoing.chunks().size());
        partLabel.setText(outgoing==null?"尚未生成":"第 "+(part+1)+" / "+outgoing.chunks().size()+" 块 · 已复制 "+copied.cardinality()+" / "+outgoing.chunks().size()+" · 最小未复制块号："+(copied.nextClearBit(0)>=outgoing.chunks().size()?"无":copied.nextClearBit(0)+1));save.setEnabled(ready&&incoming!=null&&incoming.complete());clear.setEnabled(ready&&!locked&&incoming!=null);cancelAuto.setVisible(transferActive);cancelAuto.setEnabled(ready&&transferActive);continueAuto.setVisible(pendingConfirmation);continueAuto.setEnabled(ready&&pendingConfirmation);endAuto.setVisible(locked);endAuto.setEnabled(ready&&locked);recoverAuto.setVisible(recoverable!=null&&!locked);recoverAuto.setEnabled(ready&&recoverable!=null&&!locked);previous.setVisible(false);copy.setVisible(false);next.setVisible(false);paste.setVisible(false);
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
    @Override public void dispose(){if(transferActive)coordinator.stop(this);disposed=true;outgoing=null;incoming=null;}
}
