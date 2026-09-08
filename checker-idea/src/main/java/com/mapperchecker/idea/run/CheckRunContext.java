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
    private final List<ReportedExemption> exempted = new ArrayList<>();
    private final Set<String> seenIssueKeys = new HashSet<>();
    private final com.mapperchecker.idea.suppress.ExemptionService exemptions;

    /**
     * 跨方法多消费者判定：参数引用位置（文件+偏移）→ 是否被任一 statement 使用。
     * 运行结束时，被任一 statement 使用过的位置上的 DAL-001 全部丢弃。
     */
    private final Map<String, Boolean> crossMethodUsage = new HashMap<>();
    private final List<ReportedIssue> crossMethodIssues = new ArrayList<>();

    /** Query 类级聚合（DAL-010 / 011）：实体全限定名 → 关联 statement 与被引用的属性根名。 */
    public final Map<String, com.mapperchecker.idea.java.QueryClassRules.Usage> queryUsages = new java.util.LinkedHashMap<>();
    /** 被 copyProperties 当作目标的实体全限定名，字段来源静态看不见。 */
    public final Set<String> reflectiveCopyTargets = new HashSet<>();
    /** 扫描过程中见到的 setter 调用："实体全限定名#属性" → 证据，供 DAL-010 与上游赋值分析共用。 */
    public final Map<String, com.mapperchecker.idea.java.SetterEvidence> setterUsages = new HashMap<>();
    /**
     * 声明级 DAL-001 上游赋值分析要去查 Mapper 方法的调用点，用的范围与单 Map 参数调用点追踪一致。
     * CheckRunner 算出真实范围后可以覆盖；构造时先退化为整项目。
     */
    public com.intellij.psi.search.GlobalSearchScope callSiteScope;

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
        this.exemptions = com.mapperchecker.idea.suppress.ExemptionService.getInstance(project);
        this.callSiteScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project);
        for (com.mapperchecker.core.contract.Exemption e : exemptions.all()) {
            if (!e.isComplete()) {
                statistics.incInvalidExemptions();
            }
        }
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
        // 必须在这里（已在 read action 内）算好模块名，不能留到渲染阶段现查 PSI。
        String moduleName = com.mapperchecker.idea.util.Locations.moduleNameOf(javaAnchor);
        ReportedIssue reported = new ReportedIssue(issue, pointer(javaAnchor), mapperPointer(issue.secondaryLocation()), moduleName);
        // 团队豁免：不算问题，但保留记录进汇总
        com.mapperchecker.core.contract.Exemption exemption = exemptions.find(issue);
        if (exemption != null) {
            statistics.incExemptedIssues();
            exempted.add(new ReportedExemption(reported, exemption));
            return;
        }
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
        return finish(scopeName, outReported, new ArrayList<>());
    }

    /** 收尾：应用多消费者规则，统计置信度，交出问题与豁免记录。 */
    public @NotNull CheckResult finish(@NotNull String scopeName, @NotNull List<ReportedIssue> outReported,
                                       @NotNull List<ReportedExemption> outExempted) {
        for (ReportedIssue r : crossMethodIssues) {
            String key = r.issue().primaryLocation().filePath() + "@" + r.issue().primaryLocation().startOffset();
            if (Boolean.TRUE.equals(crossMethodUsage.get(key))) {
                continue; // 该 put 对别的 statement 有效，不报
            }
            issues.add(r);
        }
        if (settings.upstreamAssignmentAnalysis()) {
            // 置信度可能变、也可能被判定为无害排除掉，必须在排序之前处理完
            UpstreamAssignmentAnalyzer analyzer = new UpstreamAssignmentAnalyzer(
                    callSiteScope == null ? com.intellij.psi.search.GlobalSearchScope.projectScope(project) : callSiteScope,
                    setterUsages, reflectiveCopyTargets);
            List<ReportedIssue> kept = new ArrayList<>(issues.size());
            for (ReportedIssue ri : issues) {
                ReportedIssue adjusted = analyzer.adjust(ri);
                if (adjusted == null) {
                    statistics.incAutoExcludedIssues();
                } else {
                    kept.add(adjusted);
                }
            }
            issues.clear();
            issues.addAll(kept);
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
        outExempted.addAll(exempted);
        List<com.mapperchecker.core.model.ExemptedIssue> plainExempted = new ArrayList<>(exempted.size());
        for (ReportedExemption re : exempted) {
            plainExempted.add(new com.mapperchecker.core.model.ExemptedIssue(re.reported().issue(), re.exemption()));
        }
        return new CheckResult(scopeName, plain, unresolved, plainExempted, statistics);
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
