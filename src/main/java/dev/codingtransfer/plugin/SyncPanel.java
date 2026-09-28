package dev.codingtransfer.plugin;

import dev.codingtransfer.core.*;
import dev.codingtransfer.core.Model.*;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class SyncPanel extends JPanel implements Disposable {
    private final Project project;
    private Path workspace;
    private Config config;
    private boolean loading,busy,disposed;
    private final JLabel roleLabel=new JLabel();
    private final BitSet copied=new BitSet();
    private List<GitHistory.RefChange> refRows=List.of();
    private final JComboBox<Config.Mapping> projects=new JComboBox<>();
    private final JButton settings=new JButton("设置"),records=new JButton("本地记录");
    private final JBTextArea status=note("");
    private final TransferStatusHistory statusHistory=new TransferStatusHistory();
    private String statusSessionKey=UUID.randomUUID().toString();
    private final JLabel sendFiles=new JLabel(),receiveFiles=new JLabel();
    private final JLabel partLabel=new JLabel("尚未生成"),receiveCount=new JLabel("等待接收");
    private final CardLayout contentLayout=new CardLayout();private final JPanel tabs=new JPanel(contentLayout);
    private final JButton generate=new JButton("生成传输"),prev=new JButton("上一块"),copy=new JButton("复制"),next=new JButton("下一块");
    private final JButton incremental=new JButton("生成增量"),receipt=new JButton("发送回执"),acceptReceipt=new JButton("接收回执"),clearSend=new JButton("清空会话");
    private final JButton sendAuto=new JButton("发送"),receiveAuto=new JButton("接收"),cancelAuto=new JButton("暂停"),continueAuto=new JButton("继续确认"),endAuto=new JButton("结束会话");
    private final JButton recoverFinal=new JButton("恢复最终确认");
    private final JButton more=new JButton("更多");
    private final TransferCoordinator coordinator=ApplicationManager.getApplication().getService(TransferCoordinator.class);
    private final Set<String> sendCaches=new HashSet<>();
    private final JButton paste=new JButton("读取剪贴板"),apply=new JButton("应用变更"),clear=new JButton("清空会话");
    private final JProgressBar receiveProgress=new JProgressBar();
    private final DefaultTableModel sendModel=tableModel(),receiveModel=tableModel();
    private final JBTable sendTable=new JBTable(sendModel),receiveTable=new JBTable(receiveModel);
    private final JPanel packet=new JPanel();
    private List<Change> sendChanges=List.of(),receiveChanges=List.of();
    private List<String> outgoing=List.of();private int part;
    private Payload incoming,outgoingPayload;private String session;private SyncEngine.Preview preview;
    private record ApplyResult(Path backup,String cleanupWarning){}
    private record RestoredSend(Payload payload,List<String> parts){}
    private ReliableTransferEngine transferEngine;private boolean transferActive,sendingAuto,hostAutoApply,pendingConfirmation;
    private boolean recoverableFinalAvailable;
    private String outerSession,transferId,inboundInnerSession;private int transferTotal,nextBlock=1;

    public SyncPanel(Project project){
        super(new BorderLayout());this.project=project;setBorder(JBUI.Borders.empty(12));
        JPanel header=vertical();header.add(row(new JLabel("项目"),projects,roleLabel,settings,more));header.add(row(generate,incremental,sendAuto,receiveAuto,apply,cancelAuto,continueAuto,endAuto,recoverFinal));header.add(status);add(header,BorderLayout.NORTH);
        JPanel send=new JPanel(new BorderLayout()),sendTop=vertical();sendTop.add(sendFiles);
        packet.setLayout(new BoxLayout(packet,BoxLayout.Y_AXIS));packet.add(partLabel);packet.add(row(prev,copy,next));packet.setVisible(false);sendTop.add(packet);
        send.add(sendTop,BorderLayout.NORTH);send.add(new JBScrollPane(sendTable),BorderLayout.CENTER);
        JPanel recv=new JPanel(new BorderLayout()),recvTop=vertical();recvTop.add(receiveFiles);recvTop.add(receiveCount);recvTop.add(receiveProgress);
        recv.add(recvTop,BorderLayout.NORTH);recv.add(new JBScrollPane(receiveTable),BorderLayout.CENTER);
        tabs.add(send,"send");tabs.add(recv,"receive");add(tabs,BorderLayout.CENTER);
        for(JBTable table:List.of(sendTable,receiveTable)){
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setRowHeight(JBUI.scale(28));table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(38));
            table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(!busy && table.getSelectedRow()>=0)showDiff(table==sendTable?sendChanges:receiveChanges,table.getSelectedRow(),table==sendTable);}});
            table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"diff");table.getActionMap().put("diff",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){if(!busy)showDiff(table==sendTable?sendChanges:receiveChanges,table.getSelectedRow(),table==sendTable);}});
        }
        settings.addActionListener(e->settings());records.addActionListener(e->{try{RevealFileAction.openDirectory(SafeFiles.state(workspace));}catch(Exception ex){error(ex);}});
        more.addActionListener(e->showMore());
        projects.addActionListener(e->{if(!loading&&!busy){reset();labels();restoreDurableState();}});
        generate.addActionListener(e->generate(false));acceptReceipt.addActionListener(e->readReceipt());
        incremental.addActionListener(e->generate(true));receipt.addActionListener(e->copyReceipt());
        sendAuto.addActionListener(e->startAutomaticSend());receiveAuto.addActionListener(e->startAutomaticReceive());
        cancelAuto.addActionListener(e->{coordinator.stop(this);message("传输已暂停；可继续确认或结束会话");updateEnabled();});
        continueAuto.addActionListener(e->continueAutomaticTransfer());
        endAuto.addActionListener(e->endAutomaticTransfer());
        recoverFinal.addActionListener(e->recoverFinalConfirmation());
        prev.addActionListener(e->{part--;updateEnabled();});next.addActionListener(e->{part++;updateEnabled();});
        copy.addActionListener(e->{int selected=part;String value=outgoing.get(selected);coordinator.writeClipboardTextAsync(value).whenComplete((unused,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null){error(failure);return;}copied.set(selected);part=Math.min(copied.nextClearBit(0),outgoing.size()-1);updateEnabled();status.setText("");}));});
        paste.addActionListener(e->coordinator.readClipboardTextAsync().whenComplete((text,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null)error(failure);else if(text==null)error(new IOException("剪贴板没有文本"));else receive(text);})));
        apply.addActionListener(e->previewOrApply());clear.addActionListener(e->clear());clearSend.addActionListener(e->clearSend());
        updateEnabled();
        if(project.getBasePath()==null){message("请打开一个本地 IDEA 项目目录");return;}
        job("加载 Every Sync 配置",()->{
            workspace=Path.of(project.getBasePath()).toRealPath();Path file=workspace.resolve(".vmsync/config.yaml");SafeFiles.noLinks(file);
            return Files.exists(file)?Config.parse(new String(SafeFiles.read(file,64*1024),StandardCharsets.UTF_8)):null;
        },loaded->{if(loaded!=null)setConfig(loaded);else message("首次使用：打开设置，选择 HOST / VM 并配置项目映射");});
    }
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;}
    private static JPanel row(Component... components){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(6),JBUI.scale(6)));for(Component c:components)p.add(c);return p;}
    private void showMore(){
        JPopupMenu menu=new JPopupMenu();JMenuItem local=new JMenuItem("本地记录"),clearOut=new JMenuItem("清理发送缓存"),clearIn=new JMenuItem("清理接收缓存");
        boolean canClear=!busy&&!transferActive&&!pendingConfirmation&&!hasPendingVmBundle();clearOut.setEnabled(canClear);clearIn.setEnabled(canClear);local.setEnabled(!busy&&!transferActive&&!pendingConfirmation);
        local.addActionListener(e->{try{RevealFileAction.openDirectory(SafeFiles.state(workspace));}catch(Exception ex){error(ex);}});
        clearOut.addActionListener(e->clearSend());clearIn.addActionListener(e->clear());menu.add(local);menu.add(clearOut);menu.add(clearIn);TransferLogMenu.add(menu,project,workspace,coordinator,text->Messages.showInfoMessage(project,text,"传输日志"),failure->Messages.showErrorDialog(project,failure.getMessage()==null?failure.toString():failure.getMessage(),"传输日志"));menu.show(more,0,more.getHeight());
    }
    private static JBTextArea note(String text){JBTextArea area=new JBTextArea(text);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setOpaque(false);area.setBorder(JBUI.Borders.empty(6,0));return area;}
    private static DefaultTableModel tableModel(){return new DefaultTableModel(new Object[]{"","文件路径"},0){@Override public boolean isCellEditable(int r,int c){return false;}};}
    private String id(){Config.Mapping m=(Config.Mapping)projects.getSelectedItem();return m==null?"":m.id();}
    private boolean hasPendingVmBundle(){if(config==null||config.role()!=Role.VM||workspace==null)return false;try{return new BundleSync(workspace,config).hasUnacknowledgedPending(id());}catch(IOException e){return true;}}
    private void message(String text){status.setText(text);}
    private void error(Throwable e){message(e.getMessage()==null?e.toString():e.getMessage());Messages.showErrorDialog(project,status.getText(),"Every Sync");}
    private void reset(){if(transferActive)coordinator.stop(this);transferActive=false;sendingAuto=false;pendingConfirmation=false;outerSession=null;transferId=null;inboundInnerSession=null;transferEngine=null;outgoing=List.of();outgoingPayload=null;copied.clear();refRows=List.of();part=0;incoming=null;preview=null;session=null;sendChanges=List.of();receiveChanges=List.of();sendModel.setRowCount(0);receiveModel.setRowCount(0);packet.setVisible(false);receiveCount.setText("等待接收");receiveProgress.setValue(0);sendFiles.setText("");receiveFiles.setText("");status.setText("");contentLayout.show(tabs,"send");updateEnabled();}
    private void labels(){
        if(config==null)return;roleLabel.setText(config.role().name());boolean host=config.role()==Role.HOST;
        generate.setText(host?"生成传输":"生成全量");incremental.setVisible(!host);
        sendAuto.setText("发送");receiveAuto.setText(host?"接收并应用":"接收");apply.setText("本地预览 / 应用");apply.setVisible(!host);
        receipt.setVisible(false);acceptReceipt.setVisible(false);paste.setVisible(false);prev.setVisible(false);copy.setVisible(false);next.setVisible(false);
    }
    private void setConfig(Config value){String previous=id();loading=true;config=value;projects.removeAllItems();for(Config.Mapping m:value.projects()){projects.addItem(m);if(m.id().equals(previous))projects.setSelectedItem(m);}loading=false;reset();labels();restoreDurableState();}
    private void restoreDurableState(){
        if(workspace==null||config==null||busy)return;
        try{TransferCompletionStore records=new TransferCompletionStore(workspace);ReliableTransferEngine.Direction direction=config.role()==Role.VM?ReliableTransferEngine.Direction.HOST_TO_VM:ReliableTransferEngine.Direction.VM_TO_HOST;recoverableFinalAvailable=records.findLatest(ReliableTransferEngine.Kind.PROJECT,id(),direction,TransferCompletionStore.Phase.COMPLETE).filter(r->r.response().length>0).isPresent();if(config.role()==Role.VM&&!recoverableFinalAvailable)recoverableFinalAvailable=records.findLatest(ReliableTransferEngine.Kind.PROJECT,id(),ReliableTransferEngine.Direction.VM_TO_HOST,TransferCompletionStore.Phase.WAIT_FINAL_ACK).isPresent();}catch(IOException e){recoverableFinalAvailable=false;}
        try{var pending=new dev.codingtransfer.core.PendingWorktreeStore(workspace).read();if(config.role()==Role.VM&&pending!=null&&pending.projectId().equals(id())){
            job("恢复待处理 WORKTREE",()->new ProjectTransferService(workspace,config).loadCompletedWorktree(pending.projectId(),pending.innerSession()),payload->{
                incoming=payload;session=pending.innerSession();outerSession=pending.outerSession();transferId=pending.innerSession();inboundInnerSession=pending.innerSession();sendingAuto=false;pendingConfirmation=false;
                try{var context=new TransferSessionStore(workspace).currentContext();if(context!=null&&context.session().equals(pending.outerSession())){transferTotal=context.total();transferEngine=ReliableTransferEngine.receiver(config.identity(),pairingKey(),new TransferSessionStore(workspace));pendingConfirmation=true;}}
                catch(Exception e){message("待处理内容已恢复，确认通道恢复失败："+e.getMessage());}
                receiveChanges=List.of();fill(receiveModel,receiveChanges);receiveFiles.setText("已恢复待处理 WORKTREE；请预览并决定是否应用");contentLayout.show(tabs,"receive");message("已从磁盘恢复待处理项目内容");updateEnabled();
            });return;
        }
        }catch(Exception e){message("待处理 WORKTREE 恢复失败："+e.getMessage());}
        try{
            TransferSessionStore store=new TransferSessionStore(workspace);if(config.role()==Role.VM){var marker=new dev.codingtransfer.core.PendingBundleTransferStore(workspace).read();if(marker!=null&&marker.projectId().equals(id())&&store.currentContext()==null)store.saveContext(new TransferSessionStore.Context(marker.session(),marker.projectId(),marker.transferId(),ReliableTransferEngine.Kind.PROJECT,ReliableTransferEngine.Direction.VM_TO_HOST,marker.total()));}
            var context=store.currentContext();if(context==null||context.kind()!=ReliableTransferEngine.Kind.PROJECT||!context.scopeId().equals(id()))return;
            outerSession=context.session();transferId=context.transferId();transferTotal=context.total();
            boolean localSender=(config.role()==Role.HOST&&context.direction()==ReliableTransferEngine.Direction.HOST_TO_VM)||(config.role()==Role.VM&&context.direction()==ReliableTransferEngine.Direction.VM_TO_HOST);
            transferEngine=localSender?ReliableTransferEngine.sender(config.identity(),pairingKey(),store):ReliableTransferEngine.receiver(config.identity(),pairingKey(),store);sendingAuto=localSender;hostAutoApply=config.role()==Role.HOST;
            if(localSender){job("恢复项目发送",()->{var source=new dev.codingtransfer.core.ProjectOutgoingStore(workspace);Payload payload=source.load(context.session());if(!payload.project().equals(context.scopeId())||(payload.git()!=null&&!payload.git().snapshot().equals(context.transferId())))throw new IOException("持久项目发送源与传输上下文不匹配");String inner=payload.type()==Type.WORKTREE?context.transferId():null;return new RestoredSend(payload,source.loadParts(context.session(),inner,context.total()));},restored->{outgoingPayload=restored.payload();outgoing=restored.parts();nextBlock=1;try{if(store.hasFrame(context.session())){ReliableTransferEngine.Message current=ReliableTransferEngine.decode(config.identity(),pairingKey(),store.readFrame(context.session()));if(current.type()==ReliableTransferEngine.Type.DATA)nextBlock=current.index();}}catch(Exception ignored){}pendingConfirmation=true;contentLayout.show(tabs,"send");message("已恢复项目发送；点击继续确认");updateEnabled();});
            }else{pendingConfirmation=true;contentLayout.show(tabs,"receive");message("已恢复项目接收状态；点击继续确认");updateEnabled();}
        }catch(Exception e){message("项目传输恢复失败："+e.getMessage());}
    }
    private void settings(){try{Config defaults=config==null?Config.parse(Config.TEMPLATE):config;SettingsDialog dialog=new SettingsDialog(project,defaults,size->{
        Config current=config==null?defaults:config;if(size==current.chunkKb())return;
        Config changed=new Config(current.role(),current.projects(),current.exclude(),size,current.git(),current.identity());
        try{SafeFiles.atomicWrite(SafeFiles.state(workspace).resolve("config.yaml"),changed.yaml().getBytes(StandardCharsets.UTF_8));setConfig(changed);}catch(Exception ex){error(ex);}
    },workspace);if(dialog.showAndGet())saveConfig(dialog.result());}catch(Exception e){error(e);}}
    private void saveConfig(Config value){
        job("保存 Every Sync 配置",()->{Path file=SafeFiles.state(workspace).resolve("config.yaml");SafeFiles.atomicWrite(file,value.yaml().getBytes(StandardCharsets.UTF_8));return value;},this::setConfig);
    }
    private void fill(DefaultTableModel model,List<Change> changes){model.setRowCount(0);for(Change c:changes)model.addRow(new Object[]{c.status(),c.path()});}
    private void generate(boolean delta){
        if(hasPendingVmBundle()){message("该项目已有已开始传输的 VM Bundle；请恢复原自动传输后再生成");return;}
        if(config.role()==Role.VM && !new TransferConfirmDialog(project,
                "生成"+(delta?"增量":"全量"), "是否确认生成"+(delta?"增量":"全量")+"传输？").showAndGet())return;
        FileDocumentManager.getInstance().saveAllDocuments();Config cfg=config;String selected=id();
        // Clear the previous package before scanning, so failure cannot leave a stale copy action enabled.
        outgoing=List.of();copied.clear();packet.setVisible(false);sendFiles.setText("");
        job("生成传输",()->new ProjectTransferService(workspace,cfg).generate(selected,delta),result->{
            sendChanges=result.generated().changes();refRows=result.refs();fill(sendModel,sendChanges);if(cfg.role()==Role.VM){sendModel.setRowCount(0);for(GitHistory.RefChange row:refRows)sendModel.addRow(new Object[]{row.status(),row.label()});}
            if(result.generated().payload().git()!=null)sendCaches.add(result.generated().payload().git().snapshot());
            outgoing=result.parts();outgoingPayload=result.generated().payload();part=0;packet.setVisible(false);sendFiles.setText("已生成 · 包内文件 "+result.generated().payload().entries().size()+" 个");contentLayout.show(tabs,"send");message("传输内容已生成；准备好后点击发送");updateEnabled();
        });
    }

    private byte[] pairingKey() throws IOException {
        if(config.identity().isBlank())throw new IllegalStateException("请先在设置中生成或导入配对配置");
        byte[] key=new PairingSecretService(workspace).load(config.identity());
        if(key==null)throw new IllegalStateException("当前识别串没有 PasswordSafe 密钥；请点击“设置”并导入配对配置");
        return key;
    }
    private static String chunkSession(String text){
        for(String line:text.split("\\R"))if(line.startsWith("SESSION:"))return line.substring(8);
        throw new IllegalArgumentException("生成的传输块缺少会话标识");
    }
    private void beginStatusSession(){statusSessionKey=UUID.randomUUID().toString();statusHistory.begin(statusSessionKey);}
    private TransferCoordinator.LogContext logContext(String endpoint){
        byte[] key;try{key=pairingKey();}catch(IOException e){return null;}
        return new TransferCoordinator.LogContext(workspace,endpoint,statusSessionKey,frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(config.identity(),key,frame);return new TransferCoordinator.FrameInfo(m.session(),m.type().name(),m.index(),m.total());},()->TransferLogSettings.enabled(project,workspace),config.role()==Role.HOST);
    }
    private TransferCoordinator.Listener transferListener(){
        return new TransferCoordinator.Listener(){
            @Override public void onStatus(String value){if(disposed)return;message(value);}
            @Override public void onStatus(TransferCoordinator.StatusEvent value){if(disposed)return;java.time.Instant now=java.time.Instant.now();if(value.complete())statusHistory.complete(value.sessionKey(),value.eventKey(),value.text(),now);else statusHistory.update(value.sessionKey(),value.eventKey(),value.text(),now);status.setText(statusHistory.display());}
            @Override public void onStopped(TransferCoordinator.StopReason reason){
                if(disposed)return;transferActive=false;pendingConfirmation=(reason==TransferCoordinator.StopReason.RETRY_EXHAUSTED||reason==TransferCoordinator.StopReason.USER_PAUSED)&&outerSession!=null;
                if(reason==TransferCoordinator.StopReason.USER_ENDED){detachSessionContext();clearTransientSession();contentLayout.show(tabs,"send");updateEnabled();return;}
                if(reason==TransferCoordinator.StopReason.COMPLETED&&sendingAuto){
                    String cache=outgoingPayload==null||outgoingPayload.git()==null?null:outgoingPayload.git().snapshot();
                    String completedSession=outerSession;
                    job("释放已完成发送缓存",()->{String warning="";if(cache!=null)try{SafeFiles.clearOutgoing(workspace,"git-outgoing",cache);}catch(IOException e){warning="；Bundle 缓存未能清理";}try{if(completedSession!=null)new dev.codingtransfer.core.ProjectOutgoingStore(workspace).clear(completedSession);}catch(IOException e){warning+="；项目发送快照未能清理";}return warning;},warning->clearFinishedSend(warning));
                }
                updateEnabled();
            }
        };
    }
    private void clearFinishedSend(String warning){
        outgoing=List.of();outgoingPayload=null;copied.clear();sendChanges=List.of();sendModel.setRowCount(0);sendFiles.setText("");transferEngine=null;sendingAuto=false;pendingConfirmation=false;
        message("传输已完成，发送包内存已释放"+warning);updateEnabled();
    }
    private void endAutomaticTransfer(){
        if(transferActive){coordinator.endSession(this);return;}
        detachSessionContext();clearTransientSession();contentLayout.show(tabs,"send");message("自动监听已结束；必要恢复数据仍保留在磁盘");updateEnabled();
    }
    private void detachSessionContext(){if(outerSession==null)return;try{new TransferSessionStore(workspace).clearContext(outerSession);}catch(IOException e){message("会话上下文未能解除："+e.getMessage());}}
    private void recoverFinalConfirmation(){
        try{byte[] key=pairingKey();if(config.role()==Role.VM){var senderRecord=new TransferCompletionStore(workspace).findLatest(ReliableTransferEngine.Kind.PROJECT,id(),ReliableTransferEngine.Direction.VM_TO_HOST,TransferCompletionStore.Phase.WAIT_FINAL_ACK).orElse(null);if(senderRecord!=null){outerSession=senderRecord.session();transferId=senderRecord.transferId();sendingAuto=true;transferEngine=ReliableTransferEngine.sender(config.identity(),key,new TransferSessionStore(workspace));String finalFrame=new TransferSessionStore(workspace).readFrame(outerSession);ReliableTransferEngine.Message persisted=ReliableTransferEngine.decode(config.identity(),key,finalFrame);if(persisted.type()!=ReliableTransferEngine.Type.FINAL||!persisted.session().equals(outerSession)||!persisted.transferId().equals(transferId))throw new IOException("待恢复 FINAL 与 Bundle 回执不匹配");transferActive=true;pendingConfirmation=false;coordinator.continueConfirmation(this,config.identity(),finalFrame,this::handleSenderFrame,transferListener(),logContext("PROJECT_"+config.role().name()));updateEnabled();return;}}
            ReliableTransferEngine.Direction direction=config.role()==Role.VM?ReliableTransferEngine.Direction.HOST_TO_VM:ReliableTransferEngine.Direction.VM_TO_HOST;var record=new TransferCompletionStore(workspace).findLatest(ReliableTransferEngine.Kind.PROJECT,id(),direction,TransferCompletionStore.Phase.COMPLETE).filter(r->r.response().length>0).orElseThrow(()->new IOException("没有可恢复的最终确认"));
            String response=new String(record.response(),StandardCharsets.UTF_8);outerSession=record.session();transferId=record.transferId();sendingAuto=false;transferActive=true;pendingConfirmation=false;
            coordinator.startReceive(this,config.identity(),frame->{ReliableTransferEngine.Message m=ReliableTransferEngine.decode(config.identity(),key,frame);if(m.type()!=ReliableTransferEngine.Type.FINAL||!m.session().equals(record.session())||!m.scopeId().equals(record.scopeId())||!m.transferId().equals(record.transferId())||m.kind()!=record.kind()||m.direction()!=record.direction())throw new IOException("最终确认与已完成项目传输不匹配");return new TransferCoordinator.Outcome(response,TransferCompletionText.format("重放已持久化的项目最终确认",m.total()),true);},transferListener(),logContext("PROJECT_"+config.role().name()));updateEnabled();
        }catch(Exception e){error(e);}
    }
    private void clearTransientSession(){
        sendingAuto=false;pendingConfirmation=false;transferActive=false;outerSession=null;transferId=null;inboundInnerSession=null;transferEngine=null;
        outgoing=List.of();outgoingPayload=null;copied.clear();incoming=null;preview=null;sendChanges=List.of();receiveChanges=List.of();part=0;
        sendModel.setRowCount(0);receiveModel.setRowCount(0);packet.setVisible(false);
    }
    private void startAutomaticSend(){
        try{
            if(transferActive||pendingConfirmation)throw new IllegalStateException("当前传输尚未结束");
            beginStatusSession();
            if(outgoing.isEmpty()||outgoingPayload==null)throw new IllegalStateException("请先人工生成项目传输内容");
            byte[] key=pairingKey();outerSession=UUID.randomUUID().toString();transferTotal=outgoing.size();
            transferId=outgoingPayload.git()==null?chunkSession(outgoing.getFirst()):outgoingPayload.git().snapshot();
            transferEngine=ReliableTransferEngine.sender(config.identity(),key,new TransferSessionStore(workspace));
            ReliableTransferEngine.Direction direction=config.role()==Role.HOST?ReliableTransferEngine.Direction.HOST_TO_VM:ReliableTransferEngine.Direction.VM_TO_HOST;
            ReliableTransferEngine.Message invite=new ReliableTransferEngine.Message(outerSession,id(),transferId,
                    ReliableTransferEngine.Kind.PROJECT,direction,ReliableTransferEngine.Type.INVITE,0,transferTotal,new byte[0]);
            new dev.codingtransfer.core.ProjectOutgoingStore(workspace).save(outerSession,outgoingPayload,outgoing);
            String first=transferEngine.prepare(invite);if(config.role()==Role.VM)new BundleSync(workspace,config).markAutomaticTransferStarted(id(),outerSession,transferId,transferTotal);sendingAuto=true;transferActive=true;pendingConfirmation=false;nextBlock=1;contentLayout.show(tabs,"send");updateEnabled();
            coordinator.startSend(this,config.identity(),first,this::handleSenderFrame,transferListener(),logContext("PROJECT_"+config.role().name()));
        }catch(Exception e){transferActive=false;error(e);updateEnabled();}
    }
    private void startAutomaticReceive(){
        try{
            if(transferActive||pendingConfirmation)throw new IllegalStateException("当前传输尚未结束");
            if(config.role()==Role.VM&&new dev.codingtransfer.core.PendingWorktreeStore(workspace).read()!=null)throw new IllegalStateException("已有待处理 WORKTREE；请恢复并预览，或应用/清理后再接收");
            if(config.role()==Role.VM&&(incoming!=null||session!=null))throw new IllegalStateException("请先查看或应用现有待处理内容");
            beginStatusSession();pairingKey();outerSession=null;transferId=null;inboundInnerSession=null;transferEngine=null;incoming=null;preview=null;
            receiveChanges=List.of();fill(receiveModel,receiveChanges);receiveFiles.setText("");hostAutoApply=config.role()==Role.HOST;
            sendingAuto=false;transferActive=true;contentLayout.show(tabs,"receive");updateEnabled();
            coordinator.startReceive(this,config.identity(),this::handleReceiverFrame,transferListener(),logContext("PROJECT_"+config.role().name()));
        }catch(Exception e){transferActive=false;error(e);updateEnabled();}
    }
    private void continueAutomaticTransfer(){
        try{
            if(!pendingConfirmation&&outerSession==null&&hasDetachedBundleTransfer()){restoreDurableState();return;}
            if(!pendingConfirmation||outerSession==null)throw new IllegalStateException("没有待继续的传输");
            byte[] key=pairingKey();String frame=null;
            try{frame=new TransferSessionStore(workspace).readFrame(outerSession);}
            catch(IOException missing){var record=new TransferCompletionStore(workspace).find(outerSession).orElse(null);if(record!=null&&record.response().length>0)frame=new String(record.response(),StandardCharsets.UTF_8);}
            transferActive=true;pendingConfirmation=false;updateEnabled();
            TransferCoordinator.FrameHandler handler=sendingAuto?this::handleSenderFrame:this::handleReceiverFrame;
            if(frame==null)coordinator.continueListening(this,config.identity(),handler,transferListener(),logContext("PROJECT_"+config.role().name()));else coordinator.continueConfirmation(this,config.identity(),frame,handler,transferListener(),logContext("PROJECT_"+config.role().name()));
        }catch(Exception e){error(e);updateEnabled();}
    }
    private boolean hasDetachedBundleTransfer(){if(config==null||config.role()!=Role.VM||workspace==null)return false;try{var marker=new dev.codingtransfer.core.PendingBundleTransferStore(workspace).read();return marker!=null&&marker.projectId().equals(id())&&new TransferSessionStore(workspace).currentContext()==null;}catch(IOException e){return false;}}
    private String dataFrame(int index)throws IOException{
        byte[] key=pairingKey();String text=outgoing.get(index-1);
        ReliableTransferEngine.Direction direction=config.role()==Role.HOST?ReliableTransferEngine.Direction.HOST_TO_VM:ReliableTransferEngine.Direction.VM_TO_HOST;
        ReliableTransferEngine.Message data=new ReliableTransferEngine.Message(outerSession,id(),transferId,
                ReliableTransferEngine.Kind.PROJECT,direction,ReliableTransferEngine.Type.DATA,index,transferTotal,
                text.getBytes(StandardCharsets.UTF_8));
        nextBlock=index;return transferEngine.prepare(data);
    }
    private TransferCoordinator.Outcome handleSenderFrame(String frame)throws Exception{
        byte[] key=pairingKey();ReliableTransferEngine.Message message=TransferCoordinator.decodePeerFrame(config.identity(),key,frame);
        if(!message.session().equals(outerSession)||!message.scopeId().equals(id())||!message.transferId().equals(transferId))throw new TransferCoordinator.UnprocessableFrame("对端消息与当前项目传输不匹配");
        if(java.util.Set.of(ReliableTransferEngine.Type.INVITE,ReliableTransferEngine.Type.DATA,ReliableTransferEngine.Type.FINAL).contains(message.type()))throw new TransferCoordinator.OwnFrame();
        switch(message.type()){
            case READY->{var result=transferEngine.acceptReadyResult(frame,outerSession);if(result.disposition()==ReliableTransferEngine.ReadyDisposition.DUPLICATE)return new TransferCoordinator.Outcome(result.awaitingDataIndex()<=transferTotal?dataFrame(result.awaitingDataIndex()):null,"继续发送最小未确认块",false).phase("WAIT_ACK_DATA_"+result.awaitingDataIndex());return new TransferCoordinator.Outcome(dataFrame(1),"接收端已准备，发送第 1 / "+transferTotal+" 块",false).retrying("WAIT_ACK_DATA_1");}
            case BLOCK_ACK->{var result=transferEngine.acceptBlockAckResult(frame);if(result.nextIndex()<=transferTotal)return new TransferCoordinator.Outcome(dataFrame(result.nextIndex()),"已确认 "+result.acknowledgedIndex()+" / "+transferTotal+" 块",false).retrying("WAIT_ACK_DATA_"+result.nextIndex());return new TransferCoordinator.Outcome(null,"全部数据块已确认，等待业务完成确认",false).phase("WAIT_BUSINESS_CONFIRMATION");}
            case MISSING->{int missing=transferEngine.acceptMissing(frame);return new TransferCoordinator.Outcome(dataFrame(missing),"补传缺失块 "+missing+" / "+transferTotal,false).retrying("WAIT_ACK_DATA_"+missing);}
            case RECEIVED->{if(config.role()!=Role.HOST)throw new TransferCoordinator.UnprocessableFrame("角色不匹配的接收成功消息");String fin=new ProjectTransferService(workspace,config).acceptWorktreeReceivedAndPrepareFinal(frame,id(),transferId,config.identity(),key);return new TransferCoordinator.Outcome(fin,"VM 已可靠保存 WORKTREE，确认待处理内容接收",false).retrying("WAIT_RECEIVED_ACK");}
            case BUSINESS_ACK->{if(config.role()!=Role.VM)throw new TransferCoordinator.UnprocessableFrame("角色不匹配的业务应用回执");String fin=new ProjectTransferService(workspace,config).acceptBusinessAckAndPrepareFinal(frame,config.identity(),key);return new TransferCoordinator.Outcome(fin,"HOST 已应用并持久化基线，确认精确待处理包",false).retrying("WAIT_BUSINESS_ACK");}
            case FINAL_ACK->{if(config.role()==Role.HOST)new ProjectTransferService(workspace,config).acceptWorktreeFinalAck(frame,id(),transferId,config.identity(),key);else new ProjectTransferService(workspace,config).acceptFinalAck(frame,config.identity(),key);return new TransferCoordinator.Outcome(null,TransferCompletionText.format("双方已确认完成",message.total()),true);}
            default->throw new TransferCoordinator.UnprocessableFrame("当前状态不接受 "+message.type());
        }
    }
    private TransferCoordinator.Outcome handleReceiverFrame(String frame)throws Exception{
        byte[] key=pairingKey();ReliableTransferEngine.Message message=TransferCoordinator.decodePeerFrame(config.identity(),key,frame);
        if(message.type()==ReliableTransferEngine.Type.INVITE){
            ReliableTransferEngine.Direction expected=config.role()==Role.VM?ReliableTransferEngine.Direction.HOST_TO_VM:ReliableTransferEngine.Direction.VM_TO_HOST;
            if(message.kind()!=ReliableTransferEngine.Kind.PROJECT||message.direction()!=expected||!message.scopeId().equals(id()))throw new TransferCoordinator.UnprocessableFrame("邀请角色或项目不匹配");
            if(config.role()==Role.VM&&incoming!=null)throw new IOException("已有待处理内容，不能替换");
            if(outerSession!=null&&!outerSession.equals(message.session()))throw new TransferCoordinator.UnprocessableFrame("已有其他传输会话正在进行");
            if(outerSession==null){transferEngine=ReliableTransferEngine.receiver(config.identity(),key,new TransferSessionStore(workspace));}
            var accepted=transferEngine.acceptInviteResult(frame);if(outerSession==null){outerSession=message.session();transferId=message.transferId();transferTotal=message.total();}if(accepted.disposition()==ReliableTransferEngine.InviteDisposition.ALREADY_ADVANCED)return new TransferCoordinator.Outcome(null,"忽略已推进会话的重复邀请",false).phase("WAIT_DATA");return new TransferCoordinator.Outcome(accepted.readyFrame(),"已接收 0 / "+transferTotal+" 块 · 等待数据",false).replayable("WAIT_DATA_1");
        }
        if(message.session().equals(outerSession)&&message.type()==ReliableTransferEngine.Type.DATA){
            String replay=new ProjectTransferService(workspace,config).replayCompletedDataResponse(frame,config.identity(),key);
            if(replay!=null)return new TransferCoordinator.Outcome(replay,"已接收 "+message.total()+" / "+message.total()+" 块 · 重发完成确认",false).retrying("WAIT_BUSINESS_CONFIRMATION");
            if(!message.scopeId().equals(id())||!message.transferId().equals(transferId)||message.total()!=transferTotal)throw new IOException("数据块与已接受邀请不匹配");
            ClipboardTransport.Received[] holder=new ClipboardTransport.Received[1];ProjectTransferService service=new ProjectTransferService(workspace,config);
            String blockAck=transferEngine.acceptData(frame,data->{
                String chunk=new String(data.payload(),StandardCharsets.UTF_8);ClipboardTransport.Received received=service.receiveChunk(id(),chunk);
                if(inboundInnerSession==null)inboundInnerSession=received.session();
                if(!inboundInnerSession.equals(received.session()))throw new IOException("内层分块会话不一致");holder[0]=received;
            });
            ClipboardTransport.Received received=holder[0];
            if(received!=null)ApplicationManager.getApplication().invokeLater(()->{if(disposed)return;receiveCount.setText("会话 "+outerSession.substring(0,8)+" · "+received.received()+" / "+received.total()+" 块 · 最小缺块："+(received.firstMissing()==0?"无":received.firstMissing()));receiveProgress.setMaximum(received.total());receiveProgress.setValue(received.received());});
            if(received==null||received.payload()==null)return new TransferCoordinator.Outcome(blockAck,"接收中 · 已可靠落盘 "+(received==null?"":received.received()+" / "+received.total()+" 块"),false).replayable("WAIT_DATA_"+(message.index()+1));
            Payload payload=received.payload();String response;
            if(config.role()==Role.VM){
                if(!transferId.equals(received.session()))throw new IOException("WORKTREE 内层会话与邀请不匹配");
                response=service.recordWorktreeReceived(outerSession,id(),received.session(),received.total(),payload,config.identity(),key);
                showReceivedWorktree(received,payload);
                return new TransferCoordinator.Outcome(response,"已接收 "+received.received()+" / "+received.total()+" 块 · 已校验并保存；HOST 可继续本地开发",false).retrying("WAIT_RECEIVED_ACK");
            }
            if(payload.git()==null||!payload.git().snapshot().equals(transferId))throw new IOException("Bundle 与邀请中的待处理目标不匹配");
            try{response=service.receiveAndApplyAckFrame(outerSession,received.total(),id(),payload,config.identity(),key);}
            catch(IOException error){throw new IOException("已接收 "+received.received()+" / "+received.total()+" 块；HOST 应用或生成回执失败："+error.getMessage(),error);}
            int fileCount=payload.entries().size();String cleanupWarning;
            try{ClipboardTransport.clear(workspace,received.session());cleanupWarning="";}catch(IOException e){cleanupWarning="；接收缓存清理失败："+e.getMessage();}
            final String cleanup=cleanupWarning;
            incoming=null;ApplicationManager.getApplication().invokeLater(()->{receiveFiles.setText("已应用 · 包内文件 "+fileCount+" 个");message("应用成功，等待 VM 接受精确待处理包回执"+cleanup);updateEnabled();});
            return new TransferCoordinator.Outcome(response,"已接收 "+received.received()+" / "+received.total()+" 块 · HOST 已应用并持久化基线"+cleanup,false);
        }
        if(message.session().equals(outerSession)&&message.type()==ReliableTransferEngine.Type.FINAL){
            String response=config.role()==Role.VM?new ProjectTransferService(workspace,config).acceptWorktreeFinalAndPrepareAck(frame,config.identity(),key)
                    :new ProjectTransferService(workspace,config).acceptFinalAndPrepareAck(frame,config.identity(),key);
            new TransferSessionStore(workspace).clearSession(outerSession);
            if(config.role()==Role.VM){incoming=null;receiveChanges=List.of();ApplicationManager.getApplication().invokeLater(()->{if(disposed)return;fill(receiveModel,receiveChanges);receiveFiles.setText("WORKTREE 已可靠保存到本地收件缓存 · 可随时预览/应用");message("接收完成；待处理内容保留在磁盘，可本地预览或解决冲突");updateEnabled();});}
            recoverableFinalAvailable=true;return new TransferCoordinator.Outcome(response,TransferCompletionText.format("业务已完成",message.total())+"；最终回执由应用级轻量缓存有限重放",true).replayable("WAIT_FINAL_ACK");
        }
        if(message.session().equals(outerSession)&&message.scopeId().equals(id())&&message.transferId().equals(transferId)
                &&java.util.Set.of(ReliableTransferEngine.Type.READY,ReliableTransferEngine.Type.BLOCK_ACK,ReliableTransferEngine.Type.MISSING,ReliableTransferEngine.Type.RECEIVED,ReliableTransferEngine.Type.BUSINESS_ACK,ReliableTransferEngine.Type.FINAL_ACK).contains(message.type()))throw new TransferCoordinator.OwnFrame();
        throw new TransferCoordinator.UnprocessableFrame("当前接收状态不接受 "+message.type());
    }
    private void showReceivedWorktree(ClipboardTransport.Received result,Payload payload){
        incoming=payload;session=result.session();preview=null;receiveChanges=new ArrayList<>();
        for(Model.Entry entry:payload.entries())receiveChanges.add(new Change(entry.path(),entry.base(),entry.encoding()==Encoding.DELETE?null:entry.data()));
        ApplicationManager.getApplication().invokeLater(()->{if(disposed)return;contentLayout.show(tabs,"receive");fill(receiveModel,receiveChanges);receiveFiles.setText("已接收并校验 · 待本地查看 "+receiveChanges.size()+" 个变更");message("内容已可靠保存；可在 VM 本地预览或解决冲突");updateEnabled();});
    }
    private void previewOrApply(){if(preview==null&&(incoming!=null||session!=null)&&config.role()==Role.VM)previewPending();else apply();}
    private void previewPending(){
        Config cfg=config;String selected=id();Payload payload=incoming;String pendingSession=session;
        job("生成本地预览",()->{Payload restored=payload!=null?payload:new ProjectTransferService(workspace,cfg).loadCompletedWorktree(selected,pendingSession);return new SyncEngine(workspace,cfg).preview(selected,restored);},candidate->{
            if(candidate.conflicts.isEmpty()){showPreview(candidate);return;}
            busy=true;updateEnabled();message("等待解决 "+candidate.conflicts.size()+" 个冲突");
            MergeDialogs.resolve(project,candidate.conflicts,resolutions->{if(disposed)return;busy=false;job("生成合并预览",()->new SyncEngine(workspace,cfg).resolve(candidate,resolutions),this::showPreview);},
                    ()->{if(!disposed){busy=false;preview=null;message("已取消冲突解决；未修改业务文件");updateEnabled();}},failure->{if(!disposed){busy=false;error(failure);updateEnabled();}});
        });
    }
    private void receive(String text){
        if(text==null||text.isBlank()){error(new IllegalArgumentException("剪贴板中没有文本"));return;}
        FileDocumentManager.getInstance().saveAllDocuments();
        Config cfg=config;String selected=id();preview=null;incoming=null;receiveChanges=List.of();fill(receiveModel,receiveChanges);receiveFiles.setText("");
        job("接收并预览",()->new ProjectTransferService(workspace,cfg).receive(selected,text),prepared->{
            ClipboardTransport.Received result=prepared.received();
            session=result.session();incoming=result.payload();receiveCount.setText("会话 "+session.substring(0,8)+" · "+result.received()+" / "+result.total()+" 块 · 最小未粘贴块号："+(result.firstMissing()==0?"无（已收齐）":result.firstMissing()));
            receiveProgress.setMaximum(result.total());receiveProgress.setValue(result.received());
            receiveFiles.setText(incoming==null?"":"已接收 · 包内文件 "+incoming.entries().size()+" 个");
            SyncEngine.Preview candidate=prepared.preview();
            if(candidate!=null&&!candidate.conflicts.isEmpty()){
                busy=true;updateEnabled();message("等待解决 "+candidate.conflicts.size()+" 个冲突");
                MergeDialogs.resolve(project,candidate.conflicts,resolutions->{
                    if(disposed)return;busy=false;FileDocumentManager.getInstance().saveAllDocuments();
                    job("生成合并预览",()->new SyncEngine(workspace,cfg).resolve(candidate,resolutions),this::showPreview);
                },()->{if(!disposed){busy=false;preview=null;incoming=null;receiveChanges=List.of();fill(receiveModel,receiveChanges);message("已取消，本次未应用");updateEnabled();}},failure->{if(!disposed){busy=false;preview=null;updateEnabled();error(failure);}});
            }else showPreview(candidate);
        });
    }
    private void showPreview(SyncEngine.Preview candidate){
        preview=candidate;contentLayout.show(tabs,"receive");
        if(preview!=null){
            receiveChanges=preview.changes;
            fill(receiveModel,receiveChanges);
            receiveFiles.setText("已接收 · 包内文件 "+preview.payload.entries().size()+" 个 · 待应用 "+receiveChanges.size()+" 个");
        }
        message(preview==null?"接收中":"可应用");
    }
    private void apply(){
        if(preview==null)return;boolean host=config.role()==Role.HOST;
        String details="目标："+preview.root+"\n来源 HEAD："+preview.payload.head()
                +(host?"":"\n来源分支："+(preview.payload.sourceBranch().isEmpty()?"detached":preview.payload.sourceBranch()));
        if(!new TransferConfirmDialog(project,"确认应用",details).showAndGet())return;
        FileDocumentManager.getInstance().saveAllDocuments();Config cfg=config;SyncEngine.Preview accepted=preview;preview=null;
        String pendingSession=session,selectedProject=id();dev.codingtransfer.core.PendingWorktreeStore.Pending pendingPointer=null;try{if(cfg.role()==Role.VM&&pendingSession!=null)pendingPointer=new dev.codingtransfer.core.PendingWorktreeStore(workspace).read();}catch(IOException e){error(e);return;}
        final var expectedPending=pendingPointer;
        job("应用代码同步（请勿编辑文件或操作 Git）",()->{Path backup=new SyncEngine(workspace,cfg).apply(accepted);String warning="";if(cfg.role()==Role.VM&&pendingSession!=null){try{ClipboardTransport.clear(workspace,pendingSession);}catch(IOException e){warning="待处理内容已应用，但收件缓存未能清理："+e.getMessage();}try{if(expectedPending!=null&&expectedPending.projectId().equals(selectedProject)&&expectedPending.innerSession().equals(pendingSession))new dev.codingtransfer.core.PendingWorktreeStore(workspace).clear(expectedPending);}catch(IOException e){warning+="；待处理索引未能清理："+e.getMessage();}}return new ApplyResult(backup,warning);},result->{
            VirtualFileManager.getInstance().asyncRefresh(()->{});incoming=null;receiveChanges=List.of();fill(receiveModel,receiveChanges);
            outgoing=List.of();copied.clear();packet.setVisible(false);sendChanges=List.of();refRows=List.of();fill(sendModel,sendChanges);
            sendFiles.setText("");
            receiveFiles.setText("已接收 · 包内文件 "+accepted.payload.entries().size()+" 个 · 已应用 "+accepted.changes.size()+" 个");
            session=null;message(result.cleanupWarning().isEmpty()?"应用成功":"应用成功；"+result.cleanupWarning());
        },true);
    }
    private void copyReceipt(){
        Config cfg=config;String selected=id();job("生成成功接收回执",()->ClipboardTransport.split(new BundleSync(workspace,cfg).receipt(selected),cfg.chunkKb()),parts->{
            coordinator.writeClipboardTextAsync(parts.getFirst()).whenComplete((unused,failure)->ApplicationManager.getApplication().invokeLater(()->{if(failure!=null)error(failure);else message("回执已复制");}));
        });
    }
    private void readReceipt(){
        if(transferActive||pendingConfirmation||busy)return;
        Config cfg=config;String selected=id();coordinator.readClipboardTextAsync().whenComplete((text,failure)->ApplicationManager.getApplication().invokeLater(()->{
            if(failure!=null){error(failure);return;}if(text==null||text.isBlank()){error(new IllegalArgumentException("剪贴板中没有回执"));return;}
            job("接收回执",()->{
                ClipboardTransport.Received received=ClipboardTransport.receive(workspace,cfg,selected,text);
                Payload ack=received.payload();if(ack==null||ack.type()!=Type.ACK)throw new IllegalArgumentException("剪贴板中不是完整回执");
                new BundleSync(workspace,cfg).acceptReceipt(selected,ack);return true;
            },unused->message("回执已确认"));
        }));
    }
    private void clear(){
        if(transferActive||pendingConfirmation||busy||session==null)return;
        if(Messages.showYesNoDialog(project,"删除当前会话已保存的分块？不会删除代码、基线或备份。","清理收件箱",Messages.getQuestionIcon())!=Messages.YES)return;
        String selected=session,selectedProject=id();job("清理当前收件会话",()->{ClipboardTransport.clear(workspace,selected);var pending=new dev.codingtransfer.core.PendingWorktreeStore(workspace).read();if(pending!=null&&pending.projectId().equals(selectedProject)&&pending.innerSession().equals(selected))new dev.codingtransfer.core.PendingWorktreeStore(workspace).clear(pending);return true;},unused->{incoming=null;preview=null;session=null;receiveModel.setRowCount(0);receiveChanges=List.of();receiveFiles.setText("");receiveCount.setText("已清理当前会话");receiveProgress.setValue(0);message("当前会话分块已永久删除；需要时可重新发送。");});
    }
    private void clearSend(){
        if(transferActive||pendingConfirmation||busy)return;
        if(hasPendingVmBundle()){message("该 VM Bundle 已开始传输，需先恢复原自动传输");return;}
        if(Messages.showYesNoDialog(project,"清空发送分块和临时缓存？未复制的块需重新生成。同步基线及待确认回执保留。","清空发送会话",Messages.getQuestionIcon())!=Messages.YES)return;
        Set<String> caches=Set.copyOf(sendCaches);
        job("清理发送缓存",()->{for(String cache:caches)SafeFiles.clearOutgoing(workspace,"git-outgoing",cache);return true;},unused->{
            sendCaches.removeAll(caches);outgoing=List.of();outgoingPayload=null;copied.clear();part=0;sendChanges=List.of();refRows=List.of();sendModel.setRowCount(0);packet.setVisible(false);sendFiles.setText("");message("发送会话已清空");
        });
    }
    private void showDiff(List<Change> changes,int index,boolean sending){
        if(sending&&config.role()==Role.VM){
            if(index<0||index>=refRows.size())return;GitHistory.RefChange row=refRows.get(index);Config cfg=config;String selected=id();
            job("读取 "+row.ref()+" 文件差异",()->GitHistory.loadChange(cfg.root(workspace,cfg.project(selected)),cfg.git(),row),change->openDiff(change,"基线 / HEAD",row.working()?"working tree":"committed"));return;
        }
        if(index<0||index>=changes.size())return;Change c=changes.get(index);
        openDiff(c,sending?"VM 基线":"当前本地",sending?"待发送内容":"待应用内容");
    }
    private void openDiff(Change c,String leftTitle,String rightTitle){
        try{
            DiffContentFactory factory=DiffContentFactory.getInstance();DiffContent left=content(factory,c.before()),right=content(factory,c.after());
            SimpleDiffRequest request=new SimpleDiffRequest("Every Sync · "+c.path(),left,right,leftTitle,rightTitle);
            FileEditorManager.getInstance(project).openFile(new SimpleDiffVirtualFile(request),true);
        }catch(Exception e){error(e);}
    }
    private DiffContent content(DiffContentFactory factory,byte[] bytes){
        if(bytes==null)return factory.createEmpty();String text=bytes.length<=1024*1024?TextPatch.text(bytes):null;
        if(text==null)text="[二进制或大于 1 MiB 的文件，仅展示摘要]\n大小: "+bytes.length+" bytes\nSHA-256: "+Model.hash(bytes);
        return factory.create(project,text);
    }
    private void updateEnabled(){
        boolean ready=!busy&&config!=null&&projects.getSelectedItem()!=null;
        boolean foreignContext=false;if(ready)try{var context=new TransferSessionStore(workspace).currentContext();foreignContext=context!=null&&(context.kind()!=ReliableTransferEngine.Kind.PROJECT||!context.scopeId().equals(id()));if(config.role()==Role.VM){var marker=new dev.codingtransfer.core.PendingBundleTransferStore(workspace).read();foreignContext|=marker!=null&&!marker.projectId().equals(id());}}catch(IOException ignored){foreignContext=true;}
        boolean locked=transferActive||pendingConfirmation||foreignContext;boolean host=config!=null&&config.role()==Role.HOST;boolean pendingVmBundle=ready&&hasPendingVmBundle();
        settings.setEnabled(!busy&&!locked&&workspace!=null);records.setEnabled(!busy&&!locked&&workspace!=null);projects.setEnabled(ready&&!transferActive&&!pendingConfirmation);
        generate.setEnabled(ready&&!locked&&!pendingVmBundle);incremental.setEnabled(ready&&!locked&&!pendingVmBundle&&!host&&Files.exists(workspace.resolve(".vmsync/bundle-state/acknowledged").resolve(id()+".gz")));
        boolean hasPendingWorktree=false;if(ready&&!host)try{hasPendingWorktree=new dev.codingtransfer.core.PendingWorktreeStore(workspace).read()!=null;}catch(IOException ignored){}
        sendAuto.setEnabled(ready&&!locked&&!outgoing.isEmpty()&&outgoingPayload!=null);receiveAuto.setEnabled(ready&&!locked&&(host||(!pendingVmBundle&&!hasPendingWorktree&&incoming==null&&session==null)));
        cancelAuto.setVisible(transferActive);cancelAuto.setEnabled(transferActive);boolean detachedSend=hasDetachedBundleTransfer();continueAuto.setText(detachedSend?"恢复待发送":"继续确认");continueAuto.setVisible(pendingConfirmation||detachedSend);continueAuto.setEnabled(ready&&(pendingConfirmation||detachedSend));endAuto.setVisible(transferActive||pendingConfirmation);endAuto.setEnabled(ready&&(transferActive||pendingConfirmation));recoverFinal.setVisible(recoverableFinalAvailable&&!locked);recoverFinal.setEnabled(ready&&recoverableFinalAvailable&&!locked);
        clearSend.setEnabled(!busy&&!locked&&!pendingVmBundle&&workspace!=null&&(!outgoing.isEmpty()||!sendCaches.isEmpty()||!sendChanges.isEmpty()));
        prev.setEnabled(false);next.setEnabled(false);copy.setEnabled(false);paste.setEnabled(false);receipt.setEnabled(false);acceptReceipt.setEnabled(false);
        apply.setEnabled(ready&&!host&&(incoming!=null||session!=null));clear.setEnabled(ready&&!locked&&session!=null);
        if(!outgoing.isEmpty())partLabel.setText("第 "+(part+1)+" / "+outgoing.size()+" 块 · 已复制 "+copied.cardinality()+" / "+outgoing.size()+" · 最小未复制块号："+(copied.nextClearBit(0)>=outgoing.size()?"无":copied.nextClearBit(0)+1));
    }
    private <T> void job(String title,Callable<T> work,Consumer<T> success){
        job(title,work,success,false);
    }
    private <T> void job(String title,Callable<T> work,Consumer<T> success,boolean modal){
        if(busy||disposed)return;busy=true;updateEnabled();message(title+"…");
        class JobState {
            private T result;private Exception failure;
            void run(ProgressIndicator indicator){indicator.setIndeterminate(true);try{result=work.call();}catch(ProcessCanceledException e){throw e;}catch(Exception e){failure=e;}}
            void success(){if(disposed||project.isDisposed())return;busy=false;try{if(failure!=null)error(failure);else success.accept(result);}finally{updateEnabled();}}
            void error(Throwable e){if(!disposed){busy=false;updateEnabled();SyncPanel.this.error(e);}}
            void cancel(){if(!disposed){busy=false;updateEnabled();message("操作已取消");}}
        }
        JobState state=new JobState();
        if(modal)new Task.Modal(project,title,false){
            @Override public void run(ProgressIndicator i){state.run(i);}
            @Override public void onSuccess(){state.success();}
            @Override public void onThrowable(Throwable e){state.error(e);}
            @Override public void onCancel(){state.cancel();}
        }.queue();
        else new Task.Backgroundable(project,title,false){
            @Override public void run(ProgressIndicator i){state.run(i);}
            @Override public void onSuccess(){state.success();}
            @Override public void onThrowable(Throwable e){state.error(e);}
            @Override public void onCancel(){state.cancel();}
        }.queue();
    }
    @Override public void dispose(){coordinator.stop(this);disposed=true;incoming=null;preview=null;outgoing=List.of();outgoingPayload=null;sendChanges=List.of();receiveChanges=List.of();transferEngine=null;}
}
