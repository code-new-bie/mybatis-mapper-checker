package com.mapperchecker.idea.navigation;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.java.StatementIdResolver;
import com.mapperchecker.idea.java.StringCallInvocationExtractor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Ctrl / Cmd + Click：Mapper 接口方法名 → XML statement；字符串调用的 statementId → XML statement。方案第 16 节。
 */
public final class MapperGotoDeclarationHandler implements GotoDeclarationHandler {

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(@Nullable PsiElement sourceElement, int offset, Editor editor) {
        if (sourceElement == null) {
            return null;
        }
        String fullId = null;
        // 1. 接口方法名
        if (sourceElement instanceof PsiIdentifier && sourceElement.getParent() instanceof PsiMethod method
                && method.getNameIdentifier() == sourceElement) {
            if (MapperNavigator.mainstreamMyBatisPluginInstalled()) {
                return null; // 让位给 MyBatisX 等
            }
            PsiClass owner = method.getContainingClass();
            if (owner != null && MapperInterfaceDetector.isMapperInterface(owner) && owner.getQualifiedName() != null) {
                fullId = owner.getQualifiedName() + "." + method.getName();
            }
        }
        // 2. 字符串调用的第一个参数
        if (fullId == null) {
            PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(sourceElement, PsiLiteralExpression.class, false);
            PsiMethodCallExpression call = literal == null ? null : PsiTreeUtil.getParentOfType(literal, PsiMethodCallExpression.class);
            if (call != null) {
                StringCallInvocationExtractor.Candidate cand = StringCallInvocationExtractor.extract(call);
                if (cand != null && isWithin(cand.statementIdExpr(), literal)) {
                    fullId = StatementIdResolver.resolve(cand.statementIdExpr());
                }
            }
        }
        if (fullId == null) {
            return null;
        }
        VirtualFile context = sourceElement.getContainingFile() == null ? null : sourceElement.getContainingFile().getVirtualFile();
        List<PsiElement> targets = MapperNavigator.findStatementTags(sourceElement.getProject(), fullId, context);
        return targets.isEmpty() ? null : targets.toArray(PsiElement[]::new);
    }

    private static boolean isWithin(PsiExpression expr, PsiElement e) {
        return PsiTreeUtil.isAncestor(expr, e, false);
    }

    @Override
    public @Nullable String getActionText(@NotNull com.intellij.openapi.actionSystem.DataContext context) {
        return null;
    }
}
