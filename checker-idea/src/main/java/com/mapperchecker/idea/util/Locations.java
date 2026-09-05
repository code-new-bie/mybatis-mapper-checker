package com.mapperchecker.idea.util;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.mapperchecker.core.model.SourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PsiElement / VirtualFile 与 core 的 SourceLocation 互转。
 */
public final class Locations {

    private Locations() {
    }

    public static @NotNull SourceLocation of(@Nullable PsiElement element) {
        if (element == null) {
            return SourceLocation.UNKNOWN;
        }
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return SourceLocation.UNKNOWN;
        }
        VirtualFile vf = file.getVirtualFile();
        if (vf == null) {
            vf = file.getOriginalFile().getVirtualFile();
        }
        String path = vf == null ? file.getName() : vf.getPath();
        TextRange range = element.getTextRange();
        int line = lineOf(vf, range.getStartOffset());
        return SourceLocation.of(path, range.getStartOffset(), range.getEndOffset(), line);
    }

    /** 补上行号（Index 中只存偏移）。 */
    public static @NotNull SourceLocation withLine(@NotNull SourceLocation loc, @Nullable VirtualFile file) {
        if (loc.line() > 0 || file == null || loc.startOffset() < 0) {
            return loc;
        }
        int line = lineOf(file, loc.startOffset());
        return line <= 0 ? loc : SourceLocation.of(loc.filePath(), loc.startOffset(), loc.endOffset(), line);
    }

    /** 1 起始的行号；取不到返回 -1。 */
    public static int lineOf(@Nullable VirtualFile file, int offset) {
        if (file == null || offset < 0) {
            return -1;
        }
        Document doc = FileDocumentManager.getInstance().getDocument(file);
        if (doc == null || offset > doc.getTextLength()) {
            return -1;
        }
        return doc.getLineNumber(offset) + 1;
    }

    public static @NotNull String moduleNameOf(@NotNull Project project, @Nullable VirtualFile file) {
        if (file == null) {
            return "";
        }
        Module module = ProjectFileIndex.getInstance(project).getModuleForFile(file, false);
        return module == null ? "" : module.getName();
    }

    public static @NotNull String moduleNameOf(@Nullable PsiElement element) {
        if (element == null) {
            return "";
        }
        Module module = ModuleUtilCore.findModuleForPsiElement(element);
        return module == null ? "" : module.getName();
    }

    public static boolean isInLibrary(@NotNull Project project, @Nullable VirtualFile file) {
        return file != null && ProjectFileIndex.getInstance(project).isInLibrary(file);
    }
}
