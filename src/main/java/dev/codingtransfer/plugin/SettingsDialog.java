package dev.codingtransfer.plugin;

import dev.codingtransfer.core.Config;
import dev.codingtransfer.core.TransferLimits;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;
import java.util.function.IntConsumer;

final class SettingsDialog extends DialogWrapper {
    private final JComboBox<String> role=new JComboBox<>(new String[]{"HOST","VM"});
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"项目 ID","本端路径（相对或绝对路径）"},0);
    private final JBTable table=new JBTable(model);
    private final JTextField git=new JTextField(10);
    private final JTextField identity=new JTextField();
    private final JBTextArea pairingText=new JBTextArea(3,36);
    private JButton copyDiagnostics;
    private final PairingSecretService pairingSecrets;
    private final JSpinner chunk=new JSpinner(new SpinnerNumberModel(128,4,TransferLimits.MAX_CHUNK_KB,4));
    private final JSpinner focus=new JSpinner(new SpinnerNumberModel(Config.DEFAULT_FOCUS_DELAY_MS,Config.MIN_FOCUS_DELAY_MS,Config.MAX_FOCUS_DELAY_MS,50));
    private final JBTextArea excludes=new JBTextArea(4,30);
    private Config result;
    SettingsDialog(Project project,Config config,IntConsumer saveChunk,Path workspace)throws java.io.IOException{
        super(project);setTitle("Every Sync · 项目映射设置");setOKButtonText("保存配置");setCancelButtonText("取消");
        pairingSecrets=new PairingSecretService(workspace);
        role.setSelectedItem(config.role().name());git.setText(config.git());identity.setText(config.identity());chunk.setValue(config.chunkKb());focus.setValue(config.focusDelayMs());excludes.setText(String.join("\n",config.exclude()));
        for(Config.Mapping m:config.projects())model.addRow(new Object[]{m.id(),config.role()==dev.codingtransfer.core.Model.Role.HOST?m.local():m.vm()});init();
        pairingText.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){try{importPairingText(pairingSecrets,pairingText.getText(),identity);setErrorText(null);}catch(java.io.IOException ex){setErrorText(ex.getMessage());}}});
        JFormattedTextField input=((JSpinner.DefaultEditor)chunk.getEditor()).getTextField();
        input.setFocusLostBehavior(JFormattedTextField.COMMIT);
        input.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){
            try{chunk.commitEdit();saveChunk.accept(((Number)chunk.getValue()).intValue());setErrorText(null);}catch(Exception ex){setErrorText(ex.getMessage());}
        }});
    }
    @Override protected JComponent createCenterPanel(){
        ((JSpinner.DefaultEditor)chunk.getEditor()).getTextField().setColumns(6);((JSpinner.DefaultEditor)focus.getEditor()).getTextField().setColumns(5);
        focus.setToolTipText("写入剪贴板后的首次焦点延时；仅 HOST 生效，切回后观察至少 3 秒");
        JPanel center=new JPanel(new BorderLayout(0,8));table.setRowHeight(JBUI.scale(28));center.add(new JBScrollPane(table),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));JButton add=new JButton("添加项目"),remove=new JButton("删除映射");
        add.addActionListener(e->model.addRow(new Object[]{"project-"+(model.getRowCount()+1),"project-"+(model.getRowCount()+1)}));
        remove.addActionListener(e->{int row=table.getSelectedRow();if(row>=0){if(table.isEditing())table.getCellEditor().cancelCellEditing();model.removeRow(row);}});buttons.add(add);buttons.add(remove);center.add(buttons,BorderLayout.SOUTH);
        JPanel pair=new JPanel(new BorderLayout(0,4));pair.add(new JLabel("完整三行配对，含密钥；识别串自动提取，保存后生效。"),BorderLayout.NORTH);pairingText.setLineWrap(true);pairingText.setWrapStyleWord(true);pair.add(new JBScrollPane(pairingText),BorderLayout.CENTER);
        JPanel pairButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));JButton generatePair=new JButton("生成并复制配对"),copyPair=new JButton("复制当前配对"),pastePair=new JButton("从剪贴板导入");copyDiagnostics=new JButton("复制诊断");copyDiagnostics.setEnabled(PairingDiagnostic.copyText()!=null);copyDiagnostics.addActionListener(e->{String value=PairingDiagnostic.copyText();if(value!=null)writeClipboard(value);});
        generatePair.addActionListener(e->{try{PairingSecretService.Pairing pairValue=pairingSecrets.generate();identity.setText(pairValue.identity());pairingText.setText(pairingSecrets.exportPairing(pairValue.identity(),pairValue.key()));writeClipboard(pairingText.getText());}catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}});
        copyPair.addActionListener(e->{try{String value=pairingText.getText().strip();if(value.isEmpty())value=pairingSecrets.exportPairing(identity.getText().strip(),pairingSecrets.load(identity.getText().strip()));writeClipboard(value);}catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}});
        pastePair.addActionListener(e->{
            ModalityState modality=ModalityState.current();pastePair.setEnabled(false);pastePair.setText("正在读取…");
            clipboard().readClipboardTextAsync().whenComplete((value,failure)->com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{
                if(isDisposed())return;
                try{if(failure!=null)throw new java.io.IOException("读取剪贴板失败："+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()),failure);if(value==null||value.isBlank())throw new java.io.IOException("剪贴板没有文本");PairingSecretService.Pairing parsed=importPairingText(pairingSecrets,value,identity);pairingText.setText(pairingSecrets.exportPairing(parsed.identity(),parsed.key()));setErrorText(null);}
                catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}
                finally{pastePair.setEnabled(true);pastePair.setText("从剪贴板导入");}
            },modality));
        });
        pairButtons.add(generatePair);pairButtons.add(copyPair);pairButtons.add(pastePair);pairButtons.add(copyDiagnostics);pair.add(pairButtons,BorderLayout.SOUTH);
        JPanel excludePanel=new JPanel(new BorderLayout(0,6));excludePanel.add(new JLabel("每行一个，两端一致；.git / .vmsync 永远排除。"),BorderLayout.NORTH);excludePanel.add(new JBScrollPane(excludes),BorderLayout.CENTER);
        JPanel root=SettingsLayout.create(role,git,chunk,focus,identity,center,pair,excludePanel);root.setPreferredSize(JBUI.size(780,490));return root;
    }
    private TransferCoordinator clipboard(){return com.intellij.openapi.application.ApplicationManager.getApplication().getService(TransferCoordinator.class);}
    private void writeClipboard(String value){ModalityState modality=ModalityState.current();clipboard().writeClipboardTextAsync(value).whenComplete((unused,failure)->com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{if(!isDisposed()&&failure!=null)Messages.showErrorDialog(getContentPanel(),failure.getMessage()==null?failure.toString():failure.getMessage(),"Every Sync");},modality));}
    static PairingSecretService.Pairing importPairingText(PairingSecretService service,String text,JTextField identity)throws java.io.IOException{
        if(text.isBlank())return null;
        PairingSecretService.Pairing pair=service.parsePairing(text);identity.setText(pair.identity());return pair;
    }
    @Override protected void doOKAction(){
        if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return;
        PairingDiagnostic.clear();
        try{
            chunk.commitEdit();focus.commitEdit();
            PairingSecretService.Pairing pair=importPairingText(pairingSecrets,pairingText.getText(),identity);
            StringBuilder yaml=new StringBuilder("role: "+role.getSelectedItem()+"\nidentity: '"+identity.getText().strip()+"'\ngit: '"+git.getText().strip()+"'\nprojects:\n");
            for(int row=0;row<model.getRowCount();row++)yaml.append("  - id: ").append(cell(row,0)).append("\n    path: '").append(cell(row,1)).append("'\n");
            yaml.append("exclude:\n");for(String line:excludes.getText().split("\\R"))if(!line.isBlank())yaml.append("  - '").append(line.strip()).append("'\n");
            yaml.append("clipboard:\n  chunk-size-kb: ").append(chunk.getValue()).append("\n  focus-delay-ms: ").append(focus.getValue()).append('\n');result=Config.parse(yaml.toString());
            if(pair!=null){
                pairingSecrets.storeAndVerify(pair.identity(),pair.key());
                pairingText.setText("");
            }
            super.doOKAction();
        }catch(Exception e){setErrorText(e.getMessage());copyDiagnostics.setEnabled(PairingDiagnostic.copyText()!=null);}
    }
    private String cell(int row,int col){return String.valueOf(model.getValueAt(row,col)).strip();}
    Config result(){return result;}
}
