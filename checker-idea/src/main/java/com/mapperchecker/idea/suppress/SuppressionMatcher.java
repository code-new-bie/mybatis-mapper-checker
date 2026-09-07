package com.mapperchecker.idea.suppress;

import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 三种抑制。方案 15.1。
 * <ol>
 *   <li>组合抑制：设置中的 statementId#parameterName</li>
 *   <li>@SuppressWarnings("DAL-001")：参数、方法、类上都可</li>
 *   <li>行注释 {@code // mapper-checker: ignore}</li>
 * </ol>
 */
public final class SuppressionMatcher {

    public static final String LINE_MARKER = "mapper-checker:";
    private static final String SUPPRESS_WARNINGS = "java.lang.SuppressWarnings";

    private final CheckSettings settings;

    public SuppressionMatcher(@NotNull CheckSettings settings) {
        this.settings = settings;
    }

    /**
     * @param issue  问题
     * @param anchor 问题在 Java 侧的 PSI 位置，可为 null
     */
    public boolean isSuppressed(@NotNull ContractIssue issue, @Nullable PsiElement anchor) {
        if (settings.suppressedPairs().contains(issue.suppressionKey())) {
            return true;
        }
        if (anchor == null || !anchor.isValid()) {
            return false;
        }
        return suppressedByAnnotation(issue, anchor) || suppressedByLineComment(anchor);
    }

    private static boolean suppressedByAnnotation(ContractIssue issue, PsiElement anchor) {
        String ruleId = issue.ruleId().code();
        PsiModifierListOwner owner = anchor instanceof PsiModifierListOwner o ? o
                : PsiTreeUtil.getParentOfType(anchor, PsiModifierListOwner.class, false);
        while (owner != null) {
            PsiAnnotation a = owner.getAnnotation(SUPPRESS_WARNINGS);
            if (a != null && annotationMentions(a, ruleId)) {
                return true;
            }
            owner = PsiTreeUtil.getParentOfType(owner, PsiModifierListOwner.class, true);
        }
        return false;
    }

    private static boolean annotationMentions(PsiAnnotation a, String ruleId) {
        PsiAnnotationMemberValue v = a.findAttributeValue("value");
        if (v instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue item : arr.getInitializers()) {
                if (literalMatches(item, ruleId)) {
                    return true;
                }
            }
            return false;
        }
        return literalMatches(v, ruleId);
    }

    private static boolean literalMatches(@Nullable PsiAnnotationMemberValue v, String ruleId) {
        if (v instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
            String t = s.trim();
            return t.equalsIgnoreCase(ruleId) || t.replace('_', '-').equalsIgnoreCase(ruleId)
                    || t.equalsIgnoreCase("DAL") || t.equalsIgnoreCase("mapper-checker")
                    || t.equalsIgnoreCase("all");
        }
        return false;
    }

    /** 同一行末尾有 {@code // mapper-checker: ignore}（或 ignore DAL-001）。 */
    private static boolean suppressedByLineComment(PsiElement anchor) {
        PsiFile file = anchor.getContainingFile();
        if (file == null) {
            return false;
        }
        Document doc = PsiDocumentManager.getInstance(anchor.getProject()).getDocument(file);
        if (doc == null) {
            return false;
        }
        int offset = anchor.getTextRange().getStartOffset();
        if (offset < 0 || offset > doc.getTextLength()) {
            return false;
        }
        int line = doc.getLineNumber(offset);
        String text = doc.getText(new com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)))
                .toLowerCase(Locale.ROOT);
        int idx = text.indexOf(LINE_MARKER);
        return idx >= 0 && text.substring(idx).contains("ignore");
    }
}
