package com.mapperchecker.idea.java;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.mapperchecker.idea.index.MapperNamespaceIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 判定一个接口是否为 MyBatis Mapper 接口。方案 9.1。满足任一：
 * <ul>
 *   <li>MapperNamespaceIndex 中存在 key == 接口全限定名</li>
 *   <li>接口带 @Mapper</li>
 *   <li>接口任一方法带 SQL 注解或 Provider 注解</li>
 * </ul>
 */
public final class MapperInterfaceDetector {

    private MapperInterfaceDetector() {
    }

    public static boolean isMapperInterface(@NotNull PsiClass psiClass) {
        if (!psiClass.isInterface() || psiClass.isAnnotationType()) {
            return false;
        }
        String fqn = psiClass.getQualifiedName();
        if (fqn == null) {
            return false;
        }
        if (psiClass.hasAnnotation(MyBatisNames.MAPPER)) {
            return true;
        }
        for (PsiMethod m : psiClass.getMethods()) {
            if (hasSqlOrProviderAnnotation(m)) {
                return true;
            }
        }
        Project project = psiClass.getProject();
        return MapperNamespaceIndex.hasNamespace(project, fqn, GlobalSearchScope.allScope(project));
    }

    public static boolean hasSqlAnnotation(@NotNull PsiMethod method) {
        for (String fqn : MyBatisNames.SQL_ANNOTATIONS) {
            if (method.hasAnnotation(fqn)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasProviderAnnotation(@NotNull PsiMethod method) {
        for (String fqn : MyBatisNames.PROVIDER_ANNOTATIONS) {
            if (method.hasAnnotation(fqn)) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasSqlOrProviderAnnotation(@NotNull PsiMethod method) {
        return hasSqlAnnotation(method) || hasProviderAnnotation(method);
    }

    /**
     * 该方法是否应作为 statement 检查：接口自身声明、非 default、非 static。
     * 从父接口（如 MyBatis-Plus BaseMapper）继承的方法不在 {@link PsiClass#getMethods()} 中，天然跳过。
     */
    public static boolean isCheckableMethod(@NotNull PsiMethod method) {
        if (method.isConstructor()) {
            return false;
        }
        if (method.hasModifierProperty(PsiModifier.STATIC) || method.hasModifierProperty(PsiModifier.DEFAULT)) {
            return false;
        }
        return method.getBody() == null;
    }

    /** 接口全限定名；取不到返回 null。 */
    public static @Nullable String namespaceOf(@NotNull PsiMethod method) {
        PsiClass owner = method.getContainingClass();
        return owner == null ? null : owner.getQualifiedName();
    }
}
