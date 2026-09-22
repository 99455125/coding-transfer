package dev.codingtransfer.plugin;

import dev.codingtransfer.core.*;
import dev.codingtransfer.core.Model.*;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.SimpleDiffVirtualFile;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.ide.CopyPasteManager;
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
import java.awt.datatransfer.*;
import java.awt.event.*;
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
    private final JBTextArea status=note(""),sendStatus=note(""),receiveStatus=note("");
    private final JLabel partLabel=new JLabel("尚未生成"),receiveCount=new JLabel("等待接收");
    private final JTabbedPane tabs=new JTabbedPane();
    private final JButton generate=new JButton("生成传输"),prev=new JButton("上一块"),copy=new JButton("复制"),next=new JButton("下一块");
    private final JButton incremental=new JButton("生成增量"),receipt=new JButton("发送回执"),acceptReceipt=new JButton("接收回执"),clearSend=new JButton("清空会话");
    private final Set<String> sendCaches=new HashSet<>();
    private final JButton paste=new JButton("读取剪贴板"),apply=new JButton("应用变更"),clear=new JButton("清空会话");
    private final JProgressBar receiveProgress=new JProgressBar();
    private final DefaultTableModel sendModel=tableModel(),receiveModel=tableModel();
    private final JBTable sendTable=new JBTable(sendModel),receiveTable=new JBTable(receiveModel);
    private final JPanel packet=new JPanel();
    private List<Change> sendChanges=List.of(),receiveChanges=List.of();
    private List<String> outgoing=List.of();private int part;
    private Payload incoming;private String session;private SyncEngine.Preview preview;

    public SyncPanel(Project project){
        super(new BorderLayout());this.project=project;setBorder(JBUI.Borders.empty(12));
        JPanel header=vertical();header.add(row(new JLabel("项目"),projects,roleLabel,settings,records));add(header,BorderLayout.NORTH);
        JPanel send=new JPanel(new BorderLayout()),sendTop=vertical();sendTop.add(row(generate,incremental,acceptReceipt,clearSend));sendTop.add(sendStatus);
        packet.setLayout(new BoxLayout(packet,BoxLayout.Y_AXIS));packet.add(partLabel);packet.add(row(prev,copy,next));packet.setVisible(false);sendTop.add(packet);
        send.add(sendTop,BorderLayout.NORTH);send.add(new JBScrollPane(sendTable),BorderLayout.CENTER);
        JPanel recv=new JPanel(new BorderLayout()),recvTop=vertical();recvTop.add(row(paste,apply,receipt,clear));recvTop.add(receiveStatus);recvTop.add(receiveCount);recvTop.add(receiveProgress);
        recv.add(recvTop,BorderLayout.NORTH);recv.add(new JBScrollPane(receiveTable),BorderLayout.CENTER);
        tabs.addTab("发送",send);tabs.addTab("接收",recv);add(tabs,BorderLayout.CENTER);
        for(JBTable table:List.of(sendTable,receiveTable)){
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setRowHeight(JBUI.scale(28));table.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(38));
            table.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(!busy && table.getSelectedRow()>=0)showDiff(table==sendTable?sendChanges:receiveChanges,table.getSelectedRow(),table==sendTable);}});
            table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"diff");table.getActionMap().put("diff",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){if(!busy)showDiff(table==sendTable?sendChanges:receiveChanges,table.getSelectedRow(),table==sendTable);}});
        }
        settings.addActionListener(e->settings());records.addActionListener(e->{try{RevealFileAction.openDirectory(SafeFiles.state(workspace));}catch(Exception ex){error(ex);}});
        projects.addActionListener(e->{if(!loading&&!busy){reset();labels();}});
        generate.addActionListener(e->generate(false));acceptReceipt.addActionListener(e->readReceipt());
        incremental.addActionListener(e->generate(true));receipt.addActionListener(e->copyReceipt());
        prev.addActionListener(e->{part--;updateEnabled();});next.addActionListener(e->{part++;updateEnabled();});
        copy.addActionListener(e->{try{CopyPasteManager.getInstance().setContents(new StringSelection(outgoing.get(part)));int sent=part;copied.set(part);part=Math.min(copied.nextClearBit(0),outgoing.size()-1);updateEnabled();message("已复制第 "+(sent+1)+" / "+outgoing.size()+" 块");}catch(Exception ex){error(ex);}});
        paste.addActionListener(e->{try{String text=CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);receive(text);}catch(Exception ex){error(ex);}});
        apply.addActionListener(e->apply());clear.addActionListener(e->clear());clearSend.addActionListener(e->clearSend());
        updateEnabled();
        if(project.getBasePath()==null){message("请打开一个本地 IDEA 项目目录");return;}
        job("加载 Every Sync 配置",()->{
            workspace=Path.of(project.getBasePath()).toRealPath();Path file=workspace.resolve(".vmsync/config.yaml");SafeFiles.noLinks(file);
            return Files.exists(file)?Config.parse(new String(SafeFiles.read(file,64*1024),StandardCharsets.UTF_8)):null;
        },loaded->{if(loaded!=null)setConfig(loaded);else message("首次使用：打开设置，选择 HOST / VM 并配置项目映射");});
    }
    private static JPanel vertical(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));return p;}
    private static JPanel row(Component... components){JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(6),JBUI.scale(6)));for(Component c:components)p.add(c);return p;}
    private static JBTextArea note(String text){JBTextArea area=new JBTextArea(text);area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setOpaque(false);area.setBorder(JBUI.Borders.empty(6,0));return area;}
    private static DefaultTableModel tableModel(){return new DefaultTableModel(new Object[]{"","文件路径"},0){@Override public boolean isCellEditable(int r,int c){return false;}};}
    private String id(){Config.Mapping m=(Config.Mapping)projects.getSelectedItem();return m==null?"":m.id();}
    private void message(String text){status.setText(text);(tabs.getSelectedIndex()==1?receiveStatus:sendStatus).setText(text);}
    private void error(Throwable e){message(e.getMessage()==null?e.toString():e.getMessage());Messages.showErrorDialog(project,status.getText(),"Every Sync");}
    private void reset(){outgoing=List.of();copied.clear();refRows=List.of();part=0;incoming=null;preview=null;session=null;sendChanges=List.of();receiveChanges=List.of();sendModel.setRowCount(0);receiveModel.setRowCount(0);packet.setVisible(false);receiveCount.setText("等待接收");receiveProgress.setValue(0);sendStatus.setText("");receiveStatus.setText("");updateEnabled();}
    private void labels(){
        if(config==null)return;roleLabel.setText(config.role().name());boolean host=config.role()==Role.HOST;
        generate.setText(host?"生成传输":"生成全量");incremental.setVisible(!host);
        receipt.setVisible(host);acceptReceipt.setVisible(!host);
    }
    private void setConfig(Config value){String previous=id();loading=true;config=value;projects.removeAllItems();for(Config.Mapping m:value.projects()){projects.addItem(m);if(m.id().equals(previous))projects.setSelectedItem(m);}loading=false;reset();labels();sendStatus.setText("");receiveStatus.setText("");}
    private void settings(){try{Config defaults=config==null?Config.parse(Config.TEMPLATE):config;SettingsDialog dialog=new SettingsDialog(project,defaults,size->{
        Config current=config==null?defaults:config;if(size==current.chunkKb())return;
        Config changed=new Config(current.role(),current.projects(),current.exclude(),size,current.git());
        try{SafeFiles.atomicWrite(SafeFiles.state(workspace).resolve("config.yaml"),changed.yaml().getBytes(StandardCharsets.UTF_8));setConfig(changed);}catch(Exception ex){error(ex);}
    });if(dialog.showAndGet())saveConfig(dialog.result());}catch(Exception e){error(e);}}
    private void saveConfig(Config value){
        job("保存 Every Sync 配置",()->{Path file=SafeFiles.state(workspace).resolve("config.yaml");SafeFiles.atomicWrite(file,value.yaml().getBytes(StandardCharsets.UTF_8));return value;},this::setConfig);
    }
    private void fill(DefaultTableModel model,List<Change> changes){model.setRowCount(0);for(Change c:changes)model.addRow(new Object[]{c.status(),c.path()});}
    private void generate(boolean delta){
        if(config.role()==Role.VM&&Messages.showYesNoDialog(project,"传输包含整个 refs 可达历史，包括排除规则过滤的历史文件。新传输会替换待确认回执目标。确认允许传输？","生成"+(delta?"增量":"全量"),Messages.getWarningIcon())!=Messages.YES)return;
        FileDocumentManager.getInstance().saveAllDocuments();Config cfg=config;String selected=id();
        // Clear the previous package before scanning, so failure cannot leave a stale copy action enabled.
        outgoing=List.of();copied.clear();packet.setVisible(false);
        job("生成传输",()->{SyncEngine.Generated result=cfg.role()==Role.HOST?new SyncEngine(workspace,cfg).generate(selected):new BundleSync(workspace,cfg).generate(selected,delta);List<String> parts=ClipboardTransport.split(result.payload(),cfg.chunkKb());return new SendResult(result,parts,cfg.role()==Role.VM?new BundleSync(workspace,cfg).refChanges(selected):List.of());},result->{
            sendChanges=result.generated.changes();refRows=result.refs;fill(sendModel,sendChanges);if(cfg.role()==Role.VM){sendModel.setRowCount(0);for(GitHistory.RefChange row:refRows)sendModel.addRow(new Object[]{row.status(),row.label()});}
            if(result.generated.payload().git()!=null)sendCaches.add(result.generated.payload().git().snapshot());
            outgoing=result.parts;part=0;packet.setVisible(!outgoing.isEmpty());message("已生成 "+outgoing.size()+" 块");
        });
    }
    private record SendResult(SyncEngine.Generated generated,List<String> parts,List<GitHistory.RefChange> refs){}
    private void receive(String text){
        if(text==null||text.isBlank()){error(new IllegalArgumentException("剪贴板中没有文本"));return;}
        FileDocumentManager.getInstance().saveAllDocuments();
        Config cfg=config;String selected=id();preview=null;incoming=null;receiveChanges=List.of();fill(receiveModel,receiveChanges);
        job("接收并预览",()->{
            ClipboardTransport.Received received=ClipboardTransport.receive(workspace,cfg,selected,text);
            if(received.payload()!=null&&received.payload().type()==Type.ACK)throw new IllegalArgumentException("请在发送页接收回执");
            return new ReceiveResult(received,received.payload()==null?null:new SyncEngine(workspace,cfg).preview(selected,received.payload()));
        },prepared->{
            ClipboardTransport.Received result=prepared.received();
            session=result.session();incoming=result.payload();receiveCount.setText("会话 "+session.substring(0,8)+" · "+result.received()+" / "+result.total()+" 块 · 最小未粘贴块号："+(result.firstMissing()==0?"无（已收齐）":result.firstMissing()));
            receiveProgress.setMaximum(result.total());receiveProgress.setValue(result.received());
            preview=prepared.preview();if(preview!=null){receiveChanges=preview.changes;fill(receiveModel,receiveChanges);}
            message(preview==null?"接收中":"可应用 · "+receiveChanges.size()+" 个文件变化");
        });
    }
    private record ReceiveResult(ClipboardTransport.Received received,SyncEngine.Preview preview){}
    private void apply(){
        if(preview==null)return;boolean host=config.role()==Role.HOST;
        ConfirmDialog dialog=new ConfirmDialog(project,host,preview.root,preview.changes.size(),preview.payload);if(!dialog.showAndGet())return;
        FileDocumentManager.getInstance().saveAllDocuments();Config cfg=config;SyncEngine.Preview accepted=preview;preview=null;
        job("应用代码同步（请勿编辑文件或操作 Git）",()->new SyncEngine(workspace,cfg).apply(accepted),backup->{
            VirtualFileManager.getInstance().asyncRefresh(()->{});incoming=null;receiveChanges=List.of();fill(receiveModel,receiveChanges);
            outgoing=List.of();copied.clear();packet.setVisible(false);sendChanges=List.of();refRows=List.of();fill(sendModel,sendChanges);
            message("应用成功");
        },true);
    }
    private void copyReceipt(){
        Config cfg=config;String selected=id();job("生成成功接收回执",()->ClipboardTransport.split(new BundleSync(workspace,cfg).receipt(selected),cfg.chunkKb()),parts->{
            CopyPasteManager.getInstance().setContents(new StringSelection(parts.getFirst()));message("回执已复制");
        });
    }
    private void readReceipt(){
        try{
            String text=CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
            if(text==null||text.isBlank())throw new IllegalArgumentException("剪贴板中没有回执");
            Config cfg=config;String selected=id();
            job("接收回执",()->{
                ClipboardTransport.Received received=ClipboardTransport.receive(workspace,cfg,selected,text);
                Payload ack=received.payload();
                if(ack==null||ack.type()!=Type.ACK)throw new IllegalArgumentException("剪贴板中不是完整回执");
                new BundleSync(workspace,cfg).acceptReceipt(selected,ack);return true;
            },unused->message("回执已确认"));
        }catch(Exception e){error(e);}
    }
    private void clear(){
        if(session==null)return;
        if(Messages.showYesNoDialog(project,"删除当前会话已保存的分块？不会删除代码、基线或备份。","清理收件箱",Messages.getQuestionIcon())!=Messages.YES)return;
        String selected=session;job("清理当前收件会话",()->{ClipboardTransport.clear(workspace,selected);return true;},unused->{incoming=null;preview=null;session=null;receiveModel.setRowCount(0);receiveChanges=List.of();receiveCount.setText("已清理当前会话");receiveProgress.setValue(0);message("当前会话分块已永久删除；需要时可重新发送。");});
    }
    private void clearSend(){
        if(Messages.showYesNoDialog(project,"清空发送分块和临时缓存？未复制的块需重新生成。同步基线及待确认回执保留。","清空发送会话",Messages.getQuestionIcon())!=Messages.YES)return;
        Set<String> caches=Set.copyOf(sendCaches);
        job("清理发送缓存",()->{for(String cache:caches)SafeFiles.clearOutgoing(workspace,"git-outgoing",cache);return true;},unused->{
            sendCaches.removeAll(caches);outgoing=List.of();copied.clear();part=0;sendChanges=List.of();refRows=List.of();sendModel.setRowCount(0);packet.setVisible(false);sendStatus.setText("发送会话已清空");
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
        settings.setEnabled(!busy&&workspace!=null);records.setEnabled(!busy&&workspace!=null);projects.setEnabled(ready);
        for(JButton button:List.of(generate,incremental,paste))button.setEnabled(ready);
        receipt.setEnabled(ready&&config.role()==Role.HOST);acceptReceipt.setEnabled(ready&&config.role()==Role.VM);
        clearSend.setEnabled(!busy&&workspace!=null&&(!outgoing.isEmpty()||!sendCaches.isEmpty()||!sendChanges.isEmpty()));
        prev.setEnabled(ready&&!outgoing.isEmpty()&&part>0);next.setEnabled(ready&&!outgoing.isEmpty()&&part<outgoing.size()-1);copy.setEnabled(ready&&!outgoing.isEmpty());
        apply.setEnabled(ready&&preview!=null);clear.setEnabled(ready&&session!=null);
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
    @Override public void dispose(){disposed=true;incoming=null;preview=null;outgoing=List.of();sendChanges=List.of();receiveChanges=List.of();}
    private static final class ConfirmDialog extends DialogWrapper{
        private final boolean host;private final Path root;private final int count;private final Payload payload;
        ConfirmDialog(Project p,boolean host,Path root,int count,Payload payload){super(p);this.host=host;this.root=root;this.count=count;this.payload=payload;setTitle("确认应用 · "+count+" 个文件");setOKButtonText("确认应用");setCancelButtonText("取消");init();setOKActionEnabled(false);}
        @Override protected JComponent createCenterPanel(){
            JPanel p=vertical();p.setPreferredSize(JBUI.size(540,310));p.add(note("目标："+root+"\nVM HEAD："+payload.head()+"\n"+(host?"按 VM 全量基线 + 增量还原完整项目；所有未排除本地修改作废（包括不在增量清单中的修改），额外文件删除。Bundle 同步还会对齐内层 Git HEAD 和暂存区，历史保留在内层仓库；不操作外层仓库。":"重新校验 VM HEAD 和目标文件基线；不匹配则整包拒绝。")+"\n应用前保存备份。请暂停其他编辑、构建和 Git 操作。"));
            JCheckBox agree=new JCheckBox(host?"我确认以 VM 快照覆盖本地项目":"我确认将修改写入 VM 工作区（不提交）");agree.addActionListener(e->setOKActionEnabled(agree.isSelected()));p.add(agree);return p;
        }
    }
}
