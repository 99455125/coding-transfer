package dev.codingtransfer.plugin;

import com.intellij.ide.actions.RevealFileAction;
import com.intellij.openapi.project.Project;
import dev.codingtransfer.core.SafeFiles;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Shared project/file transfer logging controls. Call while building the menu so state is refreshed. */
final class TransferLogMenu {
    private TransferLogMenu() {}

    static void add(JPopupMenu menu, Project project, Path workspace, TransferCoordinator coordinator,
                    Consumer<String> status, Consumer<Throwable> error) {
        JCheckBoxMenuItem enabled = new JCheckBoxMenuItem("记录传输日志（排查用）",
                TransferLogSettings.enabled(project, workspace));
        enabled.setEnabled(workspace != null && !project.isDisposed());
        enabled.addActionListener(event -> TransferLogSettings.setEnabled(project, workspace, enabled.isSelected()));
        menu.add(enabled);
        menu.addSeparator();

        Path directory = workspace == null ? null : workspace.resolve(".vmsync").resolve("transfer-logs");
        boolean exists = false;
        if(directory!=null)try{SafeFiles.noLinks(directory);exists=Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS);}catch(IOException ignored){}
        JMenuItem open = new JMenuItem("打开日志文件夹");
        open.setEnabled(exists);
        open.addActionListener(event -> {
            try { SafeFiles.noLinks(directory);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("传输日志目录不可用");RevealFileAction.openDirectory(directory); }
            catch (Exception failure) { error.accept(failure); }
        });
        menu.add(open);

        JMenuItem clear = new JMenuItem("清空传输日志");
        clear.setEnabled(workspace != null && !project.isDisposed());
        clear.addActionListener(event -> coordinator.clearTransferLogs(workspace,
                result -> status.accept((TransferLogSettings.enabled(project, workspace)
                        ? "历史日志已清空；后续事件将继续记录" : "日志已清空")
                        + (result.skippedFiles() == 0 ? "" : "；跳过不符合日志命名或链接规则的文件 " + result.skippedFiles() + " 个")),
                failure -> error.accept(failure instanceof IOException ? failure : new IOException("清空传输日志失败", failure))));
        menu.add(clear);
    }
}
