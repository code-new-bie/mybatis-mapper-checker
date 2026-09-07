package com.mapperchecker.idea.inspection;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.java.JavaRules;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.java.MapperMethodInvocationExtractor;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import com.mapperchecker.idea.suppress.ExemptionService;
import com.mapperchecker.idea.suppress.SuppressionMatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 纯 Java 规则（DAL-004 / 020 / 022 / 030）的实时提示。默认关闭，设置里"在编辑器里实时提示纯 Java 规则"打开后才生效；
 * 关闭时本 Inspection 什么都不产生，保持"零干扰"。
 * <p>
 * 这四条只看单个文件，不做跨文件引用搜索，实时跑代价很低。DAL-022 需要判定接口是不是 Mapper（要查索引），Dumb Mode 下跳过。
 */
public final class JavaRulesLocalInspection extends AbstractBaseJavaLocalInspectionTool {

    public static final String SHORT_NAME = "MyBatisMapperJavaRules";

    @Override
    public @NotNull String getShortName() {
        return SHORT_NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        return MapperCheckerBundle.message("inspection.java.rules.display.name");
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
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        PsiFile file = holder.getFile();
        Project project = holder.getProject();
        MapperCheckerSettings settings = MapperCheckerSettings.getInstance(project);
        if (!(file instanceof PsiJavaFile) || !settings.state().realtimeJavaRules) {
            return PsiElementVisitor.EMPTY_VISITOR;
        }
        CheckSettings checkSettings = settings.toCheckSettings();
        SuppressionMatcher suppression = new SuppressionMatcher(checkSettings);
        ExemptionService exemptions = ExemptionService.getInstance(project);

        JavaRules rules = new JavaRules(project, checkSettings, (issue, anchor) -> {
            if (anchor == null || !anchor.isValid() || anchor.getContainingFile() != file) {
                return;
            }
            if (suppression.isSuppressed(issue, anchor) || exemptions.find(issue) != null) {
                return;
            }
            String text = issue.remark().isEmpty() ? issue.message() : issue.message() + " " + issue.remark();
            holder.registerProblem(anchor, "[" + issue.ruleId().code() + "] " + text, highlightType(issue.severity()));
        });

        // 按元素访问而不是整文件：编辑器只重查改动区域时也能正确工作
        return new JavaElementVisitor() {
            @Override
            public void visitMethodCallExpression(@NotNull PsiMethodCallExpression call) {
                rules.checkMethodCall(call);
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                PsiClass cls = method.getContainingClass();
                if (cls == null || !cls.isInterface() || DumbService.isDumb(project) || !MapperInterfaceDetector.isMapperInterface(cls)) {
                    return;
                }
                MapperMethodInvocationExtractor.Extracted e = MapperMethodInvocationExtractor.extract(method);
                if (e != null) {
                    rules.checkQueryParamNaming(method, e.invocation().statementId());
                }
            }
        };
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
