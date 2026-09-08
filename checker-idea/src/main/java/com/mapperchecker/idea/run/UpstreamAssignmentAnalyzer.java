package com.mapperchecker.idea.run;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.UpstreamStatus;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.java.JavaRules;
import com.mapperchecker.idea.java.SetterEvidence;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DAL-001（声明的参数、实体属性）"上游有没有真的赋值"分析。方案里的产品决定：
 * 只对已经报出来的问题做，数量远小于全项目方法 / 属性数，成本可控；DAL-010 在生成时已经
 * 用 {@link SetterEvidence} 就地调整过，不走这里。
 * <p>
 * 用 {@link ReportedIssue#javaElement()} 的 PSI 形状区分两种来源，不猜文案：
 * <ul>
 *   <li>锚点是标量 / 集合 / 数组类型的 {@link PsiParameter}——Mapper 接口方法声明的参数
 *       （{@code @Param} / 无注解别名组）：去查该方法的调用点，看实参是不是字面量 null</li>
 *   <li>锚点是 Bean 类型的 {@link PsiParameter}——实体属性（单 Bean / {@code @Param} Bean 展开）；
 *       真机反馈双击应该落到"对应的 DAO 方法"，锚点已改成该方法里声明这个实体的参数，
 *       这里按参数的<b>类型</b>取实体类，然后沿调用链往上追这个实体是在哪儿构造、有没有被
 *       set 过这个属性（见 {@link #traceUpFromParameter}）；追不出结论才退回扫描阶段登记的
 *       项目级 {@link SetterEvidence} 表（与 DAL-010 共用）——那张表不区分调用链，只当兜底</li>
 *   <li>其余（理论上只有"多个 Bean 参数、猜不出对应哪个"这种极少数兜底到方法名的情形）：
 *       退化为 UNKNOWN，不下结论</li>
 * </ul>
 * 判定很朴素：只把字面量 {@code null} 当"没给值"，其余（变量、方法调用、new 出来的对象……）
 * 一律当"给了值"。静态分析看不出变量运行时是不是恰好是 null，宁可漏报，不装作能看穿数据流。
 *
 * <h2>沿调用链往上追（2026-09-08 用户反馈后加的）</h2>
 * 用户的原话是"如果整个业务调用的过程中没有赋值，理论上这条异常就不该报"——思路对，
 * 但早先只看 DAO 方法的<b>直接</b>调用点、且只认"局部变量 {@code new Bean()} + 顺序 setX"
 * 这一种写法。Spring 分层里 Query 几乎都是在 Service 层构造、当参数一层层传下来的，
 * DAO 的直接调用者拿到的就是它自己的形参，一照面就判 UNKNOWN——最典型的代码恰恰追不动，
 * 报告里于是留下一大片笼统的"该实体可能被多个 statement 共用，请人工确认"。
 * <p>
 * 现在实参解析到"当前方法自己的形参"时会顺着这个参数位置继续往上一层调用者追，直到找到
 * 构造点、或撞上 {@link #MAX_CHAIN_DEPTH} / 预算上限。搜调用点时连同 {@code findSuperMethods()}
 * 一起搜（复用 {@link CallChainFinder#searchTargets}）：Spring 里真正的调用点大多经接口引用，
 * 只搜实现类方法会把它们全漏掉——那正是调用链功能上线时真机报过的漏报（见 TASKS.md）。
 * 多搜出来的调用点只会让结论更保守（可能多报），不会让它更激进。
 *
 * <h2>三种结论，处理方式不同</h2>
 * <ul>
 *   <li>ASSIGNED（确实传过非 null 值）：值被静默丢弃，置信度上调，照常报</li>
 *   <li>NOT_ASSIGNED（追到了构造点，全程没给过真值）：DAL-001 的核心担忧是"有值却没用上"，
 *       既然确认从来没给过真值，SQL 不用它就完全谈得通，不算问题——{@link #adjust} 返回
 *       {@code null}，这条 issue 不计入报告，只在统计里加一笔"已排除"，不静默到看不出发生过什么</li>
 *   <li>UNKNOWN（追不出结论）：不代表"确认没赋值"——报告里单独分一组"证据不足，待人工确认"
 *       （{@link ContractIssue#isEvidenceInsufficient()}），跟已确认的问题分开看；置信度不变。
 *       如果沿途见到 {@code copyProperties} 之类反射拷贝，会把这层可能性写进备注</li>
 *   <li>NOT_ANALYZED：只有实体属性那一支追不出结论才算 UNKNOWN。声明参数（{@code @Param} 标量 /
 *       集合）的 DAL-001 是代码自身就能证实的契约不符，成立与否不依赖调用链证据，追不出来时
 *       原样保留、不进"证据不足"分组，详见 {@link #analyzeDeclaredParam}</li>
 * </ul>
 *
 * <h2>为什么 NOT_ASSIGNED 判得比 ASSIGNED 严</h2>
 * 判错成 ASSIGNED 只是多报一条（人看得见、能自己否掉）；判错成 NOT_ASSIGNED 是<b>静默排除</b>，
 * 真问题就此消失。所以只有"这一层每一个调用点都追出了确定结论、且没有一个是赋真值"才敢报
 * NOT_ASSIGNED；只要有一个调用点没看懂、调用点被上限截断、或者压根没找到调用点（追到入口点
 * 还没见到构造），整条就降级成 UNKNOWN 交给人工。
 * <p>
 * 必须在 ReadAction 内调用（PSI 引用搜索）。
 */
final class UpstreamAssignmentAnalyzer {

    /** 单个方法的调用点数量上限，避免极端扇入拖慢单条 issue 的分析；触顶即视为"没看全"。 */
    private static final int MAX_CALL_SITES_PER_METHOD = 50;
    /** 沿调用链往上追的层数上限。Controller → Service → DalService → DAO 大致就是这个量级。 */
    private static final int MAX_CHAIN_DEPTH = 4;
    /**
     * 一次运行里最多对多少个不同方法做引用搜索。之前专门优化掉过"几千次全项目引用搜索"的性能问题
     * （commit 58be0bf），往上追天然会重新引入这个开销，必须有硬上限；超了就老实返回 UNKNOWN，
     * 不硬追。命中缓存的方法不重复计数。
     */
    private static final int MAX_SEARCHED_METHODS = 400;

    private final GlobalSearchScope callSiteScope;
    private final Map<String, SetterEvidence> setterUsages;
    /** 被 copyProperties 之类反射拷贝当作过目标的实体全限定名——沿链没看到反射拷贝、
     * 但这个实体在别处被拷过时，用来给"为什么看不出来"补一句更具体的解释。 */
    private final Set<String> reflectiveCopyTargets;
    /** 按方法缓存调用点：同一方法在多条链、多个属性上会被反复问到，只搜一次。 */
    private final Map<PsiMethod, CallSites> callSitesByMethod = new HashMap<>();
    private int searchedMethods;

    UpstreamAssignmentAnalyzer(@NotNull GlobalSearchScope callSiteScope, @NotNull Map<String, SetterEvidence> setterUsages,
                              @NotNull Set<String> reflectiveCopyTargets) {
        this.callSiteScope = callSiteScope;
        this.setterUsages = setterUsages;
        this.reflectiveCopyTargets = reflectiveCopyTargets;
    }

    /**
     * @return 调整后的问题；{@code null} 表示确认"从未真正赋值 + SQL 未使用"，判定为无害，
     *         调用方应该把它从问题列表里去掉（改记一笔"已排除"统计，而不是静默消失）
     */
    @Nullable ReportedIssue adjust(@NotNull ReportedIssue ri) {
        ContractIssue issue = ri.issue();
        if (issue.ruleId() != RuleId.DAL_001) {
            return ri;
        }
        PsiElement anchor = ri.javaElement();
        if (anchor == null || !anchor.isValid()) {
            return ri; // 没法分析，保持 NOT_ANALYZED，别打上"证据不足"的标签
        }
        ProgressManager.checkCanceled();
        Evidence evidence = evidenceFor(anchor, issue.parameterName());
        return switch (evidence.status()) {
            case NOT_ASSIGNED -> null; // 确认从未给过真值，且 SQL 不用——完全自洽，不算问题
            case ASSIGNED -> replace(ri, applyAssigned(issue, evidence));
            // 追不出结论：置信度不变，但打上标记进"证据不足"分组；沿途见过反射拷贝就说清楚
            case UNKNOWN -> replace(ri, evidence.reflectiveCopyHint()
                    ? applyReflectiveHint(issue)
                    : rebuild(issue, issue.confidence(), issue.remark(), UpstreamStatus.UNKNOWN));
            case NOT_ANALYZED -> ri;
        };
    }

    private static ReportedIssue replace(ReportedIssue ri, ContractIssue adjusted) {
        return new ReportedIssue(adjusted, ri.javaAnchor(), ri.mapperAnchor(), ri.moduleName());
    }

    /** 上游确实传过真值，SQL 却没用：值被静默丢弃，置信度上调，备注说明。 */
    private ContractIssue applyAssigned(ContractIssue issue, Evidence evidence) {
        String extra = MapperCheckerBundle.message("remark.upstream.assigned", evidence.example());
        return rebuild(issue, issue.confidence().raise(), append(issue.remark(), extra), UpstreamStatus.ASSIGNED);
    }

    /** 直接赋值 / setter 都追不出证据，但沿途或别处见过反射拷贝：说清楚可能是这个原因，置信度不变。 */
    private ContractIssue applyReflectiveHint(ContractIssue issue) {
        String extra = MapperCheckerBundle.message("remark.upstream.maybe.reflective");
        return rebuild(issue, issue.confidence(), append(issue.remark(), extra), UpstreamStatus.UNKNOWN);
    }

    private static String append(String remark, String extra) {
        return remark.isEmpty() ? extra : remark + " " + extra;
    }

    private static ContractIssue rebuild(ContractIssue i, Confidence confidence, String remark, UpstreamStatus upstream) {
        return new ContractIssue(i.ruleId(), i.severity(), confidence, i.message(), remark,
                i.parameterName(), i.statementId(), i.callPath(), i.primaryLocation(),
                i.secondaryLocation(), i.candidates(), upstream);
    }

    /** 按锚点形状分流：Bean 类型的参数是实体属性，其余（标量 / 集合 / 数组）是声明参数走调用点。 */
    private Evidence evidenceFor(PsiElement anchor, String propertyPath) {
        if (!(anchor instanceof PsiParameter param)) {
            return Evidence.notAnalyzed(); // 极少数兜底到方法名的情形，连形状都认不出，不猜也不打标签
        }
        PsiClass beanClass = com.mapperchecker.idea.java.BeanPropertyCollector.expandableBeanClass(param.getType());
        return beanClass != null ? analyzeProperty(param, beanClass, propertyPath) : analyzeDeclaredParam(param);
    }

    // ---------------------------------------------------------------- 声明的方法参数

    /**
     * 标量 / 集合参数：只看这个方法自己的调用点，实参是不是字面量 null。这里不用往上追——
     * 上层把自己的形参转发下来时，实参表达式本身就不是 null 字面量，已经算 ASSIGNED 了。
     * <p>
     * 追不出结论时返回 NOT_ANALYZED 而不是 UNKNOWN，这两者的区别在这里很要紧：声明参数的
     * DAL-001 是代码自身就能证实的契约不符（方法签名声明了它、对应 SQL 没用它），成立与否
     * 不依赖上游证据，上游分析只是额外决定要不要升置信度 / 判定为死参数直接排除。把"查不到
     * 调用点"的声明参数丢进"证据不足待人工确认"是误伤——它的证据本来就不在调用链上。
     */
    private Evidence analyzeDeclaredParam(PsiParameter param) {
        if (!(param.getDeclarationScope() instanceof PsiMethod method)) {
            return Evidence.notAnalyzed();
        }
        int index = method.getParameterList().getParameterIndex(param);
        if (index < 0) {
            return Evidence.notAnalyzed();
        }
        CallSites sites = callSites(method);
        boolean anyNull = false;
        for (PsiMethodCallExpression call : sites.calls()) {
            ProgressManager.checkCanceled();
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (index >= args.length) {
                continue;
            }
            if (isNullLiteral(args[index])) {
                anyNull = true;
            } else {
                return new Evidence(UpstreamStatus.ASSIGNED, Locations.of(call).display()); // 一处真赋值就够
            }
        }
        // 调用点没看全就不敢说"从来没给过真值"——没看到的那些里可能就有
        return anyNull && !sites.truncated() ? new Evidence(UpstreamStatus.NOT_ASSIGNED, null) : Evidence.notAnalyzed();
    }

    // ---------------------------------------------------------------- 实体属性

    private Evidence analyzeProperty(PsiParameter param, PsiClass owner, String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        String prop = dot < 0 ? propertyPath : propertyPath.substring(dot + 1);

        Evidence precise = analyzePropertyByCallChain(param, prop);
        if (precise.status() != UpstreamStatus.UNKNOWN) {
            return precise;
        }
        String fqn = owner.getQualifiedName();
        if (fqn != null) {
            SetterEvidence recorded = setterUsages.get(fqn + "#" + prop);
            if (recorded != null && recorded.anyRealValue()) {
                // 项目级表只当"有人给过真值"的旁证；它不区分调用链，反过来说"从没给过"是不可信的，
                // 那种情况交给上面的沿链追踪去下结论，这里不拿它排除问题
                return new Evidence(UpstreamStatus.ASSIGNED, recorded.exampleLocation());
            }
        }
        // 追不到直接赋值的证据：如果这个实体曾经是反射拷贝（如 BeanUtils.copyProperties）的目标，
        // 值也可能是这么来的，静态分析看不到——不下结论，但把这层可能性说清楚
        boolean reflective = precise.reflectiveCopyHint() || (fqn != null && reflectiveCopyTargets.contains(fqn));
        return reflective ? Evidence.unknownReflective() : Evidence.unknown();
    }

    /** 从 DAO 方法这一层开始，沿调用链往上追这个实体是在哪儿构造、有没有被 set 过 prop。 */
    private Evidence analyzePropertyByCallChain(PsiParameter param, String prop) {
        if (!(param.getDeclarationScope() instanceof PsiMethod method)) {
            return Evidence.unknown();
        }
        int index = method.getParameterList().getParameterIndex(param);
        return index < 0 ? Evidence.unknown() : traceUpFromParameter(method, index, prop, 0, new HashSet<>());
    }

    /**
     * {@code owner} 的第 {@code index} 个形参就是那个实体：去看每一个调用点传的是什么，
     * 传的还是调用方自己的形参就再往上一层。
     * <p>
     * 只有"每个调用点都追出了确定结论、且没有一处赋真值"才返回 NOT_ASSIGNED（会导致静默排除，
     * 必须严）；有一处没看懂、调用点被截断、或者没有任何调用点（已经是入口方法却还没见到构造点），
     * 一律 UNKNOWN。
     */
    private Evidence traceUpFromParameter(PsiMethod owner, int index, String prop, int depth, Set<PsiMethod> visiting) {
        if (index < 0 || depth >= MAX_CHAIN_DEPTH || !visiting.add(owner)) {
            return Evidence.unknown(); // 层数到顶，或者递归 / 环形调用，不再往上
        }
        try {
            CallSites sites = callSites(owner);
            if (sites.truncated() || sites.calls().isEmpty()) {
                return Evidence.unknown();
            }
            boolean allNotAssigned = true;
            boolean reflective = false;
            for (PsiMethodCallExpression call : sites.calls()) {
                ProgressManager.checkCanceled();
                PsiExpression[] args = call.getArgumentList().getExpressions();
                if (index >= args.length) {
                    allNotAssigned = false; // 变参 / 签名对不上，不猜
                    continue;
                }
                Evidence e = traceArgument(args[index], call, prop, depth, visiting);
                if (e.status() == UpstreamStatus.ASSIGNED) {
                    return e; // 找到一处真赋值就够了，早停
                }
                if (e.status() != UpstreamStatus.NOT_ASSIGNED) {
                    allNotAssigned = false;
                    reflective |= e.reflectiveCopyHint();
                }
            }
            if (allNotAssigned) {
                return new Evidence(UpstreamStatus.NOT_ASSIGNED, null);
            }
            return reflective ? Evidence.unknownReflective() : Evidence.unknown();
        } finally {
            visiting.remove(owner);
        }
    }

    /** 一个调用点上的实参是从哪儿来的：局部 new 出来的就地看 setter，是调用方的形参就继续往上。 */
    private Evidence traceArgument(PsiExpression argument, PsiElement callSite, String prop, int depth, Set<PsiMethod> visiting) {
        PsiExpression e = unwrap(argument);
        if (!(e instanceof PsiReferenceExpression ref)) {
            return Evidence.unknown(); // 内联 new、方法返回值、三元…… 追不出，也不当反例
        }
        PsiElement resolved = ref.resolve();
        if (resolved instanceof PsiLocalVariable var) {
            return traceLocalBean(var, ref, callSite, prop);
        }
        if (resolved instanceof PsiParameter p && p.getDeclarationScope() instanceof PsiMethod caller) {
            return traceUpFromParameter(caller, caller.getParameterList().getParameterIndex(p), prop, depth + 1, visiting);
        }
        return Evidence.unknown(); // 字段 / 静态常量：来源可能在任何地方（含 Spring 注入），不敢下结论
    }

    /**
     * 只追"局部变量 = new Bean()，调用前的若干 setX(...)"这一种形态——和 {@code JavaRules.isFreshEmptyObject}
     * 同一类写法判定，但目的不同：这里要具体看 prop 这一个属性有没有被设过、设的是不是字面量 null。
     * <p>
     * 只要结构上认得出是这种写法，扫完调用点之前的整段作用域后必有定论——要么扫到一次真赋值
     * （ASSIGNED），要么扫完都没有（NOT_ASSIGNED：new 出来的对象没人碰过这个属性，或者只碰过
     * null，结论一样都是"没给过真值"）。变量中途被重新赋值、或整个传给了别的方法（那边可能填充它，
     * 反射拷贝就是最常见的一种）时返回 UNKNOWN，不猜、也不当反例。
     */
    private Evidence traceLocalBean(PsiLocalVariable var, PsiReferenceExpression selfRef, PsiElement callSite, String prop) {
        PsiExpression init = unwrap(var.getInitializer());
        if (!(init instanceof PsiNewExpression n) || n.getAnonymousClass() != null
                || n.getArgumentList() == null || n.getArgumentList().getExpressionCount() != 0) {
            return Evidence.unknown(); // 不是简单的 new Bean()，追不出
        }
        PsiCodeBlock body = PsiTreeUtil.getParentOfType(var, PsiCodeBlock.class);
        if (body == null) {
            return Evidence.unknown();
        }
        int limit = callSite.getTextRange().getStartOffset();
        for (PsiReferenceExpression use : PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class)) {
            if (use.getTextRange().getStartOffset() >= limit || !use.isReferenceTo(var) || use == selfRef) {
                continue;
            }
            PsiElement parent = use.getParent();
            if (parent instanceof PsiReferenceExpression mref && mref.getQualifierExpression() == use
                    && mref.getParent() instanceof PsiMethodCallExpression call
                    && call.getArgumentList().getExpressionCount() == 1) {
                String p = ParameterNameNormalizer.setterToProperty(mref.getReferenceName());
                if (prop.equals(p) && !isNullLiteral(call.getArgumentList().getExpressions()[0])) {
                    return new Evidence(UpstreamStatus.ASSIGNED, Locations.of(callSite).display());
                }
                continue; // 别的属性的 setter，或者是这个属性但传了 null：继续找，不打断
            }
            if (parent instanceof PsiAssignmentExpression assign && assign.getLExpression() == use) {
                return Evidence.unknown(); // 变量被重新赋值，简单构造的假设不成立
            }
            if (parent instanceof PsiExpressionList) {
                // 整个对象被传给别的方法，可能在那边被填充；是 copyProperties 之类就把原因说清楚
                return isCopyCall(parent) ? Evidence.unknownReflective() : Evidence.unknown();
            }
        }
        return new Evidence(UpstreamStatus.NOT_ASSIGNED, null);
    }

    private static boolean isCopyCall(PsiElement argumentList) {
        return argumentList.getParent() instanceof PsiMethodCallExpression call
                && JavaRules.isCopyMethodName(call.getMethodExpression().getReferenceName());
    }

    // ---------------------------------------------------------------- 调用点搜索

    /** 一个方法的调用点。{@code truncated} 表示"没看全"，此时任何"从来没赋过值"的结论都不成立。 */
    private record CallSites(List<PsiMethodCallExpression> calls, boolean truncated) {
        static final CallSites BUDGET_EXHAUSTED = new CallSites(List.of(), true);
    }

    private CallSites callSites(PsiMethod method) {
        CallSites cached = callSitesByMethod.get(method);
        if (cached != null) {
            return cached;
        }
        CallSites found = searchedMethods >= MAX_SEARCHED_METHODS ? CallSites.BUDGET_EXHAUSTED : findCallSites(method);
        searchedMethods++;
        callSitesByMethod.put(method, found);
        return found;
    }

    /**
     * 连同它覆写的每一层方法一起搜：Spring 分层里真正的调用点大多经接口类型引用，resolve() 落在
     * 接口方法上而不是实现类方法上，只搜实现类会把这些调用点全漏掉（调用链功能上线时真机报过）。
     * 多搜出来的调用点（接口有多个实现时，别的实现的调用方）只会让结论更保守，不会更激进。
     */
    private CallSites findCallSites(PsiMethod method) {
        List<PsiMethodCallExpression> out = new ArrayList<>();
        Set<PsiMethodCallExpression> seen = new HashSet<>();
        boolean[] truncated = {false};
        for (PsiMethod target : CallChainFinder.searchTargets(method)) {
            if (truncated[0]) {
                break;
            }
            MethodReferencesSearch.search(target, callSiteScope, true).forEach(ref -> {
                ProgressManager.checkCanceled();
                PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethodCallExpression.class, false);
                if (call != null && seen.add(call)) {
                    out.add(call);
                }
                if (out.size() >= MAX_CALL_SITES_PER_METHOD) {
                    truncated[0] = true;
                    return false;
                }
                return true;
            });
        }
        return new CallSites(out, truncated[0]);
    }

    // ---------------------------------------------------------------- 小工具

    private static @Nullable PsiExpression unwrap(@Nullable PsiExpression e) {
        while (true) {
            if (e instanceof PsiParenthesizedExpression p) {
                e = p.getExpression();
            } else if (e instanceof PsiTypeCastExpression c) {
                e = c.getOperand();
            } else {
                return e;
            }
        }
    }

    private static boolean isNullLiteral(@Nullable PsiExpression e) {
        e = unwrap(e);
        return e instanceof PsiLiteralExpression lit && lit.getValue() == null;
    }

    private record Evidence(UpstreamStatus status, @Nullable String example, boolean reflectiveCopyHint) {
        Evidence(UpstreamStatus status, @Nullable String example) {
            this(status, example, false);
        }

        static Evidence unknown() {
            return new Evidence(UpstreamStatus.UNKNOWN, null);
        }

        /** 这条问题不依赖上游证据（声明参数），或压根没法分析：原样保留，别打"证据不足"的标签。 */
        static Evidence notAnalyzed() {
            return new Evidence(UpstreamStatus.NOT_ANALYZED, null);
        }

        /** 没追到直接赋值证据，但沿途或别处见过反射拷贝——提示可能是这个原因。 */
        static Evidence unknownReflective() {
            return new Evidence(UpstreamStatus.UNKNOWN, null, true);
        }
    }
}
