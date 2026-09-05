package com.mapperchecker.idea.run;

import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPsiElementPointer;
import com.mapperchecker.core.model.ContractIssue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 报告中的一条问题：core 的 ContractIssue + 可导航的 PSI 指针。
 * 用 SmartPsiElementPointer 保存，文件修改后仍可跳转。
 *
 * @param issue        问题
 * @param javaAnchor   Java 侧位置（@Param 参数 / put 的 key / 方法名 / statementId 字面量）
 * @param mapperAnchor Mapper 侧位置（XML statement 标签 / 注解），可为 null
 */
public record ReportedIssue(@NotNull ContractIssue issue,
                            @Nullable SmartPsiElementPointer<PsiElement> javaAnchor,
                            @Nullable SmartPsiElementPointer<PsiElement> mapperAnchor) {

    public @Nullable PsiElement javaElement() {
        return javaAnchor == null ? null : javaAnchor.getElement();
    }

    public @Nullable PsiElement mapperElement() {
        return mapperAnchor == null ? null : mapperAnchor.getElement();
    }
}
