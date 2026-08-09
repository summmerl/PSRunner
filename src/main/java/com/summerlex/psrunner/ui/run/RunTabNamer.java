package com.summerlex.psrunner.ui.run;

import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 决策 2：Run 标签唯一命名。
 * baseName 未被占用则直接用；否则依次尝试 baseName (2)、(3)…
 * 序号仅基于当前存在的标签判断，关闭后释放可复用。
 */
public final class RunTabNamer {

    private RunTabNamer() {
    }

    public static @NotNull String uniqueName(@NotNull String baseName, @NotNull Project project) {
        RunContentManager manager = RunContentManager.getInstance(project);
        if (!hasContent(manager, baseName)) {
            return baseName;
        }
        int suffix = 2;
        while (true) {
            String candidate = baseName + " (" + suffix + ")";
            if (!hasContent(manager, candidate)) {
                return candidate;
            }
            suffix++;
        }
    }

    private static boolean hasContent(@NotNull RunContentManager manager, @NotNull String name) {
        return manager.getAllDescriptors().stream()
                .anyMatch(d -> name.equals(d.getDisplayName()));
    }
}
