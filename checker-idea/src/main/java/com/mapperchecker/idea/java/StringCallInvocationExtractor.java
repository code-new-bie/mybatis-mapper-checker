package com.mapperchecker.idea.java;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.InheritanceUtil;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.Operation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 识别 SqlSession / SqlSessionTemplate（MyBatis）与 SqlMapClient / SqlMapClientTemplate（iBatis 2）的字符串调用。
 * 方案 9.4 / 9.5：receiver 类型 + 方法名 + 第一参数为 String。
 */
public final class StringCallInvocationExtractor {

    /**
     * @param kind               调用形态
     * @param statementIdExpr    第一个参数表达式
     * @param parameterExpr      第二个参数表达式（参数对象），没有则 null
     * @param call               调用表达式
     */
    public record Candidate(InvocationKind kind, PsiExpression statementIdExpr, @Nullable PsiExpression parameterExpr,
                            PsiMethodCallExpression call) {

        public Operation operation() {
            String name = call.getMethodExpression().getReferenceName();
            if (name == null) {
                return Operation.UNKNOWN;
            }
            if (name.startsWith("select") || name.startsWith("query")) {
                return Operation.SELECT;
            }
            return switch (name) {
                case "insert" -> Operation.INSERT;
                case "update" -> Operation.UPDATE;
                case "delete" -> Operation.DELETE;
                default -> Operation.UNKNOWN;
            };
        }
    }

    private StringCallInvocationExtractor() {
    }

    /** 不是目标调用时返回 null。 */
    public static @Nullable Candidate extract(@NotNull PsiMethodCallExpression call) {
        String name = call.getMethodExpression().getReferenceName();
        if (name == null) {
            return null;
        }
        boolean sessionName = MyBatisNames.SQL_SESSION_METHODS.contains(name);
        boolean sqlMapName = MyBatisNames.SQLMAP_CLIENT_METHODS.contains(name);
        if (!sessionName && !sqlMapName) {
            return null;
        }
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) {
            return null;
        }
        PsiType firstType = args[0].getType();
        if (firstType == null || !firstType.equalsToText("java.lang.String")) {
            return null;
        }
        InvocationKind kind = receiverKind(call);
        if (kind == null) {
            return null;
        }
        if (kind == InvocationKind.SQL_SESSION_CALL && !sessionName) {
            return null;
        }
        if (kind == InvocationKind.SQLMAP_CLIENT_CALL && !sqlMapName) {
            return null;
        }
        PsiExpression paramExpr = args.length >= 2 ? args[1] : null;
        // selectList(id, param, RowBounds) 等：第二参数才是参数对象
        return new Candidate(kind, args[0], paramExpr, call);
    }

    private static @Nullable InvocationKind receiverKind(PsiMethodCallExpression call) {
        PsiMethod resolved = call.resolveMethod();
        PsiClass owner = resolved == null ? null : resolved.getContainingClass();
        if (owner != null) {
            InvocationKind k = kindOfClass(owner);
            if (k != null) {
                return k;
            }
        }
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (qualifier != null) {
            PsiType type = qualifier.getType();
            if (type instanceof PsiClassType ct) {
                PsiClass cls = ct.resolve();
                if (cls != null) {
                    return kindOfClass(cls);
                }
            }
        }
        return null;
    }

    private static @Nullable InvocationKind kindOfClass(PsiClass cls) {
        for (String fqn : MyBatisNames.SQL_SESSION_TYPES) {
            if (fqn.equals(cls.getQualifiedName()) || InheritanceUtil.isInheritor(cls, fqn)) {
                return InvocationKind.SQL_SESSION_CALL;
            }
        }
        for (String fqn : MyBatisNames.SQLMAP_CLIENT_TYPES) {
            if (fqn.equals(cls.getQualifiedName()) || InheritanceUtil.isInheritor(cls, fqn)) {
                return InvocationKind.SQLMAP_CLIENT_CALL;
            }
        }
        return null;
    }
}
