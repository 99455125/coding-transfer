package dev.codingtransfer.plugin;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;

/** Project-local opt-in preference shared by project and file transfer panels. */
final class TransferLogSettings {
    static final String PROPERTY_KEY = "everySync.transferLog.enabled";
    private static final Map<Project, Boolean> ENABLED = new WeakHashMap<>();

    private TransferLogSettings() {}

    static boolean enabled(Project project, Path workspace) {
        if (project == null || workspace == null) return false;
        synchronized (ENABLED) {
            return ENABLED.computeIfAbsent(project, ignored -> PropertiesComponent.getInstance(project).getBoolean(PROPERTY_KEY, false));
        }
    }

    static void setEnabled(Project project, Path workspace, boolean enabled) {
        if (project == null || workspace == null) return;
        PropertiesComponent.getInstance(project).setValue(PROPERTY_KEY, enabled);
        synchronized (ENABLED) { ENABLED.put(project, enabled); }
    }
}
