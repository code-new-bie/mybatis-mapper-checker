package com.mapperchecker.idea.mapper;

import com.mapperchecker.idea.index.IndexedElement;

import java.util.List;

/**
 * 一个 Mapper XML 文件的解析结果。
 *
 * @param dialect   方言
 * @param namespace namespace
 * @param elements  statement、SQL 片段、parameterMap
 */
public record ParsedMapperFile(MapperDialect dialect, String namespace, List<IndexedElement> elements) {

    public ParsedMapperFile {
        namespace = namespace == null ? "" : namespace;
        elements = elements == null ? List.of() : List.copyOf(elements);
    }
}
