package com.mapperchecker.idea.mapper;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import com.mapperchecker.core.contract.StatementAssembler;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.naming.GlobMatcher;
import com.mapperchecker.idea.index.IndexedElement;
import com.mapperchecker.idea.index.MapperStatementIndex;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Index 的 Mapper 查询。实现 {@link StatementAssembler.Repository}，把跨文件查找交给 Index。
 * <p>
 * 一个实例对应一次检查运行，内部缓存随实例销毁。
 */
public final class MapperRepository implements StatementAssembler.Repository {

    private final Project project;
    private final List<String> ignoredPathPatterns;
    private final StatementAssembler assembler = new StatementAssembler(this);

    /** fullId + scope 标识 → 组装结果。 */
    private final Map<String, List<ResolvedStatement>> resolvedCache = new HashMap<>();
    /** 全部 key 的快照，避免反复 getAllKeys。 */
    private Collection<String> allKeysSnapshot;

    public MapperRepository(@NotNull Project project, @NotNull List<String> ignoredPathPatterns) {
        this.project = project;
        this.ignoredPathPatterns = ignoredPathPatterns;
    }

    public Project project() {
        return project;
    }

    /**
     * 在范围内查找 fullId 对应的 statement，按文件分组后各自组装（同文件内 databaseId 变体合并为一个）。
     * 多个文件命中即为多候选。
     */
    public List<ResolvedStatement> findStatements(@NotNull String fullId, @NotNull GlobalSearchScope scope) {
        String cacheKey = fullId + "\u0000" + scope.hashCode();
        List<ResolvedStatement> cached = resolvedCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Map<VirtualFile, List<IndexedElement>> byFile = collect(fullId, scope, IndexedElement.Kind.STATEMENT);
        List<ResolvedStatement> result = new ArrayList<>();
        for (Map.Entry<VirtualFile, List<IndexedElement>> e : byFile.entrySet()) {
            List<MapperStatement> defs = toStatements(e.getKey(), e.getValue());
            if (!defs.isEmpty()) {
                result.add(assembler.assemble(fullId, defs));
            }
        }
        resolvedCache.put(cacheKey, result);
        return result;
    }

    /**
     * MyBatis 短 id（不含 namespace）：在所有 key 中找 "*.id"，逐个组装。方案 9.6。
     */
    public List<ResolvedStatement> findStatementsByShortId(@NotNull String shortId, @NotNull GlobalSearchScope scope) {
        if (allKeysSnapshot == null) {
            allKeysSnapshot = FileBasedIndex.getInstance().getAllKeys(MapperStatementIndex.NAME, project);
        }
        String suffix = "." + shortId;
        java.util.Set<String> fullIds = new java.util.LinkedHashSet<>();
        for (String k : allKeysSnapshot) {
            String base = k.indexOf('#') >= 0 ? k.substring(0, k.indexOf('#')) : k;
            if (base.endsWith(suffix) || base.equals(shortId)) {
                fullIds.add(base);
            }
        }
        List<ResolvedStatement> result = new ArrayList<>();
        for (String fullId : fullIds) {
            result.addAll(findStatements(fullId, scope));
        }
        return result;
    }

    /** 直接给出定义列表时的组装入口（注解 SQL 用）。 */
    public ResolvedStatement assemble(@NotNull String fullId, @NotNull List<MapperStatement> definitions) {
        return assembler.assemble(fullId, definitions);
    }

    @Override
    public List<MapperStatement> findFragments(String refid, String currentNamespace) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        List<MapperStatement> result = new ArrayList<>();
        // 先按当前 namespace 补全，再退回原样
        if (!refid.contains(".") && currentNamespace != null && !currentNamespace.isEmpty()) {
            result.addAll(fragments(MapperStatement.fullId(currentNamespace, refid), scope));
        }
        if (result.isEmpty()) {
            result.addAll(fragments(refid, scope));
        }
        return result;
    }

    @Override
    public List<String> findParameterMapProperties(String ref, String currentNamespace) {
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        List<String> candidates = new ArrayList<>();
        if (!ref.contains(".") && currentNamespace != null && !currentNamespace.isEmpty()) {
            candidates.add(MapperStatement.fullId(currentNamespace, ref));
        }
        candidates.add(ref);
        for (String fullId : candidates) {
            for (List<IndexedElement> list : collect(fullId, scope, IndexedElement.Kind.PARAMETER_MAP).values()) {
                for (IndexedElement e : list) {
                    if (!e.properties.isEmpty()) {
                        return e.properties;
                    }
                }
            }
        }
        return List.of();
    }

    @Override
    public boolean isLibraryLocation(SourceLocation location) {
        if (location == null || !location.isKnown()) {
            return false;
        }
        VirtualFile vf = VirtualFileManager.getInstance().findFileByUrl(toUrl(location.filePath()));
        return Locations.isInLibrary(project, vf);
    }

    private List<MapperStatement> fragments(String fullId, GlobalSearchScope scope) {
        List<MapperStatement> result = new ArrayList<>();
        for (Map.Entry<VirtualFile, List<IndexedElement>> e : collect(fullId, scope, IndexedElement.Kind.FRAGMENT).entrySet()) {
            result.addAll(toStatements(e.getKey(), e.getValue()));
        }
        return result;
    }

    private Map<VirtualFile, List<IndexedElement>> collect(String fullId, GlobalSearchScope scope, IndexedElement.Kind kind) {
        Map<VirtualFile, List<IndexedElement>> byFile = new LinkedHashMap<>();
        for (String key : keysFor(fullId)) {
            FileBasedIndex.getInstance().processValues(MapperStatementIndex.NAME, key, null, (file, value) -> {
                if (value.kind == kind && !isIgnoredPath(file)) {
                    byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(value);
                }
                return true;
            }, scope);
        }
        return byFile;
    }

    private List<String> keysFor(String fullId) {
        if (allKeysSnapshot == null) {
            allKeysSnapshot = FileBasedIndex.getInstance().getAllKeys(MapperStatementIndex.NAME, project);
        }
        List<String> keys = new ArrayList<>(2);
        if (allKeysSnapshot.contains(fullId)) {
            keys.add(fullId);
        }
        String prefix = fullId + "#";
        for (String k : allKeysSnapshot) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        return keys;
    }

    private boolean isIgnoredPath(VirtualFile file) {
        return !ignoredPathPatterns.isEmpty() && GlobMatcher.matchesAnyPath(ignoredPathPatterns, file.getPath());
    }

    private List<MapperStatement> toStatements(VirtualFile file, List<IndexedElement> elements) {
        String moduleName = Locations.moduleNameOf(project, file);
        List<MapperStatement> result = new ArrayList<>(elements.size());
        for (IndexedElement e : elements) {
            result.add(e.toMapperStatement(file.getPath(), moduleName));
        }
        return result;
    }

    private static String toUrl(String path) {
        // jar 内路径形如 C:/x/dep.jar!/mapper/A.xml
        if (path.contains("!/")) {
            return "jar://" + path;
        }
        return "file://" + path;
    }
}
