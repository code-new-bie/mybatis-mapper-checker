package com.mapperchecker.idea.java;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.mapperchecker.core.contract.RuleOptions;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行内共享的 Query 类清单：简单名 → 项目源码里同名的类。
 * <p>
 * 只保留源码里的类是性能关键：{@code getAllClassNames()} 会把依赖 jar 里的名字一起给出来，
 * Hibernate / JPA 的 CriteriaQuery、NativeQuery、AbstractQuery 都命中 Query 后缀，
 * 拿它们去做全项目词搜索既慢又没意义。DAL-021 与"纯 Java 规则候选文件"两处都用这份清单，只算一次。
 */
public final class QueryClassIndex {

    private final Project project;
    private final RuleOptions rules;
    private final Map<String, Map<String, List<PsiClass>>> cache = new LinkedHashMap<>();

    public QueryClassIndex(@NotNull Project project, @NotNull RuleOptions rules) {
        this.project = project;
        this.rules = rules;
    }

    /** 简单名 → 该名字在源码里的全部类（至少一个）。按 scope 缓存。 */
    public @NotNull Map<String, List<PsiClass>> sourceQueryClasses(@NotNull GlobalSearchScope scope) {
        Map<String, List<PsiClass>> cached = cache.get(scope.toString());
        if (cached != null) {
            return cached;
        }
        PsiShortNamesCache names = PsiShortNamesCache.getInstance(project);
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        GlobalSearchScope sourceScope = scope.intersectWith(GlobalSearchScope.projectScope(project));
        Map<String, List<PsiClass>> out = new LinkedHashMap<>();
        for (String name : names.getAllClassNames()) {
            if (!rules.isQueryClassName(name)) {
                continue;
            }
            ProgressManager.checkCanceled();
            List<PsiClass> inSource = new ArrayList<>(2);
            for (PsiClass cls : names.getClassesByName(name, sourceScope)) {
                PsiFile f = cls.getContainingFile();
                VirtualFile vf = f == null ? null : f.getVirtualFile();
                if (vf != null && fileIndex.isInSourceContent(vf) && cls.getQualifiedName() != null) {
                    inSource.add(cls);
                }
            }
            if (!inSource.isEmpty()) {
                out.put(name, inSource);
            }
        }
        cache.put(scope.toString(), out);
        return out;
    }
}
