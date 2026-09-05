package com.mapperchecker.idea.report;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.mapperchecker.idea.run.CheckRunner;
import com.mapperchecker.idea.run.CheckScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 保存最近一次报告并驱动工具窗口。不持久化，IDE 重启后为空。
 */
@Service(Service.Level.PROJECT)
public final class CheckReportService {

    public static final String TOOL_WINDOW_ID = "MyBatis Mapper Checker";

    private final Project project;
    private @Nullable CheckRunner.Outcome lastOutcome;
    private @Nullable CheckScope lastScope;
    private @Nullable CheckReportPanel panel;

    public CheckReportService(@NotNull Project project) {
        this.project = project;
    }

    public static CheckReportService getInstance(@NotNull Project project) {
        return project.getService(CheckReportService.class);
    }

    public @Nullable CheckRunner.Outcome lastOutcome() {
        return lastOutcome;
    }

    public @Nullable CheckScope lastScope() {
        return lastScope;
    }

    void attach(@NotNull CheckReportPanel p) {
        this.panel = p;
        if (lastOutcome != null) {
            p.render(lastOutcome, lastScope);
        }
    }

    /** EDT 调用：更新结果并弹出工具窗口。 */
    public void show(@NotNull CheckRunner.Outcome outcome, @NotNull CheckScope scope) {
        this.lastOutcome = outcome;
        this.lastScope = scope;
        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (tw == null) {
            return;
        }
        tw.show(() -> {
            if (panel != null) {
                panel.render(outcome, scope);
            }
        });
    }

    /** 报告内右键"忽略"后，从当前展示中移除，无需重跑。 */
    public void refresh() {
        if (panel != null && lastOutcome != null) {
            panel.render(lastOutcome, lastScope);
        }
    }
}
