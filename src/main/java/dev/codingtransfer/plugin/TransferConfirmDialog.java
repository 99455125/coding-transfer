package dev.codingtransfer.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import java.awt.*;

/** Compact transfer confirmation with a fixed Cancel / Confirm order on both platforms. */
final class TransferConfirmDialog extends DialogWrapper {
    private final String message;
    private JButton confirm;

    TransferConfirmDialog(Project project, String title, String message) {
        super(project);
        this.message = message;
        setTitle(title);
        setOKButtonText("确认");
        setCancelButtonText("取消");
        init();
        setResizable(false);
        getRootPane().setDefaultButton(confirm);
    }

    @Override protected JComponent createCenterPanel() {
        JBTextArea text = new JBTextArea(message);
        text.setEditable(false);
        text.setOpaque(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        FontMetrics metrics = text.getFontMetrics(text.getFont());
        int contentWidth = message.lines().mapToInt(metrics::stringWidth).max().orElse(0);
        int width = Math.max(JBUI.scale(220), Math.min(JBUI.scale(480), contentWidth + JBUI.scale(8)));
        text.setSize(new Dimension(width, Short.MAX_VALUE));
        text.setPreferredSize(new Dimension(width, text.getPreferredSize().height));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(8));
        panel.add(text, BorderLayout.CENTER);
        return panel;
    }

    @Override protected JComponent createSouthPanel() {
        // Keep both buttons in one preferred-width group; only the outer bar stretches.
        JPanel buttons = new JPanel(new GridLayout(1, 2, JBUI.scale(8), 0));
        buttons.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        buttons.add(new JButton(getCancelAction()));
        confirm = new JButton(getOKAction());
        buttons.add(confirm);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(JBUI.Borders.empty(0, 8, 0, 8));
        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }
}
