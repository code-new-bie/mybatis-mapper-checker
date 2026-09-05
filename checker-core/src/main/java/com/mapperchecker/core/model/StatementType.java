package com.mapperchecker.core.model;

import java.util.Locale;

/** statement 标签类型。 */
public enum StatementType {
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    /** iBatis 2 通用 statement。 */
    STATEMENT,
    /** iBatis 2 存储过程。 */
    PROCEDURE,
    /** SQL 片段 <sql id="...">。 */
    SQL_FRAGMENT,
    UNKNOWN;

    /** 由标签名解析；不识别的返回 null。 */
    public static StatementType fromTagName(String tagName) {
        if (tagName == null) {
            return null;
        }
        return switch (tagName.toLowerCase(Locale.ROOT)) {
            case "select" -> SELECT;
            case "insert" -> INSERT;
            case "update" -> UPDATE;
            case "delete" -> DELETE;
            case "statement" -> STATEMENT;
            case "procedure" -> PROCEDURE;
            case "sql" -> SQL_FRAGMENT;
            default -> null;
        };
    }

    public boolean isStatement() {
        return this != SQL_FRAGMENT && this != UNKNOWN;
    }

    public Operation toOperation() {
        return switch (this) {
            case SELECT -> Operation.SELECT;
            case INSERT -> Operation.INSERT;
            case UPDATE -> Operation.UPDATE;
            case DELETE -> Operation.DELETE;
            default -> Operation.UNKNOWN;
        };
    }
}
