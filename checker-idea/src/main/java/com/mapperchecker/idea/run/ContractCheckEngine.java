package com.mapperchecker.idea.run;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.Operation;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.core.model.UnresolvedReason;
import com.mapperchecker.core.rule.StatementLocationRules;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.java.MapperMethodInvocationExtractor;
import com.mapperchecker.idea.java.StatementIdResolver;
import com.mapperchecker.idea.java.StringCallInvocationExtractor;
import com.mapperchecker.idea.java.TraceResult;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 DaoInvocation 的检查链：定位 statement → DAL-005/003 → DAL-001（声明级 / 调用点数据流）。方案 13.3。
 * 所有方法都必须在 ReadAction 内调用。
 */
public final class ContractCheckEngine {

    private final CheckRunContext ctx;
    private final com.mapperchecker.idea.java.JavaRules javaRules;

    public ContractCheckEngine(@NotNull CheckRunContext ctx) {
        this.ctx = ctx;
        this.javaRules = new com.mapperchecker.idea.java.JavaRules(ctx.project, ctx.settings,
                (issue, anchor) -> ctx.report(issue, anchor, false), ctx.reflectiveCopyTargets);
        this.queryClassRules = new com.mapperchecker.idea.java.QueryClassRules(ctx.project, ctx.settings,
                (issue, anchor) -> ctx.report(issue, anchor, false));
        this.mapperSideRules = new com.mapperchecker.idea.mapper.MapperSideRules(ctx.project, ctx.settings,
                (issue, anchor) -> ctx.report(issue, anchor, false));
    }

    private final com.mapperchecker.idea.java.QueryClassRules queryClassRules;
    private final com.mapperchecker.idea.mapper.MapperSideRules mapperSideRules;

    /** Query 类级聚合规则（DAL-010 / 011），在全部 Mapper 与 Java 文件检查完之后调用。 */
    public void checkQueryClasses(@Nullable ProgressIndicator indicator) {
        queryClassRules.check(ctx.queryUsages, ctx.reflectiveCopyTargets, indicator);
    }

    /** 纯 Java 规则入口，供 CheckRunner 按文件 / 全局调用。 */
    public com.mapperchecker.idea.java.JavaRules javaRules() {
        return javaRules;
    }

    // ---------------------------------------------------------------- Mapper 接口

    /** 检查一个 Mapper 接口的全部自身方法。 */
    public void checkMapperInterface(@NotNull PsiClass mapper, @Nullable GlobalSearchScope callSiteScope) {
        ctx.statistics.incMapperInterfaces();
        VirtualFile file = mapper.getContainingFile() == null ? null : mapper.getContainingFile().getVirtualFile();
        for (PsiMethod method : mapper.getMethods()) {
            ProgressManager.checkCanceled();
            MapperMethodInvocationExtractor.Extracted extracted = MapperMethodInvocationExtractor.extract(method);
            if (extracted == null) {
                continue;
            }
            ctx.statistics.incDaoInvocations();
            DaoInvocation inv = extracted.invocation();
            PsiElement anchor = method.getNameIdentifier() == null ? method : method.getNameIdentifier();

            // DAL-022：Query 参数的 @Param 命名，与 statement 是否存在无关
            javaRules.checkQueryParamNaming(method, inv.statementId());

            if (extracted.isProvider()) {
                ctx.reportUnresolved(new UnresolvedInvocation(inv.statementId(), UnresolvedReason.PROVIDER, "", inv.location()));
                continue;
            }

            StatementLocationRules.Lookup lookup = ctx.locator.locate(inv.statementId(), file, method);
            List<ContractIssue> locationIssues = ctx.locationRules.check(inv, lookup);
            for (ContractIssue issue : locationIssues) {
                ctx.report(issue, anchor, false);
            }
            if (!lookup.isUnique()) {
                continue;
            }
            ctx.statistics.incResolvedInvocations();
            ResolvedStatement statement = lookup.candidates().get(0);
            DaoInvocation withOp = withOperation(inv, statement);
            if (statement.partiallyParsed()) {
                // 规范第六节：不能静默压制，登记为覆盖缺口
                ctx.reportUnresolved(new UnresolvedInvocation(inv.statementId(), UnresolvedReason.PARTIAL_STATEMENT,
                        "", inv.location()));
            }

            // 记录实体 → statement 引用的属性，供 DAL-010 / 011 聚合；同时做 DAL-002（实体里不存在的属性）
            recordQueryUsage(method, inv.statementId(), statement);
            // DAL-003：模板语句的 <if> 判断字段与块内绑定字段
            mapperSideRules.checkTemplateBinding(inv.statementId(), statement);

            // 声明级 DAL-001（含实体属性级，锚点可能在实体类文件里）
            if (withOp.hasComparableParameters()) {
                for (ContractIssue issue : ctx.contractEngine.check(withOp, statement)) {
                    PsiElement a = anchorFor(method, issue);
                    if (a == null) {
                        a = anchorAt(issue.primaryLocation(), anchor);
                    }
                    ctx.report(issue, a, false);
                }
            }
            // 单 Map 参数：到调用点追踪
            if (extracted.mapParameter() != null && callSiteScope != null) {
                checkMapCallSites(method, withOp, statement, callSiteScope);
            }
        }
    }

    private void checkMapCallSites(PsiMethod method, DaoInvocation declared, ResolvedStatement statement,
                                   GlobalSearchScope scope) {
        for (PsiReference ref : MethodReferencesSearch.search(method, scope, true).findAll()) {
            ProgressManager.checkCanceled();
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethodCallExpression.class, false);
            if (call == null) {
                continue;
            }
            PsiExpression[] args = call.getArgumentList().getExpressions();
            int index = indexOfMapParameter(method);
            if (index < 0 || index >= args.length) {
                continue;
            }
            traceAndCheck(args[index], call, declared.statementId(), declared.operation(), InvocationKind.MAPPER_METHOD, statement);
        }
    }

    private static int indexOfMapParameter(PsiMethod method) {
        var params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (com.mapperchecker.idea.java.MethodSignatureParameterResolver.isMap(params[i].getType())) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- 字符串调用

    /** 检查一个 SqlSession / SqlMapClient 字符串调用。 */
    public void checkStringCall(@NotNull PsiMethodCallExpression call) {
        StringCallInvocationExtractor.Candidate cand = StringCallInvocationExtractor.extract(call);
        if (cand == null) {
            return;
        }
        ctx.statistics.incDaoInvocations();
        SourceLocation callLocation = Locations.of(cand.statementIdExpr());
        String statementId = StatementIdResolver.resolve(cand.statementIdExpr());
        if (statementId == null) {
            ctx.reportUnresolved(new UnresolvedInvocation("", UnresolvedReason.STATEMENT_ID_DYNAMIC,
                    cand.statementIdExpr().getText(), callLocation));
            return;
        }
        PsiFile psiFile = call.getContainingFile();
        VirtualFile file = psiFile == null ? null : psiFile.getVirtualFile();
        DaoInvocation inv = new DaoInvocation(cand.kind(), cand.operation(), statementId, null, null,
                callLocation, Locations.moduleNameOf(call));

        StatementLocationRules.Lookup lookup = ctx.locator.locate(statementId, file, null);
        for (ContractIssue issue : ctx.locationRules.check(inv, lookup)) {
            ctx.report(issue, cand.statementIdExpr(), false);
        }
        if (!lookup.isUnique()) {
            return;
        }
        ctx.statistics.incResolvedInvocations();
        ResolvedStatement statement = lookup.candidates().get(0);
        if (statement.partiallyParsed()) {
            ctx.reportUnresolved(new UnresolvedInvocation(statementId, UnresolvedReason.PARTIAL_STATEMENT, "", callLocation));
        }
        mapperSideRules.checkTemplateBinding(statementId, statement);
        if (cand.parameterExpr() == null) {
            return;
        }
        traceAndCheck(cand.parameterExpr(), call, statementId, cand.operation(), cand.kind(), statement);
    }

    // ---------------------------------------------------------------- 数据流 + DAL-001

    private void traceAndCheck(PsiExpression argument, PsiMethodCallExpression callSite, String statementId,
                               Operation operation, InvocationKind kind, ResolvedStatement statement) {
        TraceResult trace = ctx.javaParameters.trace(argument, callSite);
        SourceLocation loc = Locations.of(argument);
        switch (trace.status()) {
            case NOT_COMPARABLE -> {
                // 单标量等，不报也不算无法解析
            }
            case UNRESOLVED -> ctx.reportUnresolved(new UnresolvedInvocation(statementId,
                    trace.reason() == null ? UnresolvedReason.OTHER : trace.reason(), trace.detail(), loc));
            case RESOLVED -> {
                DaoInvocation inv = new DaoInvocation(kind, operation, statementId, trace.parameters(), trace.callPath(),
                        loc, Locations.moduleNameOf(callSite));
                boolean crossMethod = !trace.callPath().isEmpty();
                List<ContractIssue> issues = ctx.contractEngine.check(inv, statement);
                if (crossMethod) {
                    // 记录每个参数位置在该 statement 上是否被使用，供多消费者判定
                    Map<String, ContractIssue> unusedByLoc = new HashMap<>();
                    for (ContractIssue i : issues) {
                        unusedByLoc.put(i.primaryLocation().filePath() + "@" + i.primaryLocation().startOffset(), i);
                    }
                    for (ParameterReference ref : trace.parameters()) {
                        String key = ref.location().filePath() + "@" + ref.location().startOffset();
                        ctx.recordCrossMethodUsage(ref.location(), !unusedByLoc.containsKey(key));
                    }
                }
                for (ContractIssue issue : issues) {
                    ctx.report(issue, anchorAt(issue.primaryLocation(), callSite), crossMethod);
                }
            }
        }
    }

    /** 参数是项目里的实体类时，记录该 statement 通过它引用了哪些属性。 */
    private void recordQueryUsage(PsiMethod method, String statementId, ResolvedStatement statement) {
        var params = method.getParameterList().getParameters();
        int beanCount = 0;
        for (var p : params) {
            if (com.mapperchecker.idea.java.BeanPropertyCollector.expandableBeanClass(p.getType()) != null) {
                beanCount++;
            }
        }
        for (var p : params) {
            PsiClass bean = com.mapperchecker.idea.java.BeanPropertyCollector.expandableBeanClass(p.getType());
            if (bean == null) {
                continue;
            }
            String prefix = com.mapperchecker.idea.java.MethodSignatureParameterResolver.paramAnnotationValue(p);
            if (prefix == null && (params.length > 1 || beanCount > 1)) {
                // 多参数无 @Param：Mapper 里用 param1.x / arg0.x，与实体属性的对应关系不确定，不记录
                continue;
            }
            com.mapperchecker.idea.java.QueryClassRules.record(ctx.queryUsages, bean, statementId,
                    statement.parameterPaths(), prefix);
            if (!statement.partiallyParsed()) {
                mapperSideRules.checkMissingProperties(bean, prefix, statementId, statement);
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static DaoInvocation withOperation(DaoInvocation inv, ResolvedStatement st) {
        Operation op = st.type().toOperation();
        if (op == inv.operation()) {
            return inv;
        }
        return new DaoInvocation(inv.kind(), op, inv.statementId(), inv.parameters(), inv.callPath(), inv.location(), inv.moduleName());
    }

    /** 声明级问题的锚点：位置与某个 PsiParameter 重合时返回该参数；否则返回 null 交给按位置定位。 */
    private static @Nullable PsiElement anchorFor(PsiMethod method, ContractIssue issue) {
        String issueFile = issue.primaryLocation().filePath();
        for (var p : method.getParameterList().getParameters()) {
            SourceLocation l = Locations.of(p);
            if (l.startOffset() == issue.primaryLocation().startOffset() && l.filePath().equals(issueFile)) {
                return p;
            }
        }
        return null;
    }

    /** 数据流问题的锚点：位置处的最小 PSI 元素（可能在另一个文件）。 */
    private @Nullable PsiElement anchorAt(SourceLocation loc, PsiElement fallback) {
        if (!loc.isKnown()) {
            return fallback;
        }
        VirtualFile vf = CheckRunContext.findFile(loc.filePath());
        PsiFile file = vf == null ? null : com.intellij.psi.PsiManager.getInstance(ctx.project).findFile(vf);
        if (file == null) {
            return fallback;
        }
        PsiElement at = file.findElementAt(loc.startOffset());
        if (at == null) {
            return fallback;
        }
        // 提升到覆盖整个范围的最小父节点（如字面量表达式）
        PsiElement e = at;
        while (e.getParent() != null && e.getParent().getTextRange() != null
                && e.getParent().getTextRange().getEndOffset() <= loc.endOffset()
                && e.getParent().getTextRange().getStartOffset() >= loc.startOffset()
                && !(e.getParent() instanceof PsiFile)) {
            e = e.getParent();
        }
        return e;
    }
}
