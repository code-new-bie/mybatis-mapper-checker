package com.mapperchecker.idea.run;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.report.CheckReportService;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * 后台检查任务：可取消、有进度。完成后刷新报告窗口；取消则保留上一次结果。方案 13.2 / 13.3。
 */
public final class ContractCheckTask extends Task.Backgroundable {

    private final CheckScope scope;
    private CheckRunner.Outcome outcome;

    public ContractCheckTask(@NotNull Project project, @NotNull CheckScope scope) {
        super(project, MapperCheckerBundle.message("task.title"), true);
        this.scope = scope;
    }

    /** 入口：Dumb Mode 直接提示，不排队。 */
    public static void start(@NotNull Project project, @NotNull CheckScope scope) {
        if (DumbService.isDumb(project)) {
            Messages.showInfoMessage(project,
                    MapperCheckerBundle.message("dumb.mode.message"),
                    MapperCheckerBundle.message("dumb.mode.title"));
            return;
        }
        MapperCheckerSettings.getInstance(project).state().lastScope = scope.kind.name();
        new ContractCheckTask(project, scope).queue();
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        Project project = getProject();
        indicator.setIndeterminate(false);
        CheckRunner runner = new CheckRunner(project, MapperCheckerSettings.getInstance(project).toCheckSettings());
        outcome = runner.run(scope, indicator);
    }

    @Override
    public void onSuccess() {
        if (outcome == null || getProject().isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() ->
                CheckReportService.getInstance(getProject()).show(outcome, scope), getProject().getDisposed());
    }
}
