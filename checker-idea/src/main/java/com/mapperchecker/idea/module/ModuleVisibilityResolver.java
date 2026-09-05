package com.mapperchecker.idea.module;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapper 可见性：按"当前 Module → 直接依赖 → 传递依赖 + 库 → （兼容模式）整项目"分层给出搜索范围。方案第 12 节。
 * <p>
 * main 代码看不到 test 资源；test 代码可以看到两者。
 * 一个实例对应一次运行，内部缓存随实例销毁。
 */
public final class ModuleVisibilityResolver {

    /** 一层可见范围。 */
    public record Tier(String name, GlobalSearchScope scope) {
    }

    private final Project project;
    private final boolean strict;
    private final Map<String, List<Tier>> cache = new HashMap<>();

    public ModuleVisibilityResolver(@NotNull Project project, boolean strict) {
        this.project = project;
        this.strict = strict;
    }

    /** 由文件所在 Module 与是否测试源码得到分层范围。 */
    public @NotNull List<Tier> tiersFor(@Nullable VirtualFile contextFile) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Module module = contextFile == null ? null : index.getModuleForFile(contextFile, false);
        boolean inTest = contextFile != null && index.isInTestSourceContent(contextFile);
        return tiersFor(module, inTest);
    }

    public @NotNull List<Tier> tiersFor(@Nullable Module module, boolean includeTests) {
        String key = (module == null ? "<none>" : module.getName()) + "/" + includeTests;
        return cache.computeIfAbsent(key, k -> build(module, includeTests));
    }

    private List<Tier> build(@Nullable Module module, boolean includeTests) {
        List<Tier> tiers = new ArrayList<>();
        if (module != null) {
            tiers.add(new Tier("当前 Module", module.getModuleScope(includeTests)));

            Module[] direct = ModuleRootManager.getInstance(module).getDependencies(includeTests);
            if (direct.length > 0) {
                GlobalSearchScope union = null;
                for (Module d : direct) {
                    GlobalSearchScope s = d.getModuleScope(false);
                    union = union == null ? s : union.union(s);
                }
                tiers.add(new Tier("直接依赖 Module", union));
            }

            tiers.add(new Tier("传递依赖与库", GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module, includeTests)));
        } else {
            tiers.add(new Tier("项目", GlobalSearchScope.projectScope(project)));
        }
        if (!strict) {
            tiers.add(new Tier("整项目兼容", GlobalSearchScope.allScope(project)));
        }
        return tiers;
    }

    /** 所有层的并集（用于判断"namespace 是否在任何地方存在"）。 */
    public @NotNull GlobalSearchScope everything() {
        return GlobalSearchScope.allScope(project);
    }
}
