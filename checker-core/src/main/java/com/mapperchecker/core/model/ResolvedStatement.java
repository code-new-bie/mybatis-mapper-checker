package com.mapperchecker.core.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolver 阶段产物：已展开 include、合并 databaseId 变体、合并多文件后的 statement。
 *
 * @param fullId          完整 id
 * @param type            类型
 * @param parameterNames  Mapper 侧实际引用的全部根名
 * @param parameterPaths  Mapper 侧实际引用的全部完整路径（如 query.poiId），用于实体属性级比对
 * @param parameters      带位置的参数引用（展示用）
 * @param definitions     所有定义位置（databaseId 变体、多文件时不止一个）
 * @param partiallyParsed 任一定义为部分解析、或 include 循环 / 动态 refid 时为 true
 * @param fromLibrary     定义位于依赖 jar 中
 */
public record ResolvedStatement(
        String fullId,
        StatementType type,
        Set<String> parameterNames,
        Set<String> parameterPaths,
        List<ParameterReference> parameters,
        List<SourceLocation> definitions,
        boolean partiallyParsed,
        boolean fromLibrary) {

    public ResolvedStatement {
        Objects.requireNonNull(fullId, "fullId");
        Objects.requireNonNull(type, "type");
        parameterNames = parameterNames == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(parameterNames));
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        if (parameterPaths == null) {
            // 未提供路径时由 parameters 推导，退化为根名
            Set<String> paths = new LinkedHashSet<>();
            for (ParameterReference r : parameters) {
                paths.add(r.propertyPath());
            }
            paths.addAll(parameterNames);
            parameterPaths = Collections.unmodifiableSet(paths);
        } else {
            parameterPaths = Collections.unmodifiableSet(new LinkedHashSet<>(parameterPaths));
        }
    }

    /** 兼容旧构造：无路径集合。 */
    public ResolvedStatement(String fullId, StatementType type, Set<String> parameterNames,
                             List<ParameterReference> parameters, List<SourceLocation> definitions,
                             boolean partiallyParsed, boolean fromLibrary) {
        this(fullId, type, parameterNames, null, parameters, definitions, partiallyParsed, fromLibrary);
    }

    /** 属性路径 prefix 是否被使用：存在路径等于 prefix 或以 prefix. 开头。 */
    public boolean usesPath(String prefix) {
        for (String p : parameterPaths) {
            if (com.mapperchecker.core.naming.ParameterNameNormalizer.pathCovers(p, prefix)) {
                return true;
            }
        }
        return false;
    }

    public SourceLocation primaryLocation() {
        return definitions.isEmpty() ? SourceLocation.UNKNOWN : definitions.get(0);
    }
}
