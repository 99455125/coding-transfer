package dev.codingtransfer.plugin;

import dev.codingtransfer.core.Config;
import dev.codingtransfer.core.TransferLimits;
import com.intellij.openapi.project.Project;
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
    private final JTextField git=new JTextField();
    private final JTextField identity=new JTextField();
    private final JBTextArea pairingText=new JBTextArea(3,36);
    private JButton copyDiagnostics;
    private final PairingSecretService pairingSecrets;
    private final JSpinner chunk=new JSpinner(new SpinnerNumberModel(128,4,TransferLimits.MAX_CHUNK_KB,4));
    private final JBTextArea excludes=new JBTextArea(6,30);
    private Config result;
    SettingsDialog(Project project,Config config,IntConsumer saveChunk,Path workspace)throws java.io.IOException{
        super(project);setTitle("Every Sync · 项目映射设置");setOKButtonText("保存配置");setCancelButtonText("取消");
        pairingSecrets=new PairingSecretService(workspace);
        role.setSelectedItem(config.role().name());git.setText(config.git());identity.setText(config.identity());chunk.setValue(config.chunkKb());excludes.setText(String.join("\n",config.exclude()));
        for(Config.Mapping m:config.projects())model.addRow(new Object[]{m.id(),config.role()==dev.codingtransfer.core.Model.Role.HOST?m.local():m.vm()});init();
        JFormattedTextField input=((JSpinner.DefaultEditor)chunk.getEditor()).getTextField();
        input.setFocusLostBehavior(JFormattedTextField.COMMIT);
        input.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){
            try{chunk.commitEdit();saveChunk.accept(((Number)chunk.getValue()).intValue());setErrorText(null);}catch(Exception ex){setErrorText(ex.getMessage());}
        }});
    }
    @Override protected JComponent createCenterPanel(){
        JPanel root=new JPanel(new BorderLayout(0,JBUI.scale(10)));root.setPreferredSize(JBUI.size(780,490));
        JPanel top=new JPanel(new GridLayout(0,2,8,8));top.add(new JLabel("本端角色（只在这里设置）"));top.add(role);top.add(new JLabel("Git 可执行文件（两端使用）"));top.add(git);top.add(new JLabel("剪贴板分块大小 / KiB"));top.add(chunk);top.add(new JLabel("传输识别串（两端相同）"));top.add(identity);root.add(top,BorderLayout.NORTH);
        JPanel center=new JPanel(new BorderLayout(0,8));table.setRowHeight(JBUI.scale(28));center.add(new JBScrollPane(table),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));JButton add=new JButton("添加项目"),remove=new JButton("删除映射");
        add.addActionListener(e->model.addRow(new Object[]{"project-"+(model.getRowCount()+1),"project-"+(model.getRowCount()+1)}));
        remove.addActionListener(e->{int row=table.getSelectedRow();if(row>=0){if(table.isEditing())table.getCellEditor().cancelCellEditing();model.removeRow(row);}});buttons.add(add);buttons.add(remove);center.add(buttons,BorderLayout.SOUTH);root.add(center,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,6));
        JPanel pair=new JPanel(new BorderLayout(0,4));pair.add(new JLabel("配对配置含共享密钥；旧版密钥丢失时，从仍有效的一端复制当前配对，再在此导入并保存；若两端都丢失且无原配置，需手动生成一次新配对并导入两端。仅在明确点击复制后才写入剪贴板。"),BorderLayout.NORTH);pairingText.setLineWrap(true);pairingText.setWrapStyleWord(true);pair.add(new JBScrollPane(pairingText),BorderLayout.CENTER);
        JPanel pairButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));JButton generatePair=new JButton("生成并复制配对"),copyPair=new JButton("复制当前配对"),pastePair=new JButton("从剪贴板导入");copyDiagnostics=new JButton("复制诊断");copyDiagnostics.setEnabled(false);copyDiagnostics.addActionListener(e->{String value=PairingDiagnostic.copyText();if(value!=null)writeClipboard(value);});
        generatePair.addActionListener(e->{try{PairingSecretService.Pairing pairValue=pairingSecrets.generate();identity.setText(pairValue.identity());pairingText.setText(pairingSecrets.exportPairing(pairValue.identity(),pairValue.key()));writeClipboard(pairingText.getText());}catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}});
        copyPair.addActionListener(e->{try{String value=pairingText.getText().strip();if(value.isEmpty())value=pairingSecrets.exportPairing(identity.getText().strip(),pairingSecrets.load(identity.getText().strip()));writeClipboard(value);}catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}});
        pastePair.addActionListener(e->clipboard().readClipboardTextAsync().whenComplete((value,failure)->com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{try{if(failure!=null)throw new java.io.IOException("读取剪贴板失败",failure);if(value==null)throw new java.io.IOException("剪贴板没有文本");PairingSecretService.Pairing parsed=pairingSecrets.parsePairing(value);identity.setText(parsed.identity());pairingText.setText(pairingSecrets.exportPairing(parsed.identity(),parsed.key()));}catch(Exception ex){Messages.showErrorDialog(getContentPanel(),ex.getMessage(),"Every Sync");}})));
        pairButtons.add(generatePair);pairButtons.add(copyPair);pairButtons.add(pastePair);pairButtons.add(copyDiagnostics);pair.add(pairButtons,BorderLayout.SOUTH);bottom.add(pair,BorderLayout.NORTH);
        JPanel excludePanel=new JPanel(new BorderLayout(0,6));excludePanel.add(new JLabel("排除规则（每行一个；两端必须一致；.git / .vmsync 永远排除）"),BorderLayout.NORTH);excludePanel.add(new JBScrollPane(excludes),BorderLayout.CENTER);
        JPanel bottomFooter=new JPanel(new BorderLayout());bottomFooter.add(new JLabel("仅填写本端路径；相对路径以当前 IDEA 项目根目录为基准。两端按项目 ID 匹配。"),BorderLayout.SOUTH);excludePanel.add(bottomFooter,BorderLayout.SOUTH);
        JPanel south=new JPanel(new BorderLayout(0,8));south.add(excludePanel,BorderLayout.CENTER);
        south.add(bottom,BorderLayout.NORTH);root.add(south,BorderLayout.SOUTH);return root;
    }
    private TransferCoordinator clipboard(){return com.intellij.openapi.application.ApplicationManager.getApplication().getService(TransferCoordinator.class);}
    private void writeClipboard(String value){clipboard().writeClipboardTextAsync(value).whenComplete((unused,failure)->com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(()->{if(failure!=null)Messages.showErrorDialog(getContentPanel(),failure.getMessage()==null?failure.toString():failure.getMessage(),"Every Sync");}));}
    @Override protected void doOKAction(){
        if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return;
        PairingDiagnostic.clear();
        try{
            chunk.commitEdit();
            StringBuilder yaml=new StringBuilder("role: "+role.getSelectedItem()+"\nidentity: '"+identity.getText().strip()+"'\ngit: '"+git.getText().strip()+"'\nprojects:\n");
            for(int row=0;row<model.getRowCount();row++)yaml.append("  - id: ").append(cell(row,0)).append("\n    path: '").append(cell(row,1)).append("'\n");
            yaml.append("exclude:\n");for(String line:excludes.getText().split("\\R"))if(!line.isBlank())yaml.append("  - '").append(line.strip()).append("'\n");
            yaml.append("clipboard:\n  chunk-size-kb: ").append(chunk.getValue()).append('\n');result=Config.parse(yaml.toString());
            if(!pairingText.getText().isBlank()){
                PairingSecretService.Pairing pair=pairingSecrets.parsePairing(pairingText.getText());
                if(!pair.identity().equals(result.identity()))throw new java.io.IOException("配对配置识别串与设置中的识别串不一致");
                pairingSecrets.storeAndVerify(pair.identity(),pair.key());
                pairingText.setText("");
            }
            super.doOKAction();
        }catch(Exception e){setErrorText(e.getMessage());copyDiagnostics.setEnabled(PairingDiagnostic.copyText()!=null);}
    }
    private String cell(int row,int col){return String.valueOf(model.getValueAt(row,col)).strip();}
    Config result(){return result;}
}
