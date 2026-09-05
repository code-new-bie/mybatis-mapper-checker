package com.mapperchecker.idea.java;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.Operation;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Mapper 接口方法声明 → {@link DaoInvocation}。方案 9.1。
 */
public final class MapperMethodInvocationExtractor {

    /**
     * 声明级提取结果。
     *
     * @param invocation    DaoInvocation（parameters 可能为 null 表示不可比较）
     * @param method        接口方法
     * @param mapParameter  单 Map 参数无 @Param 时的那个参数，需转调用点追踪；否则 null
     * @param isProvider    带 Provider 注解，SQL 无法静态确定
     */
    public record Extracted(DaoInvocation invocation, PsiMethod method, @Nullable PsiParameter mapParameter,
                            boolean isProvider) {
    }

    private MapperMethodInvocationExtractor() {
    }

    /** 不是可检查的接口方法时返回 null。 */
    public static @Nullable Extracted extract(@NotNull PsiMethod method) {
        if (!MapperInterfaceDetector.isCheckableMethod(method)) {
            return null;
        }
        PsiClass owner = method.getContainingClass();
        String namespace = owner == null ? null : owner.getQualifiedName();
        if (namespace == null) {
            return null;
        }
        MethodSignatureParameterResolver.Result sig = MethodSignatureParameterResolver.resolve(method);
        PsiElement anchor = method.getNameIdentifier() == null ? method : method.getNameIdentifier();
        SourceLocation location = Locations.of(anchor);
        DaoInvocation inv = new DaoInvocation(
                InvocationKind.MAPPER_METHOD,
                Operation.UNKNOWN,
                namespace + "." + method.getName(),
                sig.mode() == MethodSignatureParameterResolver.Mode.COMPARABLE ? sig.parameters() : null,
                null,
                location,
                Locations.moduleNameOf(method));
        return new Extracted(inv, method, sig.mapParameter(), MapperInterfaceDetector.hasProviderAnnotation(method));
    }
}
