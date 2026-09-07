package com.mapperchecker.idea.java;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.naming.AliasGroupBuilder;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 由 Mapper 接口方法签名得到 Java 侧参数集合。方案 9.2 的规则表。
 */
public final class MethodSignatureParameterResolver {

    /** 签名解析结果。 */
    public enum Mode {
        /** 可比较：parameters 有效。 */
        COMPARABLE,
        /** 单个 Map 参数无 @Param：声明级不检查，转调用点追踪。 */
        SINGLE_MAP,
        /** 不可比较：单 Bean 无 @Param、单标量、无参数等。 */
        NOT_COMPARABLE
    }

    public record Result(Mode mode, List<ParameterReference> parameters, @Nullable PsiParameter mapParameter) {
        public static Result notComparable() {
            return new Result(Mode.NOT_COMPARABLE, null, null);
        }
    }

    private static final Set<String> SCALAR_LIKE = Set.of(
            "java.lang.String", "java.lang.Number", "java.util.Date", "java.time.temporal.Temporal",
            "java.math.BigDecimal", "java.math.BigInteger", "java.lang.CharSequence", "java.lang.Enum",
            "java.lang.Boolean", "java.lang.Character");

    private MethodSignatureParameterResolver() {
    }

    public static @NotNull Result resolve(@NotNull PsiMethod method) {
        List<PsiParameter> effective = new ArrayList<>();
        for (PsiParameter p : method.getParameterList().getParameters()) {
            if (!isSpecialParameter(p.getType())) {
                effective.add(p);
            }
        }
        if (effective.isEmpty()) {
            return Result.notComparable();
        }

        boolean anyParamAnnotation = effective.stream().anyMatch(p -> paramAnnotationValue(p) != null);

        // 单参数且无 @Param：按类型分流
        if (effective.size() == 1 && !anyParamAnnotation) {
            PsiParameter only = effective.get(0);
            PsiType type = only.getType();
            if (isMap(type)) {
                return new Result(Mode.SINGLE_MAP, null, only);
            }
            if (type instanceof PsiArrayType) {
                return new Result(Mode.COMPARABLE, List.of(ParameterReference.withAliases(
                        only.getName(), AliasGroupBuilder.forSingleArray(only.getName()),
                        ParameterSourceType.METHOD_PARAM, Locations.of(only))), null);
            }
            if (isCollection(type)) {
                boolean isList = InheritanceUtil.isInheritor(type, "java.util.List");
                return new Result(Mode.COMPARABLE, List.of(ParameterReference.withAliases(
                        only.getName(), AliasGroupBuilder.forSingleCollection(only.getName(), isList),
                        ParameterSourceType.METHOD_PARAM, Locations.of(only))), null);
            }
            // 单标量：任意名字合法，不检查
            if (isScalar(type)) {
                return Result.notComparable();
            }
            // 单 Bean 无 @Param：Mapper 直接用 #{prop} 访问属性，展开实体属性逐个比对（低置信度）
            PsiClass bean = BeanPropertyCollector.expandableBeanClass(type);
            if (bean == null) {
                return Result.notComparable();
            }
            List<ParameterReference> props = new ArrayList<>();
            String owner = bean.getName() == null ? "" : bean.getName();
            for (var entry : BeanPropertyCollector.collect(bean).entrySet()) {
                props.add(ParameterReference.beanProperty(entry.getKey(), entry.getKey(), owner, Locations.of(entry.getValue())));
            }
            return props.isEmpty() ? Result.notComparable() : new Result(Mode.COMPARABLE, props, null);
        }

        List<ParameterReference> refs = new ArrayList<>(effective.size());
        for (int i = 0; i < effective.size(); i++) {
            PsiParameter p = effective.get(i);
            String named = paramAnnotationValue(p);
            if (named != null) {
                refs.add(ParameterReference.of(named, ParameterSourceType.PARAM_ANNOTATION, Locations.of(p)));
                // @Param("q") Bean：再按 q.prop 展开属性
                PsiClass bean = BeanPropertyCollector.expandableBeanClass(p.getType());
                if (bean != null) {
                    String owner = bean.getName() == null ? "" : bean.getName();
                    for (var entry : BeanPropertyCollector.collect(bean).entrySet()) {
                        refs.add(ParameterReference.beanProperty(named, named + "." + entry.getKey(), owner,
                                Locations.of(entry.getValue())));
                    }
                }
            } else {
                refs.add(ParameterReference.withAliases(p.getName(), AliasGroupBuilder.forPositional(p.getName(), i),
                        ParameterSourceType.METHOD_PARAM, Locations.of(p)));
            }
        }
        return new Result(Mode.COMPARABLE, refs, null);
    }

    /** @Param 的 value；无注解或值不是字面量时返回 null。 */
    public static @Nullable String paramAnnotationValue(@NotNull PsiParameter p) {
        PsiAnnotation a = p.getAnnotation(MyBatisNames.PARAM);
        if (a == null) {
            return null;
        }
        PsiAnnotationMemberValue v = a.findAttributeValue("value");
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    static boolean isSpecialParameter(@Nullable PsiType type) {
        return type != null && (InheritanceUtil.isInheritor(type, MyBatisNames.ROW_BOUNDS)
                || InheritanceUtil.isInheritor(type, MyBatisNames.RESULT_HANDLER));
    }

    private static final Set<String> MAP_CLASS_NAMES = Set.of(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "Hashtable", "Properties",
            "SortedMap", "NavigableMap", "ConcurrentMap", "ImmutableMap", "MapUtil", "CaseInsensitiveMap");

    private static final Set<String> COLLECTION_CLASS_NAMES = Set.of(
            "Collection", "List", "ArrayList", "LinkedList", "Set", "HashSet", "LinkedHashSet", "TreeSet",
            "SortedSet", "NavigableSet", "Queue", "Deque", "ArrayDeque", "ImmutableList", "ImmutableSet");

    public static boolean isMap(@Nullable PsiType type) {
        if (type == null) {
            return false;
        }
        if (InheritanceUtil.isInheritor(type, "java.util.Map")) {
            return true;
        }
        // JDK 未配置、类型解析不到时按类名兜底，避免把 Map 当成 Bean
        return unresolvedClassNameIn(type, MAP_CLASS_NAMES);
    }

    public static boolean isCollection(@Nullable PsiType type) {
        if (type == null) {
            return false;
        }
        if (InheritanceUtil.isInheritor(type, "java.util.Collection")) {
            return true;
        }
        return unresolvedClassNameIn(type, COLLECTION_CLASS_NAMES);
    }

    private static boolean unresolvedClassNameIn(PsiType type, Set<String> names) {
        if (type instanceof PsiClassType ct && ct.resolve() == null) {
            String name = ct.getClassName();
            return name != null && names.contains(name);
        }
        return false;
    }

    /** 标量：基本类型、包装类型、String、Number、Date、枚举等。 */
    public static boolean isScalar(@Nullable PsiType type) {
        if (type == null) {
            return false;
        }
        if (type instanceof PsiPrimitiveType) {
            return true;
        }
        if (TypeConversionUtil.isPrimitiveWrapper(type)) {
            return true;
        }
        for (String fqn : SCALAR_LIKE) {
            if (InheritanceUtil.isInheritor(type, fqn)) {
                return true;
            }
        }
        if (type instanceof PsiClassType ct) {
            PsiClass cls = ct.resolve();
            return cls != null && cls.isEnum();
        }
        return false;
    }
}
