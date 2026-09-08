package com.mapperchecker.idea.java;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PropertyUtilBase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query 类级聚合规则。规范第一节 1.1 / 1.3。
 * <ul>
 *   <li>DAL-010 删条件未清理：某属性有调用方 set，但该实体关联的<b>所有</b> statement 都不引用它</li>
 *   <li>DAL-011 死字段：既无人 set，也不被任何关联 statement 引用</li>
 * </ul>
 * 与 DAL-001 的区别：DAL-001 看单条语句，一个实体被多条语句复用、各自只用一部分属性是正常的；
 * 这里是把实体关联的全部语句取并集后仍然没人用，才是残留或死字段。
 * <p>
 * "关联的 statement"由 {@link Usage} 在检查 Mapper 方法时逐条记录；"有没有人 set"用 setter 的引用搜索判定。
 * 只在 Module / 项目级检查里运行（全局规则）。
 */
public final class QueryClassRules {

    /** 一个实体在本次运行中被哪些 statement 用了哪些属性根名。 */
    public static final class Usage {
        public final PsiClass beanClass;
        public final Set<String> usedRoots = new LinkedHashSet<>();
        public final Set<String> statementIds = new LinkedHashSet<>();

        Usage(PsiClass beanClass) {
            this.beanClass = beanClass;
        }
    }

    private final Project project;
    private final CheckSettings settings;
    private final JavaRules.Reporter reporter;

    public QueryClassRules(@NotNull Project project, @NotNull CheckSettings settings, @NotNull JavaRules.Reporter reporter) {
        this.project = project;
        this.settings = settings;
        this.reporter = reporter;
    }

    /** 记录：statement 通过参数 bean 引用了哪些属性路径。prefix 为 @Param 值（形如 q.poiId 的 q），无则 null。 */
    public static void record(@NotNull Map<String, Usage> usages, @NotNull PsiClass bean, @NotNull String statementId,
                              @NotNull Set<String> mapperPaths, @Nullable String prefix) {
        String fqn = bean.getQualifiedName();
        if (fqn == null) {
            return;
        }
        Usage u = usages.computeIfAbsent(fqn, k -> new Usage(bean));
        u.statementIds.add(statementId);
        for (String path : mapperPaths) {
            String p = path;
            if (prefix != null) {
                if (!ParameterNameNormalizer.pathCovers(path, prefix) || path.equals(prefix)) {
                    continue;
                }
                p = path.substring(prefix.length() + 1);
            }
            String root = ParameterNameNormalizer.rootName(p);
            if (root != null) {
                u.usedRoots.add(root);
            }
        }
    }

    /**
     * @param usages               本次运行记录的实体使用情况
     * @param reflectiveCopyTargets 被 copyProperties 当作目标的实体全限定名（字段来源看不见，需在文案里标注）
     */
    public void check(@NotNull Map<String, Usage> usages, @NotNull Set<String> reflectiveCopyTargets,
                      @Nullable ProgressIndicator indicator) {
        check(usages, reflectiveCopyTargets, Map.of(), indicator);
    }

    /**
     * @param setterUsages 扫描阶段登记的 setter 调用（"实体全限定名#属性" → 证据）。命中即知道有人 set，
     *                     省掉一次全项目引用搜索；没命中的属性才回退到搜索，且只在 DAL-011 开启时才需要区分
     */
    public void check(@NotNull Map<String, Usage> usages, @NotNull Set<String> reflectiveCopyTargets,
                      @NotNull Map<String, SetterEvidence> setterUsages, @Nullable ProgressIndicator indicator) {
        boolean want010 = settings.isEnabled(RuleId.DAL_010);
        boolean want011 = settings.isEnabled(RuleId.DAL_011);
        if (!want010 && !want011) {
            return;
        }
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        int total = usages.size();
        int done = 0;
        for (Usage u : usages.values()) {
            done++;
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText2(MapperCheckerBundle.message("task.queryClass", done, total,
                        u.beanClass.getName() == null ? "" : u.beanClass.getName()));
            } else {
                ProgressManager.checkCanceled();
            }
            if (!u.beanClass.isValid() || u.statementIds.isEmpty()) {
                continue;
            }
            String entity = u.beanClass.getName() == null ? "" : u.beanClass.getName();
            String fqn = u.beanClass.getQualifiedName();
            boolean copied = fqn != null && reflectiveCopyTargets.contains(fqn);
            Map<String, PsiElement> props = BeanPropertyCollector.collect(u.beanClass);
            for (Map.Entry<String, PsiElement> e : props.entrySet()) {
                String prop = e.getKey();
                if (isUsed(u.usedRoots, prop) || settings.isIgnoredParameter(prop)) {
                    continue;
                }
                PsiMethod setter = PropertyUtilBase.findPropertySetter(u.beanClass, prop, false, true);
                if (setter == null) {
                    // 没有 setter（Lombok 未装插件 / 只读属性）：既判不了 set 也判不了死，不下结论
                    continue;
                }
                String remark = copied ? MapperCheckerBundle.message("remark.reflective.copy.target") : "";
                // 快路径：扫描阶段已经见过 x.setProp(...)
                SetterEvidence recorded = setterUsages.get(fqn + "#" + prop);
                if (recorded != null) {
                    if (want010) {
                        // 上游赋值分析：目前见过的调用全是传 null，大概率是死代码，降一级优先度，但仍然报——
                        // 静态分析看不到全部调用点，不敢断言"一定没用"
                        Confidence confidence = Confidence.HIGH;
                        String upstream = "";
                        if (settings.upstreamAssignmentAnalysis() && !recorded.anyRealValue()) {
                            confidence = confidence.lower();
                            upstream = MapperCheckerBundle.message("remark.upstream.not_assigned");
                        }
                        String message = MapperCheckerBundle.message("issue.DAL-010", entity, prop, recorded.exampleLocation(), u.statementIds.size());
                        report(RuleId.DAL_010, confidence, message, combine(remark, upstream), prop, fqn, e.getValue(), u);
                    }
                    continue;
                }
                if (!want011) {
                    // 只开 DAL-010 时，没登记到就当没人 set（set 该实体的文件必然提到过它的类名，已在扫描范围内），
                    // 不值得为此做一次全项目引用搜索
                    continue;
                }
                // 慢路径：只有"看起来是死字段"的少数属性才走引用搜索，确认确实没人 set
                PsiReference firstCaller = MethodReferencesSearch.search(setter, scope, false).findFirst();
                if (firstCaller != null) {
                    if (!want010) {
                        continue;
                    }
                    String example = Locations.of(firstCaller.getElement()).display();
                    String message = MapperCheckerBundle.message("issue.DAL-010", entity, prop, example, u.statementIds.size());
                    report(RuleId.DAL_010, Confidence.HIGH, message, remark, prop, fqn, e.getValue(), u);
                } else {
                    String message = MapperCheckerBundle.message("issue.DAL-011", entity, prop, u.statementIds.size());
                    report(RuleId.DAL_011, Confidence.LOW, message, remark, prop, fqn, e.getValue(), u);
                }
            }
        }
        if (indicator != null) {
            indicator.setText2("");
        }
    }

    private static boolean isUsed(Set<String> usedRoots, String prop) {
        return usedRoots.contains(prop);
    }

    static String combine(String base, String extra) {
        if (extra.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? extra : base + " " + extra;
    }

    private void report(RuleId rule, Confidence confidence, String message, String remark,
                        String prop, String fqn, PsiElement anchor, Usage u) {
        // 备注里附上关联的 statement，便于人工核对
        String detail = MapperCheckerBundle.message("remark.related.statements", String.join("、", shortIds(u.statementIds)));
        String fullRemark = remark.isEmpty() ? detail : remark + " " + detail;
        ContractIssue issue = new ContractIssue(rule, settings.severityOf(rule), confidence, message, fullRemark,
                prop, fqn, List.of(), Locations.of(anchor), SourceLocation.UNKNOWN, List.of());
        reporter.accept(issue, anchor);
    }

    private static List<String> shortIds(Set<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            int dot = id.lastIndexOf('.');
            int prev = dot <= 0 ? -1 : id.lastIndexOf('.', dot - 1);
            out.add(prev < 0 ? id : id.substring(prev + 1));
        }
        return out;
    }
}
