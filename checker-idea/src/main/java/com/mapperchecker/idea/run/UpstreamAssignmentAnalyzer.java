package com.mapperchecker.idea.run;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.UpstreamStatus;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.java.SetterEvidence;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

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
 *       真机反馈双击应该落到"对应的 DAO 方法"，锚点已改成该方法里声明这个实体的参数（见
 *       {@code ContractCheckEngine.anchorForBeanParam}），这里按参数的<b>类型</b>取实体类，
 *       查扫描阶段登记的 {@link SetterEvidence} 表（与 DAL-010 共用）——不能反过来从锚点向上找
 *       外层 PsiClass，那样找到的是 Mapper 接口本身，不是实体类</li>
 *   <li>其余（理论上只有"多个 Bean 参数、猜不出对应哪个"这种极少数兜底到方法名的情形）：
 *       退化为 UNKNOWN，不下结论</li>
 * </ul>
 * 判定很朴素：只把字面量 {@code null} 当"没给值"，其余（变量、方法调用、new 出来的对象……）
 * 一律当"给了值"。静态分析看不出变量运行时是不是恰好是 null，宁可漏报，不装作能看穿数据流。
 * <p>
 * 已知限制：实体属性若继承自父类，setterUsages 的 key 用的是调用点限定符的<b>声明类型</b>，
 * 与参数声明的类型不是同一个 FQN，会查不到——退化为 UNKNOWN，不影响正确性，只是少一条备注。
 */
final class UpstreamAssignmentAnalyzer {

    private final GlobalSearchScope callSiteScope;
    private final Map<String, SetterEvidence> setterUsages;

    UpstreamAssignmentAnalyzer(@NotNull GlobalSearchScope callSiteScope, @NotNull Map<String, SetterEvidence> setterUsages) {
        this.callSiteScope = callSiteScope;
        this.setterUsages = setterUsages;
    }

    @NotNull ReportedIssue adjust(@NotNull ReportedIssue ri) {
        ContractIssue issue = ri.issue();
        if (issue.ruleId() != RuleId.DAL_001) {
            return ri;
        }
        PsiElement anchor = ri.javaElement();
        if (anchor == null || !anchor.isValid()) {
            return ri;
        }
        ProgressManager.checkCanceled();
        Evidence evidence = evidenceFor(anchor, issue.parameterName());
        if (evidence.status() == UpstreamStatus.UNKNOWN) {
            return ri;
        }
        ContractIssue adjusted = applyStatus(issue, evidence);
        return new ReportedIssue(adjusted, ri.javaAnchor(), ri.mapperAnchor(), ri.moduleName());
    }

    private ContractIssue applyStatus(ContractIssue issue, Evidence evidence) {
        boolean assigned = evidence.status() == UpstreamStatus.ASSIGNED;
        Confidence confidence = assigned ? issue.confidence().raise() : issue.confidence().lower();
        String extra = assigned
                ? MapperCheckerBundle.message("remark.upstream.assigned", evidence.example())
                : MapperCheckerBundle.message("remark.upstream.not_assigned");
        String remark = issue.remark().isEmpty() ? extra : issue.remark() + " " + extra;
        return new ContractIssue(issue.ruleId(), issue.severity(), confidence, issue.message(), remark,
                issue.parameterName(), issue.statementId(), issue.callPath(), issue.primaryLocation(),
                issue.secondaryLocation(), issue.candidates());
    }

    /** 按锚点形状分流：Bean 类型的参数是实体属性走 setterUsages，其余（标量 / 集合 / 数组）是声明参数走调用点。 */
    private Evidence evidenceFor(PsiElement anchor, String propertyPath) {
        if (!(anchor instanceof PsiParameter param)) {
            return Evidence.unknown(); // 极少数兜底到方法名的情形，不猜
        }
        PsiClass beanClass = com.mapperchecker.idea.java.BeanPropertyCollector.expandableBeanClass(param.getType());
        return beanClass != null ? analyzeProperty(beanClass, propertyPath) : analyzeDeclaredParam(param);
    }

    // ---------------------------------------------------------------- 声明的方法参数

    private Evidence analyzeDeclaredParam(PsiParameter param) {
        PsiElement scope = param.getDeclarationScope();
        if (!(scope instanceof PsiMethod method)) {
            return Evidence.unknown();
        }
        int index = method.getParameterList().getParameterIndex(param);
        if (index < 0) {
            return Evidence.unknown();
        }
        boolean[] assigned = {false};
        boolean[] notAssigned = {false};
        String[] example = {null};
        MethodReferencesSearch.search(method, callSiteScope, true).forEach(ref -> {
            ProgressManager.checkCanceled();
            PsiElement e = ref.getElement();
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(e, PsiMethodCallExpression.class, false);
            if (call == null) {
                return true;
            }
            PsiExpression[] args = call.getArgumentList().getExpressions();
            if (index >= args.length) {
                return true;
            }
            if (isNullLiteral(args[index])) {
                notAssigned[0] = true;
            } else {
                assigned[0] = true;
                example[0] = Locations.of(call).display();
                return false; // 找到一处真赋值就够了，早停
            }
            return true;
        });
        if (assigned[0]) {
            return new Evidence(UpstreamStatus.ASSIGNED, example[0]);
        }
        if (notAssigned[0]) {
            return new Evidence(UpstreamStatus.NOT_ASSIGNED, null);
        }
        return Evidence.unknown();
    }

    // ---------------------------------------------------------------- 实体属性

    private Evidence analyzeProperty(PsiClass owner, String propertyPath) {
        if (owner.getQualifiedName() == null) {
            return Evidence.unknown();
        }
        int dot = propertyPath.lastIndexOf('.');
        String prop = dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
        SetterEvidence recorded = setterUsages.get(owner.getQualifiedName() + "#" + prop);
        if (recorded == null) {
            return Evidence.unknown();
        }
        return recorded.anyRealValue()
                ? new Evidence(UpstreamStatus.ASSIGNED, recorded.exampleLocation())
                : new Evidence(UpstreamStatus.NOT_ASSIGNED, null);
    }

    private static boolean isNullLiteral(@Nullable PsiExpression e) {
        while (e instanceof PsiParenthesizedExpression p) {
            e = p.getExpression();
        }
        while (e instanceof PsiTypeCastExpression c) {
            e = c.getOperand();
        }
        return e instanceof PsiLiteralExpression lit && lit.getValue() == null;
    }

    private record Evidence(UpstreamStatus status, @Nullable String example) {
        static Evidence unknown() {
            return new Evidence(UpstreamStatus.UNKNOWN, null);
        }
    }
}
