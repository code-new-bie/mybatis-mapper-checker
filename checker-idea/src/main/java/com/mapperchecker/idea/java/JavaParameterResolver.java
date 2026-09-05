package com.mapperchecker.idea.java;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiLocalVariable;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.UnresolvedReason;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数对象数据流追踪：Map / Bean，方法内 + 有界跨方法。方案第 10 节。
 * <p>
 * 原则：追踪到哪一步能证明就报到哪一步，证明不了就停（UNRESOLVED）。
 * 一个实例对应一次检查运行，{@link #builderCache} 随实例销毁。
 */
public final class JavaParameterResolver {

    private static final Set<String> MAP_FACTORY_QUALIFIERS = Set.of("Map", "ImmutableMap", "Maps");
    private static final Set<String> PUT_LIKE = Set.of("put", "putIfAbsent", "computeIfAbsent", "merge");

    private final Project project;
    private final int maxDepth;
    /** 参数构造方法 → 追踪结果（跨方法缓存，运行级）。 */
    private final Map<PsiMethod, TraceResult> builderCache = new LinkedHashMap<>();

    public JavaParameterResolver(@NotNull Project project, int maxDepth) {
        this.project = project;
        this.maxDepth = maxDepth <= 0 ? 3 : maxDepth;
    }

    /**
     * 追踪一个实参表达式。
     *
     * @param argument 传给 DAO 调用的参数对象表达式
     * @param callSite DAO 调用表达式（用于判定"put 在调用之前"与"逃逸"）
     */
    public @NotNull TraceResult trace(@NotNull PsiExpression argument, @NotNull PsiElement callSite) {
        PsiMethod enclosing = PsiTreeUtil.getParentOfType(callSite, PsiMethod.class);
        Context ctx = new Context(enclosing, callSite, 0, new HashSet<>(), new ArrayList<>());
        return traceExpression(argument, ctx);
    }

    // ---------------------------------------------------------------- 上下文

    private record Context(@Nullable PsiMethod method, @Nullable PsiElement callSite, int depth,
                           Set<PsiMethod> visiting, List<String> callPath) {
        Context descend(PsiMethod callee) {
            List<String> path = new ArrayList<>(callPath);
            if (path.isEmpty() && method != null) {
                path.add(describe(method));
            }
            path.add(describe(callee));
            Set<PsiMethod> v = new HashSet<>(visiting);
            v.add(callee);
            return new Context(callee, null, depth + 1, v, path);
        }

        static String describe(PsiMethod m) {
            PsiClass c = m.getContainingClass();
            return (c == null || c.getName() == null ? "" : c.getName() + ".") + m.getName() + "()";
        }
    }

    // ---------------------------------------------------------------- 表达式分流

    private TraceResult traceExpression(@Nullable PsiExpression expr, Context ctx) {
        expr = unwrap(expr);
        if (expr == null) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, "");
        }
        PsiType type = expr.getType();
        if (MethodSignatureParameterResolver.isScalar(type)) {
            return TraceResult.notComparable();
        }
        if (expr instanceof PsiReferenceExpression ref) {
            PsiElement target = ref.resolve();
            if (target instanceof PsiLocalVariable local) {
                return traceLocalVariable(local, ctx);
            }
            if (target instanceof PsiParameter) {
                return TraceResult.unresolved(UnresolvedReason.METHOD_PARAM, ref.getText());
            }
            if (target instanceof PsiField) {
                return TraceResult.unresolved(UnresolvedReason.FIELD, ref.getText());
            }
            return TraceResult.unresolved(UnresolvedReason.OTHER, ref.getText());
        }
        if (expr instanceof PsiMethodCallExpression call) {
            return traceCall(call, ctx);
        }
        if (expr instanceof PsiNewExpression newExpr) {
            return traceNewExpression(newExpr, ctx);
        }
        return TraceResult.unresolved(UnresolvedReason.OTHER, expr.getText());
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

    // ---------------------------------------------------------------- 局部变量

    private TraceResult traceLocalVariable(PsiLocalVariable var, Context ctx) {
        PsiCodeBlock body = ctx.method() == null ? null : ctx.method().getBody();
        if (body == null) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, var.getName());
        }
        PsiType type = var.getType();
        boolean isMap = MethodSignatureParameterResolver.isMap(type);
        boolean isBean = !isMap && !MethodSignatureParameterResolver.isScalar(type)
                && !MethodSignatureParameterResolver.isCollection(type)
                && !(type instanceof com.intellij.psi.PsiArrayType);
        if (!isMap && !isBean) {
            return TraceResult.notComparable();
        }

        List<ParameterReference> params = new ArrayList<>();
        List<String> path = new ArrayList<>(ctx.callPath());

        // 初始化器与赋值右侧
        List<PsiExpression> sources = new ArrayList<>();
        if (var.getInitializer() != null) {
            sources.add(var.getInitializer());
        }
        for (PsiReferenceExpression use : usagesOf(var, body)) {
            if (use.getParent() instanceof PsiAssignmentExpression assign && assign.getLExpression() == use
                    && assign.getRExpression() != null && before(assign, ctx.callSite())) {
                sources.add(assign.getRExpression());
            }
        }
        if (sources.isEmpty()) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, var.getName());
        }
        boolean anySetterOrPut = false;
        for (PsiExpression src : sources) {
            SourceKind kind = classifySource(src);
            switch (kind) {
                case FRESH -> { /* 空容器，看后续 put / set */ }
                case BUILDER_CHAIN -> {
                    return TraceResult.unresolved(UnresolvedReason.BEAN_BUILDER, var.getName());
                }
                case OTHER -> {
                    TraceResult inner = traceExpression(src, ctx);
                    if (!inner.isResolved()) {
                        return inner.status() == TraceResult.Status.NOT_COMPARABLE
                                ? TraceResult.unresolved(UnresolvedReason.OTHER, var.getName()) : inner;
                    }
                    params.addAll(inner.parameters());
                    anySetterOrPut |= !inner.parameters().isEmpty();
                    if (path.isEmpty()) {
                        path.addAll(inner.callPath());
                    }
                }
            }
        }

        // 方法体内对该变量的使用：put / setX / 逃逸
        for (PsiReferenceExpression use : usagesOf(var, body)) {
            if (!before(use, ctx.callSite())) {
                continue;
            }
            PsiElement parent = use.getParent();
            if (parent instanceof PsiReferenceExpression methodRef
                    && methodRef.getParent() instanceof PsiMethodCallExpression call
                    && methodRef.getQualifierExpression() == use) {
                // var.xxx(...)
                if (isMap) {
                    TraceResult r = collectMapMutation(call, params, ctx);
                    if (r != null) {
                        return r;
                    }
                    anySetterOrPut = true;
                } else {
                    anySetterOrPut |= collectSetterChain(call, params);
                }
                continue;
            }
            if (parent instanceof PsiExpressionList argList && argList.getParent() instanceof PsiMethodCallExpression other
                    && other != ctx.callSite() && !isSelfOrEnclosingCallSite(other, ctx.callSite())
                    && !isReadOnlyConsumer(other)) {
                // 传给了别的方法：对方可能修改它，无法证明
                PsiMethod resolved = other.resolveMethod();
                return TraceResult.unresolved(UnresolvedReason.MAP_MUTATED,
                        resolved == null ? other.getMethodExpression().getText() + "()" : Context.describe(resolved));
            }
        }
        if (isBean && !anySetterOrPut) {
            return TraceResult.unresolved(UnresolvedReason.BEAN_NO_SETTER, var.getName());
        }
        return TraceResult.resolved(params, path);
    }

    /** 只读取实参内容的调用：other.putAll(var)、other.equals(var)、log(var) 之类。 */
    private static boolean isReadOnlyConsumer(PsiMethodCallExpression other) {
        String n = other.getMethodExpression().getReferenceName();
        return n != null && (n.equals("putAll") || n.equals("equals") || n.equals("containsKey")
                || n.equals("containsValue") || n.equals("addAll") || n.equals("copyOf")
                || n.equals("unmodifiableMap") || n.equals("valueOf") || n.equals("toString")
                || n.equals("debug") || n.equals("info") || n.equals("warn") || n.equals("error") || n.equals("trace")
                || n.equals("println") || n.equals("format") || n.equals("requireNonNull") || n.equals("isEmpty"));
    }

    private static boolean isSelfOrEnclosingCallSite(PsiMethodCallExpression call, @Nullable PsiElement callSite) {
        return callSite != null && (PsiTreeUtil.isAncestor(call, callSite, false) || PsiTreeUtil.isAncestor(callSite, call, false));
    }

    private enum SourceKind { FRESH, BUILDER_CHAIN, OTHER }

    /** 初始化器分类：空容器 / builder 链 / 其他（需要继续追踪）。 */
    private static SourceKind classifySource(PsiExpression src) {
        PsiExpression e = unwrap(src);
        if (e instanceof PsiNewExpression n) {
            if (n.getAnonymousClass() != null) {
                return SourceKind.OTHER; // 双花括号，走 traceNewExpression
            }
            return SourceKind.FRESH;
        }
        if (e instanceof PsiMethodCallExpression call) {
            String name = call.getMethodExpression().getReferenceName();
            String qualifier = qualifierName(call);
            if ("newHashMap".equals(name) || "newLinkedHashMap".equals(name) || "newTreeMap".equals(name)
                    || "newConcurrentMap".equals(name)) {
                return SourceKind.FRESH;
            }
            if ("build".equals(name) && chainContainsBuilder(call)) {
                return qualifierIsImmutableMapBuilder(call) ? SourceKind.OTHER : SourceKind.BUILDER_CHAIN;
            }
            if (qualifier != null && !MAP_FACTORY_QUALIFIERS.contains(qualifier) && "builder".equals(name)) {
                return SourceKind.BUILDER_CHAIN;
            }
        }
        return SourceKind.OTHER;
    }

    private static boolean chainContainsBuilder(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        while (q instanceof PsiMethodCallExpression qc) {
            if ("builder".equals(qc.getMethodExpression().getReferenceName())) {
                return true;
            }
            q = qc.getMethodExpression().getQualifierExpression();
        }
        return false;
    }

    private static boolean qualifierIsImmutableMapBuilder(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        while (q instanceof PsiMethodCallExpression qc) {
            if ("builder".equals(qc.getMethodExpression().getReferenceName())) {
                return "ImmutableMap".equals(qualifierName(qc));
            }
            q = qc.getMethodExpression().getQualifierExpression();
        }
        return false;
    }

    private static @Nullable String qualifierName(PsiMethodCallExpression call) {
        PsiExpression q = call.getMethodExpression().getQualifierExpression();
        if (q instanceof PsiReferenceExpression r) {
            return r.getReferenceName();
        }
        return null;
    }

    private static Collection<PsiReferenceExpression> usagesOf(PsiVariable var, PsiElement scope) {
        List<PsiReferenceExpression> out = new ArrayList<>();
        for (PsiReferenceExpression ref : PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression.class)) {
            if (ref.getQualifierExpression() == null && ref.isReferenceTo(var)) {
                out.add(ref);
            }
        }
        return out;
    }

    /** callSite 为 null（被调方法内）时不限制顺序。 */
    private static boolean before(PsiElement e, @Nullable PsiElement callSite) {
        return callSite == null || e.getTextRange().getStartOffset() < callSite.getTextRange().getStartOffset();
    }

    // ---------------------------------------------------------------- Map 操作

    /**
     * 处理 var.put(...) 等。返回非 null 表示遇到无法证明的修改，应整体 UNRESOLVED。
     */
    private @Nullable TraceResult collectMapMutation(PsiMethodCallExpression call, List<ParameterReference> params, Context ctx) {
        String name = call.getMethodExpression().getReferenceName();
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (name == null) {
            return null;
        }
        if (PUT_LIKE.contains(name) && args.length >= 1) {
            String key = constantString(args[0]);
            if (key == null) {
                return TraceResult.unresolved(UnresolvedReason.MAP_KEY_DYNAMIC, args[0].getText());
            }
            params.add(ParameterReference.of(key, ParameterSourceType.MAP_PUT, Locations.of(args[0])));
            return null;
        }
        if ("putAll".equals(name) && args.length == 1) {
            TraceResult inner = traceExpression(args[0], ctx);
            if (!inner.isResolved()) {
                return TraceResult.unresolved(UnresolvedReason.MAP_MUTATED, "putAll(" + args[0].getText() + ")");
            }
            params.addAll(inner.parameters());
            return null;
        }
        if ("remove".equals(name) || "clear".equals(name) || "replaceAll".equals(name) || "compute".equals(name)
                || "keySet".equals(name) || "values".equals(name) || "entrySet".equals(name)) {
            // remove / clear 改变内容；keySet/values/entrySet 视图可被修改
            return TraceResult.unresolved(UnresolvedReason.MAP_MUTATED, name + "()");
        }
        // get / containsKey / size / isEmpty 等只读操作
        return null;
    }

    // ---------------------------------------------------------------- Bean setter

    /** 处理 var.setX(v) 与链式 var.setA(a).setB(b)。返回是否至少收集到一个 setter。 */
    private static boolean collectSetterChain(PsiMethodCallExpression call, List<ParameterReference> params) {
        boolean any = false;
        PsiMethodCallExpression cur = call;
        while (cur != null) {
            String name = cur.getMethodExpression().getReferenceName();
            String prop = ParameterNameNormalizer.setterToProperty(name);
            if (prop != null && cur.getArgumentList().getExpressionCount() == 1) {
                PsiElement anchor = cur.getMethodExpression().getReferenceNameElement();
                params.add(ParameterReference.of(prop, ParameterSourceType.BEAN_SETTER,
                        Locations.of(anchor == null ? cur : anchor)));
                any = true;
            }
            // 链式：外层 (cur).setB(...)
            if (cur.getParent() instanceof PsiReferenceExpression outerRef
                    && outerRef.getParent() instanceof PsiMethodCallExpression outer
                    && outerRef.getQualifierExpression() == cur) {
                cur = outer;
            } else {
                cur = null;
            }
        }
        return any;
    }

    // ---------------------------------------------------------------- 调用表达式

    private TraceResult traceCall(PsiMethodCallExpression call, Context ctx) {
        String name = call.getMethodExpression().getReferenceName();
        String qualifier = qualifierName(call);

        // Map.of / Map.ofEntries / ImmutableMap.of
        if (("of".equals(name) || "ofEntries".equals(name)) && qualifier != null && MAP_FACTORY_QUALIFIERS.contains(qualifier)) {
            return traceMapOf(call, ctx);
        }
        // ImmutableMap.builder().put(k, v).build()
        if ("build".equals(name) && qualifierIsImmutableMapBuilder(call)) {
            return traceImmutableBuilder(call, ctx);
        }
        // Maps.newHashMap() 直接作为实参：空 Map
        if (qualifier != null && MAP_FACTORY_QUALIFIERS.contains(qualifier) && name != null && name.startsWith("new")) {
            return TraceResult.resolved(List.of(), ctx.callPath());
        }
        // 其他方法调用：跨方法
        return traceBuilderMethod(call, ctx);
    }

    private TraceResult traceMapOf(PsiMethodCallExpression call, Context ctx) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        List<ParameterReference> params = new ArrayList<>();
        String name = call.getMethodExpression().getReferenceName();
        if ("ofEntries".equals(name)) {
            for (PsiExpression a : args) {
                if (unwrap(a) instanceof PsiMethodCallExpression entry
                        && "entry".equals(entry.getMethodExpression().getReferenceName())
                        && entry.getArgumentList().getExpressionCount() == 2) {
                    PsiExpression k = entry.getArgumentList().getExpressions()[0];
                    String key = constantString(k);
                    if (key == null) {
                        return TraceResult.unresolved(UnresolvedReason.MAP_KEY_DYNAMIC, k.getText());
                    }
                    params.add(ParameterReference.of(key, ParameterSourceType.MAP_OF, Locations.of(k)));
                } else {
                    return TraceResult.unresolved(UnresolvedReason.MAP_KEY_DYNAMIC, a.getText());
                }
            }
            return TraceResult.resolved(params, ctx.callPath());
        }
        for (int i = 0; i + 1 < args.length; i += 2) {
            String key = constantString(args[i]);
            if (key == null) {
                return TraceResult.unresolved(UnresolvedReason.MAP_KEY_DYNAMIC, args[i].getText());
            }
            params.add(ParameterReference.of(key, ParameterSourceType.MAP_OF, Locations.of(args[i])));
        }
        return TraceResult.resolved(params, ctx.callPath());
    }

    private TraceResult traceImmutableBuilder(PsiMethodCallExpression buildCall, Context ctx) {
        List<ParameterReference> params = new ArrayList<>();
        PsiExpression q = buildCall.getMethodExpression().getQualifierExpression();
        while (q instanceof PsiMethodCallExpression qc) {
            String n = qc.getMethodExpression().getReferenceName();
            PsiExpression[] args = qc.getArgumentList().getExpressions();
            if ("put".equals(n) && args.length == 2) {
                String key = constantString(args[0]);
                if (key == null) {
                    return TraceResult.unresolved(UnresolvedReason.MAP_KEY_DYNAMIC, args[0].getText());
                }
                params.add(ParameterReference.of(key, ParameterSourceType.MAP_OF, Locations.of(args[0])));
            } else if ("putAll".equals(n)) {
                TraceResult inner = traceExpression(args.length == 1 ? args[0] : null, ctx);
                if (!inner.isResolved()) {
                    return TraceResult.unresolved(UnresolvedReason.MAP_MUTATED, "putAll");
                }
                params.addAll(inner.parameters());
            }
            q = qc.getMethodExpression().getQualifierExpression();
        }
        return TraceResult.resolved(params, ctx.callPath());
    }

    // ---------------------------------------------------------------- new 表达式

    private TraceResult traceNewExpression(PsiNewExpression newExpr, Context ctx) {
        PsiAnonymousClass anon = newExpr.getAnonymousClass();
        PsiType type = newExpr.getType();
        boolean isMap = MethodSignatureParameterResolver.isMap(type);
        if (anon == null) {
            // new HashMap<>() / new Bean() 直接作为实参：空容器
            if (isMap) {
                return TraceResult.resolved(List.of(), ctx.callPath());
            }
            return MethodSignatureParameterResolver.isScalar(type)
                    ? TraceResult.notComparable()
                    : TraceResult.unresolved(UnresolvedReason.BEAN_NO_SETTER, newExpr.getText());
        }
        // 双花括号初始化：{{ put("a", 1); }}
        if (!isMap) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, newExpr.getText());
        }
        List<ParameterReference> params = new ArrayList<>();
        for (PsiClassInitializer init : anon.getInitializers()) {
            for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(init.getBody(), PsiMethodCallExpression.class)) {
                if (call.getMethodExpression().getQualifierExpression() != null) {
                    continue; // 只看无限定的 put(...)
                }
                TraceResult r = collectMapMutation(call, params, ctx);
                if (r != null) {
                    return r;
                }
            }
        }
        return TraceResult.resolved(params, ctx.callPath());
    }

    // ---------------------------------------------------------------- 跨方法

    private TraceResult traceBuilderMethod(PsiMethodCallExpression call, Context ctx) {
        PsiMethod target = call.resolveMethod();
        if (target == null) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, call.getText());
        }
        PsiType returnType = target.getReturnType();
        if (MethodSignatureParameterResolver.isScalar(returnType)) {
            return TraceResult.notComparable();
        }
        if (ctx.depth() >= maxDepth) {
            return TraceResult.unresolved(UnresolvedReason.DEPTH_EXCEEDED, Context.describe(target));
        }
        if (ctx.visiting().contains(target) || target == ctx.method()) {
            return TraceResult.unresolved(UnresolvedReason.CYCLE, Context.describe(target));
        }
        if (!isProjectSource(target)) {
            return TraceResult.unresolved(UnresolvedReason.LIBRARY_CODE, Context.describe(target));
        }
        PsiClass owner = target.getContainingClass();
        boolean abstractLike = target.hasModifierProperty(PsiModifier.ABSTRACT)
                || (owner != null && owner.isInterface() && target.getBody() == null);
        if (abstractLike) {
            return TraceResult.unresolved(UnresolvedReason.MULTI_IMPL, Context.describe(target));
        }
        PsiCodeBlock body = target.getBody();
        if (body == null) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, Context.describe(target));
        }

        TraceResult cached = builderCache.get(target);
        if (cached == null) {
            cached = traceReturns(target, body, ctx.descend(target));
            builderCache.put(target, cached);
        }
        if (!cached.isResolved()) {
            return cached;
        }
        List<String> path = new ArrayList<>(ctx.callPath());
        if (path.isEmpty() && ctx.method() != null) {
            path.add(Context.describe(ctx.method()));
        }
        path.add(Context.describe(target));
        return TraceResult.resolved(cached.parameters(), path);
    }

    /** 所有 return 分支取并集。 */
    private TraceResult traceReturns(PsiMethod method, PsiCodeBlock body, Context calleeCtx) {
        List<ParameterReference> params = new ArrayList<>();
        boolean any = false;
        for (PsiReturnStatement ret : PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement.class)) {
            // 排除 lambda / 匿名类 / 内部类里的 return
            PsiElement owner = PsiTreeUtil.getParentOfType(ret, PsiMethod.class, PsiLambdaExpression.class, PsiClass.class);
            if (owner != method) {
                continue;
            }
            PsiExpression value = ret.getReturnValue();
            if (value == null) {
                continue;
            }
            any = true;
            TraceResult r = traceExpression(value, calleeCtx);
            if (!r.isResolved()) {
                return r.status() == TraceResult.Status.NOT_COMPARABLE
                        ? TraceResult.unresolved(UnresolvedReason.OTHER, Context.describe(method)) : r;
            }
            params.addAll(r.parameters());
        }
        if (!any) {
            return TraceResult.unresolved(UnresolvedReason.OTHER, Context.describe(method));
        }
        return TraceResult.resolved(params, calleeCtx.callPath());
    }

    private boolean isProjectSource(PsiMethod method) {
        PsiFile file = method.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null) {
            return false;
        }
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        return index.isInSourceContent(vf) && !index.isInLibrary(vf);
    }

    // ---------------------------------------------------------------- 工具

    private @Nullable String constantString(PsiExpression expr) {
        Object v = JavaPsiFacade.getInstance(project).getConstantEvaluationHelper().computeConstantExpression(expr, false);
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
