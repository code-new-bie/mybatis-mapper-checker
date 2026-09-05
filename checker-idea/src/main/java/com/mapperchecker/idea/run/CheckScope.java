package com.mapperchecker.idea.run;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.mapperchecker.idea.MapperCheckerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 检查范围：当前文件 / 所选文件集合 / Module / 整项目。
 */
public final class CheckScope {

    public enum Kind { FILE, FILES, MODULE, PROJECT }

    public final Kind kind;
    public final List<VirtualFile> files;
    public final @Nullable Module module;

    private CheckScope(Kind kind, List<VirtualFile> files, @Nullable Module module) {
        this.kind = kind;
        this.files = files == null ? List.of() : List.copyOf(files);
        this.module = module;
    }

    public static CheckScope file(@NotNull VirtualFile file) {
        return new CheckScope(Kind.FILE, List.of(file), null);
    }

    public static CheckScope files(@NotNull List<VirtualFile> files) {
        return new CheckScope(Kind.FILES, files, null);
    }

    public static CheckScope module(@NotNull Module module) {
        return new CheckScope(Kind.MODULE, List.of(), module);
    }

    public static CheckScope project() {
        return new CheckScope(Kind.PROJECT, List.of(), null);
    }

    /** 报告统计条上的范围名。 */
    public @NotNull String displayName() {
        return switch (kind) {
            case FILE -> files.isEmpty() ? MapperCheckerBundle.message("report.scope.file") : files.get(0).getName();
            case FILES -> MapperCheckerBundle.message("report.scope.selection");
            case MODULE -> MapperCheckerBundle.message("report.scope.module", module == null ? "" : module.getName());
            case PROJECT -> MapperCheckerBundle.message("report.scope.project");
        };
    }

    /** 调用点搜索范围（单 Map 参数需到调用点追踪）。 */
    public @NotNull GlobalSearchScope callSiteScope(@NotNull Project project) {
        return switch (kind) {
            case MODULE -> module == null ? GlobalSearchScope.projectScope(project)
                    : GlobalSearchScope.moduleWithDependentsScope(module);
            default -> GlobalSearchScope.projectScope(project);
        };
    }

    /** Module / 项目级的发现范围；文件级返回 null。 */
    public @Nullable GlobalSearchScope discoveryScope(@NotNull Project project) {
        return switch (kind) {
            case MODULE -> module == null ? null : module.getModuleScope(true);
            case PROJECT -> GlobalSearchScope.projectScope(project);
            default -> null;
        };
    }
}
