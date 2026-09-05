package com.mapperchecker.core.model;

/** DAO 调用的来源形态。 */
public enum InvocationKind {
    /** MyBatis Mapper 接口方法声明。 */
    MAPPER_METHOD,
    /** MyBatis SqlSession / SqlSessionTemplate 字符串调用。 */
    SQL_SESSION_CALL,
    /** iBatis 2 SqlMapClient / SqlMapClientTemplate 字符串调用。 */
    SQLMAP_CLIENT_CALL
}
