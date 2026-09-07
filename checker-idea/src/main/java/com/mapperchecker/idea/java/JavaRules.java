package com.mapperchecker.idea.java;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.contract.RuleOptions;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.naming.NameSimilarity;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 团队规范里的纯 Java 规则。方案《开发规范：Query 与 Mapper 绑定》第二、三、四节。
 * <ul>
 *   <li>DAL-022 DAO 方法的 Query 参数 @Param 未统一为 query（按 Mapper 接口方法检查）</li>
 *   <li>DAL-020 转换方法内使用反射拷贝（按文件检查）</li>
 *   <li>DAL-004 copyProperties 参数顺序写反，空对象被当作拷贝源（按文件检查）</li>
 *   <li>DAL-030 跨层赋值 setA(getB()) 改了字段名（按文件检查）</li>
 *   <li>DAL-021 跨模块同名 Query 类（全局）</li>
 * </ul>
 * 所有方法必须在 ReadAction 内调用。发现的问题通过 {@code reporter} 回调交出去，
 * 既能进报告窗口，也能给实时 Inspection 用。
 */
public final class JavaRules {

    /** 问题回调：问题 + Java 侧锚点。 */
    public interface Reporter extends BiConsumer<ContractIssue, PsiElement> {
    }

    private static final Set<String> COPY_METHOD_NAMES = Set.of("copyProperties", "copy", "copyBean", "populate");

    private final Project project;
    private final CheckSettings settings;
    private final RuleOptions rules;
    private final Reporter reporter;
    /** 被反射拷贝当作目标的类全限定名，可为 null（实时 Inspection 不需要）。 */
    private final @Nullable Set<String> reflectiveCopyTargets;
    /** 扫描过程中顺带登记的 setter 调用："实体全限定名#属性" → 调用位置，供 DAL-010 免去引用搜索。 */
    private final @Nullable Map<String, String> setterUsages;
    private @Nullable QueryClassIndex queryClassIndex;

    public JavaRules(@NotNull Project project, @NotNull CheckSettings settings, @NotNull Reporter reporter) {
        this(project, settings, reporter, null, null);
    }

    public JavaRules(@NotNull Project project, @NotNull CheckSettings settings, @NotNull Reporter reporter,
                     @Nullable Set<String> reflectiveCopyTargets) {
        this(project, settings, reporter, reflectiveCopyTargets, null);
    }

    public JavaRules(@NotNull Project project, @NotNull CheckSettings settings, @NotNull Reporter reporter,
                     @Nullable Set<String> reflectiveCopyTargets, @Nullable Map<String, String> setterUsages) {
        this.project = project;
        this.settings = settings;
        this.rules = settings.rules();
        this.reporter = reporter;
        this.reflectiveCopyTargets = reflectiveCopyTargets;
        this.setterUsages = setterUsages;
    }

    /** 共享的 Query 类清单（只算一次）。 */
    public @NotNull QueryClassIndex queryClassIndex() {
        if (queryClassIndex == null) {
            queryClassIndex = new QueryClassIndex(project, rules);
        }
        return queryClassIndex;
    }

    // ---------------------------------------------------------------- DAL-022

    /** Mapper 接口方法上的 Query 参数必须 @Param("query")。 */
    public void checkQueryParamNaming(@NotNull PsiMethod method, @NotNull String statementId) {
        if (!settings.isEnabled(RuleId.DAL_022)) {
            return;
        }
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (!isQueryType(p.getType())) {
                continue;
            }
            String current = paramAnnotationValue(p);
            if ("query".equals(current)) {
                continue;
            }
            String shown = current == null ? MapperCheckerBundle.message("issue.DAL-022.none") : "@Param(\"" + current + "\")";
            String message = MapperCheckerBundle.message("issue.DAL-022", shortStatement(statementId), p.getName(), shown);
            report(RuleId.DAL_022, Confidence.HIGH, message, "", p.getName(), statementId, p);
        }
    }

    // ---------------------------------------------------------------- 按文件：DAL-020 / DAL-004 / DAL-030

    public void checkJavaFile(@NotNull PsiFile file) {
        if (!(file instanceof PsiJavaFile)) {
            return;
        }
        boolean want020 = settings.isEnabled(RuleId.DAL_020);
        boolean want004 = settings.isEnabled(RuleId.DAL_004);
        boolean want030 = settings.isEnabled(RuleId.DAL_030);
        if (!want020 && !want004 && !want030) {
            return;
        }
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            ProgressManager.checkCanceled();
            checkMethodCall(call);
        }
    }

    /** 单个方法调用上的 DAL-020 / DAL-004 / DAL-030，实时 Inspection 按元素访问时也走这里。 */
    public void checkMethodCall(@NotNull PsiMethodCallExpression call) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) {
            return;
        }
        if (COPY_METHOD_NAMES.contains(name)) {
            recordCopyTargets(call);
            if (settings.isEnabled(RuleId.DAL_020)) {
                checkReflectiveCopyInTransform(call);
            }
            if (settings.isEnabled(RuleId.DAL_004)) {
                checkCopyArgumentOrder(call);
            }
        } else if (name.startsWith("set") && call.getArgumentList().getExpressionCount() == 1) {
            recordSetterUsage(call, name);
            if (settings.isEnabled(RuleId.DAL_030)) {
                checkCrossLayerRename(call);
            }
        }
    }

    /**
     * 登记 {@code x.setFoo(v)}：DAL-010 要判断"有没有人 set 过"，逐属性做全项目引用搜索代价极高
     * （setStatus 这类名字满项目都是，每个候选文件都要解析、解引用）。这里在本来就要遍历的文件上顺手记下来，
     * 让 DAL-010 走查表；查不到的少数属性才回退到引用搜索。
     */
    private void recordSetterUsage(PsiMethodCallExpression call, String setterName) {
        if (setterUsages == null) {
            return;
        }
        String prop = ParameterNameNormalizer.setterToProperty(setterName);
        if (prop == null || prop.isEmpty()) {
            return;
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        PsiClass owner;
        if (qualifier != null) {
            // 用限定符的声明类型，比解引用方法便宜
            owner = BeanPropertyCollector.expandableBeanClass(qualifier.getType());
        } else {
            PsiMethod enclosing = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
            owner = enclosing == null ? null : enclosing.getContainingClass();
        }
        if (owner == null || owner.getQualifiedName() == null) {
            return;
        }
        setterUsages.putIfAbsent(owner.getQualifiedName() + "#" + prop, Locations.of(call).display());
    }

    /** 记录反射拷贝的目标类型：已登记参数顺序的按目标位置取，未登记的两个实参都算。 */
    private void recordCopyTargets(PsiMethodCallExpression call) {
        if (reflectiveCopyTargets == null) {
            return;
        }
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length < 2) {
            return;
        }
        PsiMethod target = call.resolveMethod();
        int sourceIndex = target == null || target.getContainingClass() == null || target.getContainingClass().getQualifiedName() == null
                ? -1 : rules.copySourceIndex(target.getContainingClass().getQualifiedName(), target.getName());
        for (int i = 0; i < 2; i++) {
            if (sourceIndex >= 0 && i == sourceIndex) {
                continue;
            }
            PsiClass cls = BeanPropertyCollector.expandableBeanClass(args[i].getType());
            if (cls != null && cls.getQualifiedName() != null) {
                reflectiveCopyTargets.add(cls.getQualifiedName());
            }
        }
    }

    /** DAL-020：转换方法内的反射拷贝。 */
    private void checkReflectiveCopyInTransform(PsiMethodCallExpression call) {
        PsiMethod enclosing = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        if (enclosing == null || !isTransformMethod(enclosing)) {
            return;
        }
        PsiMethod target = call.resolveMethod();
        String callee = target != null && target.getContainingClass() != null
                ? target.getContainingClass().getName() + "." + target.getName() + "()"
                : call.getMethodExpression().getText() + "()";
        String owner = describe(enclosing);
        String message = MapperCheckerBundle.message("issue.DAL-020", owner, callee);
        report(RuleId.DAL_020, Confidence.HIGH, message, "", callee, owner, call);
    }

    /** 转换方法：返回 Query 类，或方法名像 transform/convert/to 且返回项目里的 Bean。 */
    boolean isTransformMethod(PsiMethod m) {
        PsiType rt = m.getReturnType();
        if (rt == null || com.intellij.psi.PsiTypes.voidType().equals(rt)) {
            return false;
        }
        if (isQueryType(rt)) {
            return true;
        }
        return rules.looksLikeTransformName(m.getName()) && BeanPropertyCollector.expandableBeanClass(rt) != null;
    }

    /** DAL-004：源对象位置传入刚 new 出来的空对象。 */
    private void checkCopyArgumentOrder(PsiMethodCallExpression call) {
        PsiMethod target = call.resolveMethod();
        if (target == null || target.getContainingClass() == null || target.getContainingClass().getQualifiedName() == null) {
            return;
        }
        int sourceIndex = rules.copySourceIndex(target.getContainingClass().getQualifiedName(), target.getName());
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (sourceIndex < 0 || args.length < 2) {
            return;
        }
        PsiExpression source = args[sourceIndex];
        if (!isFreshEmptyObject(source, call)) {
            return;
        }
        String callee = target.getContainingClass().getName() + "." + target.getName();
        String message = MapperCheckerBundle.message("issue.DAL-004", callee, source.getText());
        PsiMethod enclosing = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        report(RuleId.DAL_004, Confidence.HIGH, message, "", source.getText(),
                enclosing == null ? callee : describe(enclosing), source);
    }

    /**
     * 是否为"刚 new 出来还没设过任何值"的对象：直接 {@code new X()}，
     * 或引用一个以无参 {@code new X()} 初始化、且在此调用之前没有任何方法调用和再赋值的局部变量。
     */
    static boolean isFreshEmptyObject(PsiExpression expr, PsiMethodCallExpression at) {
        PsiExpression e = unwrap(expr);
        if (e instanceof PsiNewExpression n) {
            return n.getAnonymousClass() == null && n.getArgumentList() != null
                    && n.getArgumentList().getExpressionCount() == 0 && n.getArrayInitializer() == null;
        }
        if (!(e instanceof PsiReferenceExpression ref) || !(ref.resolve() instanceof PsiLocalVariable var)) {
            return false;
        }
        PsiExpression init = unwrap(var.getInitializer());
        if (!(init instanceof PsiNewExpression n) || n.getAnonymousClass() != null
                || n.getArgumentList() == null || n.getArgumentList().getExpressionCount() != 0) {
            return false;
        }
        PsiCodeBlock body = PsiTreeUtil.getParentOfType(var, PsiCodeBlock.class);
        if (body == null) {
            return false;
        }
        int limit = at.getTextRange().getStartOffset();
        for (PsiReferenceExpression use : PsiTreeUtil.findChildrenOfType(body, PsiReferenceExpression.class)) {
            if (use.getTextRange().getStartOffset() >= limit || !use.isReferenceTo(var) || use == e) {
                continue;
            }
            PsiElement parent = use.getParent();
            // var.setX(...) / var.foo(...)：已经被填过值
            if (parent instanceof PsiReferenceExpression mref && mref.getQualifierExpression() == use
                    && mref.getParent() instanceof PsiMethodCallExpression) {
                return false;
            }
            // var = ...：被重新赋值
            if (parent instanceof PsiAssignmentExpression assign && assign.getLExpression() == use) {
                return false;
            }
            // 传给了别的方法：可能在里面被填
            if (parent instanceof com.intellij.psi.PsiExpressionList) {
                return false;
            }
        }
        return true;
    }

    /** DAL-030：setA(x.getB()) 且 A、B 高度相似但不同。 */
    private void checkCrossLayerRename(PsiMethodCallExpression setterCall) {
        String setterName = setterCall.getMethodExpression().getReferenceName();
        String setProp = ParameterNameNormalizer.setterToProperty(setterName);
        if (setProp == null) {
            return;
        }
        PsiExpression arg = unwrap(setterCall.getArgumentList().getExpressions()[0]);
        if (!(arg instanceof PsiMethodCallExpression getterCall) || getterCall.getArgumentList().getExpressionCount() != 0) {
            return;
        }
        String getProp = getterToProperty(getterCall.getMethodExpression().getReferenceName());
        if (getProp == null) {
            return;
        }
        // 只看项目源码里的实体（setter 的 receiver 或 getter 的 receiver 任一是项目类即可）
        if (!isProjectBeanReceiver(setterCall) && !isProjectBeanReceiver(getterCall)) {
            return;
        }
        NameSimilarity.Kind kind = NameSimilarity.classify(setProp, getProp);
        String key;
        if (kind == NameSimilarity.Kind.TYPO) {
            key = "issue.DAL-030.typo";
        } else if (kind == NameSimilarity.Kind.PLURAL) {
            key = "issue.DAL-030.plural";
        } else {
            return;
        }
        PsiMethod enclosing = PsiTreeUtil.getParentOfType(setterCall, PsiMethod.class);
        String message = MapperCheckerBundle.message(key, setterName, getterCall.getMethodExpression().getReferenceName());
        PsiElement anchor = setterCall.getMethodExpression().getReferenceNameElement();
        report(RuleId.DAL_030, Confidence.MEDIUM, message, "", setProp + "<-" + getProp,
                enclosing == null ? "" : describe(enclosing), anchor == null ? setterCall : anchor);
    }

    private static @Nullable String getterToProperty(@Nullable String name) {
        if (name == null) {
            return null;
        }
        String rest;
        if (name.startsWith("get") && name.length() > 3) {
            rest = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2) {
            rest = name.substring(2);
        } else {
            return null;
        }
        if (!Character.isUpperCase(rest.charAt(0))) {
            return null;
        }
        return ParameterNameNormalizer.decapitalize(rest);
    }

    private boolean isProjectBeanReceiver(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        PsiType type = q == null ? null : q.getType();
        if (type != null && BeanPropertyCollector.expandableBeanClass(type) != null) {
            return true;
        }
        PsiMethod m = call.resolveMethod();
        PsiClass owner = m == null ? null : m.getContainingClass();
        if (owner == null) {
            return false;
        }
        PsiFile f = owner.getContainingFile();
        VirtualFile vf = f == null ? null : f.getVirtualFile();
        return vf != null && ProjectFileIndex.getInstance(project).isInSourceContent(vf);
    }

    // ---------------------------------------------------------------- DAL-021（全局）

    /** 同一简单类名的 Query 类出现多个（不同全限定名）。 */
    public void checkDuplicateQueryClasses(@NotNull GlobalSearchScope scope) {
        if (!settings.isEnabled(RuleId.DAL_021)) {
            return;
        }
        for (Map.Entry<String, List<PsiClass>> named : queryClassIndex().sourceQueryClasses(scope).entrySet()) {
            String name = named.getKey();
            ProgressManager.checkCanceled();
            Map<String, PsiClass> byFqn = new LinkedHashMap<>();
            for (PsiClass cls : named.getValue()) {
                byFqn.putIfAbsent(cls.getQualifiedName(), cls);
            }
            if (byFqn.size() < 2) {
                continue;
            }
            List<String> where = new ArrayList<>();
            for (PsiClass cls : byFqn.values()) {
                String module = Locations.moduleNameOf(cls);
                where.add((module.isEmpty() ? "" : module + " / ") + cls.getQualifiedName());
            }
            String message = MapperCheckerBundle.message("issue.DAL-021", name, String.join("；", where));
            for (PsiClass cls : byFqn.values()) {
                PsiElement anchor = cls.getNameIdentifier() == null ? cls : cls.getNameIdentifier();
                report(RuleId.DAL_021, Confidence.HIGH, message, "", "", cls.getQualifiedName(), anchor);
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    boolean isQueryType(@Nullable PsiType type) {
        if (!(type instanceof PsiClassType ct)) {
            return false;
        }
        PsiClass cls = ct.resolve();
        String simple = cls != null ? cls.getName() : ct.getClassName();
        return rules.isQueryClassName(simple);
    }

    static @Nullable String paramAnnotationValue(@NotNull PsiParameter p) {
        PsiAnnotation a = p.getAnnotation(MyBatisNames.PARAM);
        if (a == null) {
            return null;
        }
        PsiAnnotationMemberValue v = a.findAttributeValue("value");
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            return s;
        }
        return "";
    }

    private void report(RuleId rule, Confidence confidence, String message, String remark,
                        String parameterName, String statementId, PsiElement anchor) {
        ContractIssue issue = new ContractIssue(rule, settings.severityOf(rule), confidence, message, remark,
                parameterName, statementId, List.of(), Locations.of(anchor), SourceLocation.UNKNOWN, List.of());
        reporter.accept(issue, anchor);
    }

    static String describe(PsiMethod m) {
        PsiClass c = m.getContainingClass();
        return (c == null || c.getQualifiedName() == null ? "" : c.getQualifiedName() + ".") + m.getName();
    }

    private static String shortStatement(String fullId) {
        int dot = fullId.lastIndexOf('.');
        if (dot <= 0) {
            return fullId;
        }
        int prev = fullId.lastIndexOf('.', dot - 1);
        return prev < 0 ? fullId : fullId.substring(prev + 1);
    }

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
}
