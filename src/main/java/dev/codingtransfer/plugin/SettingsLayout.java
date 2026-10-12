package dev.codingtransfer.plugin;

import javax.swing.*;
import java.awt.*;

/** Compact settings structure; collapsed editors retain their values. */
final class SettingsLayout {
    private SettingsLayout(){}
    static JPanel create(JComponent role,JComponent git,JComponent chunk,JComponent focus,JComponent identity,JComponent projects,JComponent pairing,JComponent excludes){
        JPanel root=new JPanel(new BorderLayout(0,8));
        JPanel top=new JPanel();top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));
        JPanel options=new JPanel(new GridBagLayout());
        String[] labels={"角色","Git","分块 / KiB","焦点 / ms"};JComponent[] fields={role,git,chunk,focus};
        for(int i=0;i<fields.length;i++){
            JPanel group=new JPanel(new BorderLayout(5,0));group.add(new JLabel(labels[i]),BorderLayout.WEST);group.add(fields[i],BorderLayout.CENTER);
            GridBagConstraints c=new GridBagConstraints();c.gridx=i;c.gridy=0;c.weightx=i==1?1:0;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(0,i==0?0:10,0,0);options.add(group,c);
        }
        top.add(options);top.add(Box.createVerticalStrut(8));
        JToggleButton pairToggle=new JToggleButton("配对 ▸");JPanel identityRow=new JPanel(new BorderLayout(8,0));identityRow.add(new JLabel("识别串"),BorderLayout.WEST);identityRow.add(identity,BorderLayout.CENTER);identityRow.add(pairToggle,BorderLayout.EAST);top.add(identityRow);
        pairing.setVisible(false);top.add(Box.createVerticalStrut(6));top.add(pairing);
        JToggleButton excludeToggle=new JToggleButton("排除规则 ▸");JPanel excludeRow=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));excludeRow.add(excludeToggle);top.add(Box.createVerticalStrut(6));top.add(excludeRow);excludes.setVisible(false);top.add(excludes);
        pairToggle.addActionListener(e->{pairing.setVisible(pairToggle.isSelected());pairToggle.setText(pairToggle.isSelected()?"配对 ▾":"配对 ▸");root.revalidate();root.repaint();});
        excludeToggle.addActionListener(e->{excludes.setVisible(excludeToggle.isSelected());excludeToggle.setText(excludeToggle.isSelected()?"排除规则 ▾":"排除规则 ▸");root.revalidate();root.repaint();});
        root.add(top,BorderLayout.NORTH);root.add(projects,BorderLayout.CENTER);root.add(new JLabel("填写本端路径；相对路径以当前项目为基准，两端按项目 ID 匹配。"),BorderLayout.SOUTH);return root;
    }
}
