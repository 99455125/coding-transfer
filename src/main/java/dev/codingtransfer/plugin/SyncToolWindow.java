package dev.codingtransfer.plugin;

import com.intellij.openapi.project.*;
import com.intellij.openapi.wm.*;
import com.intellij.ui.content.Content;
import com.intellij.ui.components.JBTabbedPane;

public final class SyncToolWindow implements ToolWindowFactory, DumbAware {
    @Override public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        SyncPanel panel = new SyncPanel(project);
        FileTransferPanel files = new FileTransferPanel(project);
        JBTabbedPane pages = new JBTabbedPane();pages.addTab("项目传输",panel);pages.addTab("文件传输",files);
        Content content = toolWindow.getContentManager().getFactory().createContent(pages, "", false);
        content.setDisposer(()->{panel.dispose();files.dispose();});toolWindow.getContentManager().addContent(content);
    }
}
