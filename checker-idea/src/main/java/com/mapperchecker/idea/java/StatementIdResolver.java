package com.mapperchecker.idea.java;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiExpression;
import org.jetbrains.annotations.Nullable;

/**
 * 字符串 statementId 解析：字面量、静态常量、常量拼接。方案 9.6。
 * 求不出返回 null，由调用方标 UNRESOLVED。
 */
public final class StatementIdResolver {

    private StatementIdResolver() {
    }

    public static @Nullable String resolve(@Nullable PsiExpression expression) {
        if (expression == null) {
            return null;
        }
        Object value = JavaPsiFacade.getInstance(expression.getProject())
                .getConstantEvaluationHelper()
                .computeConstantExpression(expression, false);
        if (value instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }
}
