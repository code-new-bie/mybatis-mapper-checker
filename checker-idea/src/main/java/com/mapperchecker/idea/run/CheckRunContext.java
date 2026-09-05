package com.mapperchecker.idea.run;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.contract.ParameterContractEngine;
import com.mapperchecker.core.model.CheckResult;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.Statistics;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.core.rule.StatementLocationRules;
import com.mapperchecker.idea.java.JavaParameterResolver;
import com.mapperchecker.idea.mapper.MapperRepository;
import com.mapperchecker.idea.module.ModuleVisibilityResolver;
import com.mapperchecker.idea.suppress.SuppressionMatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次检查运行的上下文：所有运行级缓存与结果收集。方案 13.5。任务结束即丢弃。
 */
public final class CheckRunContext {

    public final Project project;
    public final CheckSettings settings;
    public final MapperRepository repository;
    public final ModuleVisibilityResolver visibility;
    public final StatementLocator locator;
    public final JavaParameterResolver javaParameters;
    public final ParameterContractEngine contractEngine;
    public final StatementLocationRules locationRules;
    public final SuppressionMatcher suppression;
    public final Statistics statistics = new Statistics();

    private final List<ReportedIssue> issues = new ArrayList<>();
    private final List<UnresolvedInvocation> unresolved = new ArrayList<>();
    private final Set<String> seenIssueKeys = new HashSet<>();

    /**
     * 跨方法多消费者判定：参数引用位置（文件+偏移）→ 是否被任一 statement 使用。
     * 运行结束时，被任一 statement 使用过的位置上的 MMC001 全部丢弃。
     */
    private final Map<String, Boolean> crossMethodUsage = new HashMap<>();
    private final List<ReportedIssue> crossMethodIssues = new ArrayList<>();

    public CheckRunContext(@NotNull Project project, @NotNull CheckSettings settings) {
        this.project = project;
        this.settings = settings;
        this.repository = new MapperRepository(project, settings.ignoredPathPatterns());
        this.visibility = new ModuleVisibilityResolver(project, settings.strictVisibility());
        this.locator = new StatementLocator(repository, visibility);
        this.javaParameters = new JavaParameterResolver(project, settings.traceDepth());
        this.contractEngine = new ParameterContractEngine(settings);
        this.locationRules = new StatementLocationRules(settings);
        this.suppression = new SuppressionMatcher(settings);
    }

    // ---------------------------------------------------------------- 收集

    /** 记录一条问题；已抑制的只计数。 */
    public void report(@NotNull ContractIssue issue, @Nullable PsiElement javaAnchor, boolean crossMethod) {
        String key = issue.ruleId() + "|" + issue.statementId() + "|" + issue.parameterName() + "|"
                + issue.primaryLocation().filePath() + "|" + issue.primaryLocation().startOffset();
        if (!seenIssueKeys.add(key)) {
            return;
        }
        if (suppression.isSuppressed(issue, javaAnchor)) {
            statistics.incSuppressedIssues();
            return;
        }
        ReportedIssue reported = new ReportedIssue(issue, pointer(javaAnchor), mapperPointer(issue.secondaryLocation()));
        if (crossMethod) {
            crossMethodIssues.add(reported);
        } else {
            issues.add(reported);
        }
    }

    /** 跨方法追踪：记录某参数位置在某 statement 上是否被使用。 */
    public void recordCrossMethodUsage(@NotNull SourceLocation paramLocation, boolean used) {
        String key = paramLocation.filePath() + "@" + paramLocation.startOffset();
        crossMethodUsage.merge(key, used, (a, b) -> a || b);
    }

    public void reportUnresolved(@NotNull UnresolvedInvocation u) {
        unresolved.add(u);
        statistics.incUnresolvedInvocations();
    }

    /** 收尾：应用多消费者规则，统计置信度。 */
    public @NotNull CheckResult finish(@NotNull String scopeName, @NotNull List<ReportedIssue> outReported) {
        for (ReportedIssue r : crossMethodIssues) {
            String key = r.issue().primaryLocation().filePath() + "@" + r.issue().primaryLocation().startOffset();
            if (Boolean.TRUE.equals(crossMethodUsage.get(key))) {
                continue; // 该 put 对别的 statement 有效，不报
            }
            issues.add(r);
        }
        issues.sort((a, b) -> {
            int c = a.issue().ruleId().compareTo(b.issue().ruleId());
            if (c != 0) {
                return c;
            }
            c = a.issue().confidence().compareTo(b.issue().confidence());
            if (c != 0) {
                return c;
            }
            return a.issue().primaryLocation().filePath().compareTo(b.issue().primaryLocation().filePath());
        });
        List<ContractIssue> plain = new ArrayList<>(issues.size());
        for (ReportedIssue r : issues) {
            plain.add(r.issue());
            statistics.countIssue(r.issue());
        }
        outReported.addAll(issues);
        return new CheckResult(scopeName, plain, unresolved, statistics);
    }

    // ---------------------------------------------------------------- PSI 指针

    private @Nullable SmartPsiElementPointer<PsiElement> pointer(@Nullable PsiElement e) {
        if (e == null || !e.isValid()) {
            return null;
        }
        return SmartPointerManager.getInstance(project).createSmartPsiElementPointer(e);
    }

    /** 由 Mapper 位置找到 XML 标签或注解元素。 */
    private @Nullable SmartPsiElementPointer<PsiElement> mapperPointer(@Nullable SourceLocation loc) {
        if (loc == null || !loc.isKnown()) {
            return null;
        }
        VirtualFile vf = findFile(loc.filePath());
        if (vf == null) {
            return null;
        }
        PsiFile psiFile = PsiManager.getInstance(project).findFile(vf);
        if (psiFile == null) {
            return null;
        }
        PsiElement at = psiFile.findElementAt(Math.min(loc.startOffset(), Math.max(0, psiFile.getTextLength() - 1)));
        if (at == null) {
            return pointer(psiFile);
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(at, XmlTag.class, false);
        return pointer(tag != null ? tag : at);
    }

    /** 供报告窗口导航使用。 */
    public static @Nullable VirtualFile findFileForNavigation(String path) {
        return findFile(path);
    }

    static @Nullable VirtualFile findFile(String path) {
        if (path.contains("!/")) {
            return VirtualFileManager.getInstance().findFileByUrl("jar://" + path);
        }
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(path);
        if (vf == null) {
            // 测试环境的 temp:// 文件系统
            vf = VirtualFileManager.getInstance().findFileByUrl("temp://" + path);
        }
        return vf;
    }
}
