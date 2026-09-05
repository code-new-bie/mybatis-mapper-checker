package com.mapperchecker.idea.inspection;

import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.GlobalSimpleInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptionsProcessor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.run.CheckRunner;
import com.mapperchecker.idea.run.ReportedIssue;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;

/**
 * Inspect Code 批量入口。GlobalSimpleInspectionTool 只在批量模式运行，不会在编辑器里实时标线。方案 13.1。
 */
public final class MapperContractGlobalInspection extends GlobalSimpleInspectionTool {

    public static final String SHORT_NAME = "MyBatisMapperContract";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        return MapperCheckerBundle.message("inspection.display.name");
    }

    @Override
    public @NotNull String getGroupDisplayName() {
        return MapperCheckerBundle.message("inspection.group");
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }

    @Override
    public void checkFile(@NotNull PsiFile file, @NotNull InspectionManager manager, @NotNull ProblemsHolder problemsHolder,
                          @NotNull GlobalInspectionContext globalContext, @NotNull ProblemDescriptionsProcessor processor) {
        if (!(file instanceof PsiJavaFile)) {
            return;
        }
        CheckRunner runner = new CheckRunner(file.getProject(), MapperCheckerSettings.getInstance(file.getProject()).toCheckSettings());
        CheckRunner.Outcome outcome = runner.runOnFile(file);
        for (ReportedIssue ri : outcome.reported()) {
            PsiElement anchor = ri.javaElement();
            if (anchor == null || !anchor.isValid() || anchor.getContainingFile() != file) {
                // 跨文件位置（另一个方法里的 put）只在报告窗口展示
                continue;
            }
            ContractIssue i = ri.issue();
            String text = i.remark().isEmpty() ? i.message() : i.message() + " " + i.remark();
            problemsHolder.registerProblem(anchor, text, highlightType(i.severity()));
        }
    }

    private static ProblemHighlightType highlightType(Severity s) {
        return switch (s) {
            case ERROR -> ProblemHighlightType.GENERIC_ERROR;
            case WARNING -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
            case WEAK_WARNING -> ProblemHighlightType.WEAK_WARNING;
            case INFO -> ProblemHighlightType.INFORMATION;
        };
    }
}
