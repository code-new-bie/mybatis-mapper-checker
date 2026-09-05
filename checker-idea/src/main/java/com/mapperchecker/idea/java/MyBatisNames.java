package com.mapperchecker.idea.java;

import java.util.Set;

/** MyBatis / iBatis 2 相关的类名、注解名常量。 */
public final class MyBatisNames {

    private MyBatisNames() {
    }

    public static final String PARAM = "org.apache.ibatis.annotations.Param";
    public static final String MAPPER = "org.apache.ibatis.annotations.Mapper";
    public static final String ROW_BOUNDS = "org.apache.ibatis.session.RowBounds";
    public static final String RESULT_HANDLER = "org.apache.ibatis.session.ResultHandler";

    public static final String SELECT = "org.apache.ibatis.annotations.Select";
    public static final String INSERT = "org.apache.ibatis.annotations.Insert";
    public static final String UPDATE = "org.apache.ibatis.annotations.Update";
    public static final String DELETE = "org.apache.ibatis.annotations.Delete";
    public static final Set<String> SQL_ANNOTATIONS = Set.of(SELECT, INSERT, UPDATE, DELETE);

    public static final Set<String> PROVIDER_ANNOTATIONS = Set.of(
            "org.apache.ibatis.annotations.SelectProvider",
            "org.apache.ibatis.annotations.InsertProvider",
            "org.apache.ibatis.annotations.UpdateProvider",
            "org.apache.ibatis.annotations.DeleteProvider");

    /** MyBatis SqlSession 系列 receiver 类型。 */
    public static final Set<String> SQL_SESSION_TYPES = Set.of(
            "org.apache.ibatis.session.SqlSession",
            "org.mybatis.spring.SqlSessionTemplate",
            "org.mybatis.spring.support.SqlSessionDaoSupport");

    public static final Set<String> SQL_SESSION_METHODS = Set.of(
            "selectList", "selectOne", "selectMap", "selectCursor", "select", "insert", "update", "delete");

    /** iBatis 2 SqlMapClient 系列 receiver 类型。 */
    public static final Set<String> SQLMAP_CLIENT_TYPES = Set.of(
            "com.ibatis.sqlmap.client.SqlMapClient",
            "com.ibatis.sqlmap.client.SqlMapExecutor",
            "com.ibatis.sqlmap.client.SqlMapSession",
            "org.springframework.orm.ibatis.SqlMapClientTemplate",
            "org.springframework.orm.ibatis.SqlMapClientOperations");

    public static final Set<String> SQLMAP_CLIENT_METHODS = Set.of(
            "queryForList", "queryForObject", "queryForMap", "queryForPaginatedList", "queryWithRowHandler",
            "insert", "update", "delete");
}
