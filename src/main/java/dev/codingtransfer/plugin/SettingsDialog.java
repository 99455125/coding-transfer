package dev.codingtransfer.plugin;

import dev.codingtransfer.core.Config;
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
import java.util.function.IntConsumer;

final class SettingsDialog extends DialogWrapper {
    private final JComboBox<String> role=new JComboBox<>(new String[]{"HOST","VM"});
    private final DefaultTableModel model=new DefaultTableModel(new Object[]{"项目 ID","本端路径（相对或绝对路径）"},0);
    private final JBTable table=new JBTable(model);
    private final JTextField git=new JTextField();
    private final JSpinner chunk=new JSpinner(new SpinnerNumberModel(128,4,4096,4));
    private final JBTextArea excludes=new JBTextArea(6,30);
    private Config result;
    SettingsDialog(Project project,Config config,IntConsumer saveChunk){
        super(project);setTitle("Every Sync · 项目映射设置");setOKButtonText("保存配置");setCancelButtonText("取消");
        role.setSelectedItem(config.role().name());git.setText(config.git());chunk.setValue(config.chunkKb());excludes.setText(String.join("\n",config.exclude()));
        for(Config.Mapping m:config.projects())model.addRow(new Object[]{m.id(),config.role()==dev.codingtransfer.core.Model.Role.HOST?m.local():m.vm()});init();
        JFormattedTextField input=((JSpinner.DefaultEditor)chunk.getEditor()).getTextField();
        input.setFocusLostBehavior(JFormattedTextField.COMMIT);
        input.addFocusListener(new FocusAdapter(){@Override public void focusLost(FocusEvent e){
            try{chunk.commitEdit();saveChunk.accept(((Number)chunk.getValue()).intValue());setErrorText(null);}catch(Exception ex){setErrorText(ex.getMessage());}
        }});
    }
    @Override protected JComponent createCenterPanel(){
        JPanel root=new JPanel(new BorderLayout(0,JBUI.scale(10)));root.setPreferredSize(JBUI.size(780,490));
        JPanel top=new JPanel(new GridLayout(0,2,8,8));top.add(new JLabel("本端角色（只在这里设置）"));top.add(role);top.add(new JLabel("Git 可执行文件（两端使用）"));top.add(git);top.add(new JLabel("剪贴板分块大小 / KiB"));top.add(chunk);root.add(top,BorderLayout.NORTH);
        JPanel center=new JPanel(new BorderLayout(0,8));table.setRowHeight(JBUI.scale(28));center.add(new JBScrollPane(table),BorderLayout.CENTER);
        JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));JButton add=new JButton("添加项目"),remove=new JButton("删除映射");
        add.addActionListener(e->model.addRow(new Object[]{"project-"+(model.getRowCount()+1),"project-"+(model.getRowCount()+1)}));
        remove.addActionListener(e->{int row=table.getSelectedRow();if(row>=0){if(table.isEditing())table.getCellEditor().cancelCellEditing();model.removeRow(row);}});buttons.add(add);buttons.add(remove);center.add(buttons,BorderLayout.SOUTH);root.add(center,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,6));bottom.add(new JLabel("排除规则（每行一个；两端必须一致；.git / .vmsync 永远排除）"),BorderLayout.NORTH);bottom.add(new JBScrollPane(excludes),BorderLayout.CENTER);
        bottom.add(new JLabel("仅填写本端路径；相对路径以当前 IDEA 项目根目录为基准。两端按项目 ID 匹配。"),BorderLayout.SOUTH);root.add(bottom,BorderLayout.SOUTH);return root;
    }
    @Override protected void doOKAction(){
        if(table.isEditing()&&!table.getCellEditor().stopCellEditing())return;
        try{
            chunk.commitEdit();
            StringBuilder yaml=new StringBuilder("role: "+role.getSelectedItem()+"\ngit: '"+git.getText().strip()+"'\nprojects:\n");
            for(int row=0;row<model.getRowCount();row++)yaml.append("  - id: ").append(cell(row,0)).append("\n    path: '").append(cell(row,1)).append("'\n");
            yaml.append("exclude:\n");for(String line:excludes.getText().split("\\R"))if(!line.isBlank())yaml.append("  - '").append(line.strip()).append("'\n");
            yaml.append("clipboard:\n  chunk-size-kb: ").append(chunk.getValue()).append('\n');result=Config.parse(yaml.toString());super.doOKAction();
        }catch(Exception e){setErrorText(e.getMessage());}
    }
    private String cell(int row,int col){return String.valueOf(model.getValueAt(row,col)).strip();}
    Config result(){return result;}
}
