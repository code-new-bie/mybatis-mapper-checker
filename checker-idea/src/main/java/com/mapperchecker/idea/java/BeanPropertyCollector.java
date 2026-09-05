package com.mapperchecker.idea.java;

import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PropertyUtilBase;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 收集实体类的属性：字段 + getter / setter 推导的属性，含父类（不含 java.lang.Object），排除 static / transient。
 * Lombok 生成的访问器依赖 Lombok 插件补全 PSI；未安装时字段仍在，结果不受影响。
 */
public final class BeanPropertyCollector {

    private BeanPropertyCollector() {
    }

    /**
     * 类型是否值得展开为属性：可解析、位于项目源码、不是 Map / 集合 / 数组 / 标量 / 枚举 / 接口。
     */
    public static @Nullable PsiClass expandableBeanClass(@Nullable PsiType type) {
        if (type == null || !(type instanceof PsiClassType ct)) {
            return null;
        }
        if (MethodSignatureParameterResolver.isMap(type) || MethodSignatureParameterResolver.isCollection(type)
                || MethodSignatureParameterResolver.isScalar(type)) {
            return null;
        }
        PsiClass cls = ct.resolve();
        if (cls == null || cls.isEnum() || cls.isInterface() || cls.isAnnotationType()) {
            return null;
        }
        String fqn = cls.getQualifiedName();
        if (fqn == null || fqn.startsWith("java.") || fqn.startsWith("javax.") || fqn.startsWith("kotlin.")) {
            return null;
        }
        PsiFile file = cls.getContainingFile();
        VirtualFile vf = file == null ? null : file.getVirtualFile();
        if (vf == null || !ProjectFileIndex.getInstance(cls.getProject()).isInSourceContent(vf)) {
            return null; // 库里的类型（如 MyBatis-Plus Page）不展开
        }
        return cls;
    }

    /**
     * @return 属性名 → 锚点（优先字段，其次 setter，再 getter），保持声明顺序；父类属性在后
     */
    public static @NotNull Map<String, PsiElement> collect(@NotNull PsiClass beanClass) {
        Map<String, PsiElement> result = new LinkedHashMap<>();
        PsiClass cls = beanClass;
        int guard = 0;
        while (cls != null && !"java.lang.Object".equals(cls.getQualifiedName()) && guard++ < 16) {
            for (PsiField f : cls.getFields()) {
                if (f.hasModifierProperty(PsiModifier.STATIC) || f.hasModifierProperty(PsiModifier.TRANSIENT)
                        || "serialVersionUID".equals(f.getName())) {
                    continue;
                }
                result.putIfAbsent(f.getName(), f);
            }
            for (PsiMethod m : cls.getMethods()) {
                if (m.hasModifierProperty(PsiModifier.STATIC) || m.isConstructor()) {
                    continue;
                }
                String prop = null;
                if (PropertyUtilBase.isSimplePropertySetter(m)) {
                    prop = ParameterNameNormalizer.setterToProperty(m.getName());
                } else if (PropertyUtilBase.isSimplePropertyGetter(m)) {
                    prop = PropertyUtilBase.getPropertyNameByGetter(m);
                }
                if (prop != null && !prop.isEmpty() && !"class".equals(prop)) {
                    result.putIfAbsent(prop, m.getNameIdentifier() == null ? m : m.getNameIdentifier());
                }
            }
            cls = cls.getSuperClass();
        }
        return result;
    }
}
