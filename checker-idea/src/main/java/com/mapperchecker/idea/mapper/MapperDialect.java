package com.mapperchecker.idea.mapper;

import com.mapperchecker.core.model.StatementSource;

/** Mapper XML 方言。 */
public enum MapperDialect {
    /** {@code <mapper namespace="...">}，#{} / ${}。 */
    MYBATIS("mapper", StatementSource.MYBATIS_XML),
    /** {@code <sqlMap namespace="...">}，#x# / $x$。 */
    IBATIS("sqlMap", StatementSource.IBATIS_XML);

    private final String rootTag;
    private final StatementSource source;

    MapperDialect(String rootTag, StatementSource source) {
        this.rootTag = rootTag;
        this.source = source;
    }

    public String rootTag() {
        return rootTag;
    }

    public StatementSource source() {
        return source;
    }

    /** 由根标签名判定；不是 Mapper 文件返回 null。 */
    public static MapperDialect fromRootTag(String tagName) {
        if (tagName == null) {
            return null;
        }
        for (MapperDialect d : values()) {
            if (d.rootTag.equals(tagName)) {
                return d;
            }
        }
        return null;
    }
}
