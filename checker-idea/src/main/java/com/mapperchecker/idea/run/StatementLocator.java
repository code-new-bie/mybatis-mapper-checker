package com.mapperchecker.idea.run;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiMethod;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.rule.StatementLocationRules;
import com.mapperchecker.idea.index.MapperNamespaceIndex;
import com.mapperchecker.idea.java.AnnotationSqlParser;
import com.mapperchecker.idea.mapper.MapperRepository;
import com.mapperchecker.idea.module.ModuleVisibilityResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把 statementId 定位到 ResolvedStatement 列表：按可见性分层查找，第一层命中即停。
 * 接口方法另加注解 SQL 来源。
 */
public final class StatementLocator {

    private final MapperRepository repository;
    private final ModuleVisibilityResolver visibility;

    public StatementLocator(@NotNull MapperRepository repository, @NotNull ModuleVisibilityResolver visibility) {
        this.repository = repository;
        this.visibility = visibility;
    }

    /**
     * @param fullId      完整 id（或 MyBatis 短 id）
     * @param contextFile 调用方所在文件，决定可见性
     * @param method      Mapper 接口方法（可为 null），用于附加注解 SQL
     */
    public @NotNull StatementLocationRules.Lookup locate(@NotNull String fullId, @Nullable VirtualFile contextFile,
                                                          @Nullable PsiMethod method) {
        List<ResolvedStatement> found = List.of();
        for (ModuleVisibilityResolver.Tier tier : visibility.tiersFor(contextFile)) {
            found = fullId.indexOf('.') < 0
                    ? repository.findStatementsByShortId(fullId, tier.scope())
                    : repository.findStatements(fullId, tier.scope());
            if (!found.isEmpty()) {
                break;
            }
        }
        List<ResolvedStatement> candidates = new ArrayList<>(found);

        if (method != null) {
            MapperStatement annotated = AnnotationSqlParser.parse(method);
            if (annotated != null) {
                candidates.add(repository.assemble(fullId, List.of(annotated)));
            }
        }

        if (candidates.isEmpty()) {
            String namespace = namespaceOf(fullId);
            boolean namespaceKnown = namespace != null
                    && MapperNamespaceIndex.hasNamespace(repository.project(), namespace, visibility.everything());
            // namespace 在项目里根本没有任何 XML：可能定义在未索引的地方，降置信度
            return StatementLocationRules.Lookup.notFound(!namespaceKnown && method == null);
        }
        if (candidates.size() == 1) {
            return StatementLocationRules.Lookup.single(candidates.get(0));
        }
        return new StatementLocationRules.Lookup(candidates, false, looksLikeDbVariants(candidates));
    }

    private static @Nullable String namespaceOf(String fullId) {
        int dot = fullId.lastIndexOf('.');
        return dot <= 0 ? null : fullId.substring(0, dot);
    }

    /** 多个候选文件名相同、只差目录：疑似多数据库变体。 */
    private static boolean looksLikeDbVariants(List<ResolvedStatement> candidates) {
        Set<String> names = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (ResolvedStatement c : candidates) {
            for (SourceLocation loc : c.definitions()) {
                names.add(loc.fileName());
                paths.add(loc.filePath());
            }
        }
        return names.size() == 1 && paths.size() > 1;
    }
}
