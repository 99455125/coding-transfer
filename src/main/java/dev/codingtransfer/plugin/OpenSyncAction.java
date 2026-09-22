package dev.codingtransfer.plugin;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.wm.*;

public final class OpenSyncAction extends DumbAwareAction {
    @Override public void actionPerformed(AnActionEvent event) {
        if(event.getProject()!=null){ToolWindow w=ToolWindowManager.getInstance(event.getProject()).getToolWindow("Every Sync");if(w!=null)w.activate(null);}
    }
    @Override public void update(AnActionEvent event){event.getPresentation().setEnabledAndVisible(event.getProject()!=null);}
    @Override public ActionUpdateThread getActionUpdateThread(){return ActionUpdateThread.BGT;}
}
