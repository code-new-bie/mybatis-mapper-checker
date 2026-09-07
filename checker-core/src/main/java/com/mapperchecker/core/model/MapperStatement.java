package com.mapperchecker.core.model;

import java.util.List;
import java.util.Objects;

/**
 * 一条 Mapper statement 或 SQL 片段。Index 阶段产出的是"单文件事实"：
 * directParameters 只含本标签内直接引用，includeRefs 尚未展开。
 *
 * @param source           定义来源
 * @param namespace        namespace
 * @param id               id
 * @param type             标签类型
 * @param databaseId       databaseId 属性，无则为空串
 * @param directParameters 本标签内直接引用的参数
 * @param includeRefs      引用的 include refid（可能是短 id，需在 Resolver 中补全 namespace）
 * @param parameterMapRef  iBatis 2 parameterMap 引用，无则为空串
 * @param partiallyParsed  是否只解析了一部分（注解 SQL 含不可求值片段等）
 * @param location         位置
 * @param moduleName       模块名
 * @param templates        参数名里嵌了 ${} 占位符的原文，需 include 的 property 替换后才能提取，
 *                         见 {@link com.mapperchecker.core.contract.ParameterTemplate}
 */
public record MapperStatement(
        StatementSource source,
        String namespace,
        String id,
        StatementType type,
        String databaseId,
        List<ParameterReference> directParameters,
        List<String> includeRefs,
        String parameterMapRef,
        boolean partiallyParsed,
        SourceLocation location,
        String moduleName,
        List<String> templates) {

    /** 无模板的旧签名。 */
    public MapperStatement(StatementSource source, String namespace, String id, StatementType type, String databaseId,
                           List<ParameterReference> directParameters, List<String> includeRefs, String parameterMapRef,
                           boolean partiallyParsed, SourceLocation location, String moduleName) {
        this(source, namespace, id, type, databaseId, directParameters, includeRefs, parameterMapRef,
                partiallyParsed, location, moduleName, List.of());
    }

    public MapperStatement {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(location, "location");
        namespace = namespace == null ? "" : namespace;
        databaseId = databaseId == null ? "" : databaseId;
        directParameters = directParameters == null ? List.of() : List.copyOf(directParameters);
        includeRefs = includeRefs == null ? List.of() : List.copyOf(includeRefs);
        parameterMapRef = parameterMapRef == null ? "" : parameterMapRef;
        moduleName = moduleName == null ? "" : moduleName;
        templates = templates == null ? List.of() : List.copyOf(templates);
    }

    /** namespace.id；无 namespace 时就是 id。 */
    public String fullId() {
        return fullId(namespace, id);
    }

    public static String fullId(String namespace, String id) {
        return namespace == null || namespace.isEmpty() ? id : namespace + "." + id;
    }

    public boolean isFragment() {
        return type == StatementType.SQL_FRAGMENT;
    }
}
