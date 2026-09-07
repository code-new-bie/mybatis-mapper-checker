package com.mapperchecker.core.naming;

import java.util.List;
import java.util.Set;

/**
 * 内置分页 / 排序参数名单。PageHelper、MyBatis-Plus、老式手写分页都会把这些参数放进参数对象，
 * 但 SQL 里不引用（由拦截器拼 LIMIT），逐个报 DAL-001 全是误报。默认开启忽略，设置里可关闭或补充。
 */
public final class PaginationDefaults {

    /** 精确名字（大小写敏感）。 */
    public static final Set<String> NAMES = Set.of(
            // 页码 / 页大小
            "page", "pageNo", "pageNum", "pageNumber", "pageIndex", "pageSize", "size", "current", "currentPage",
            "limit", "offset", "start", "startRow", "endRow", "startIndex", "endIndex", "rows", "pageStart",
            "firstResult", "maxResults", "skip", "take",
            // 总数
            "total", "totalCount", "totalPage", "totalPages", "pages", "count", "needCount", "searchCount",
            // 排序
            "orderBy", "orderByClause", "orderByField", "orderField", "orderType", "order", "sort", "sortBy",
            "sortField", "sortName", "sortOrder", "sortType", "asc", "desc", "isAsc", "ascs", "descs", "orders",
            // PageHelper / MyBatis-Plus 特有
            "reasonable", "pageSizeZero", "countColumn", "optimizeCountSql", "isSearchCount", "hitCount",
            "records", "optimizeJoinOfCountSql", "maxLimit", "countId");

    /** 通配模式。 */
    public static final List<String> PATTERNS = List.of(
            "page*", "*PageNo", "*PageNum", "*PageSize", "*PageIndex", "*Offset", "*Limit", "*OrderBy", "*SortField");

    private PaginationDefaults() {
    }

    /**
     * 是否为分页参数。带路径时只看最后一段（q.pageSize → pageSize）。
     */
    public static boolean matches(String nameOrPath) {
        if (nameOrPath == null || nameOrPath.isEmpty()) {
            return false;
        }
        int dot = nameOrPath.lastIndexOf('.');
        String last = dot < 0 ? nameOrPath : nameOrPath.substring(dot + 1);
        return NAMES.contains(last) || GlobMatcher.matchesAny(PATTERNS, last);
    }
}
