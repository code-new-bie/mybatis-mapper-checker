package com.mapperchecker.core.model;

import java.util.Objects;

/**
 * 无法解析的调用。不是问题，但要在报告中可见。
 *
 * @param statementId 可能为空串
 * @param reason      原因
 * @param detail      补充说明（如被调方法名），可为空串
 * @param location    位置
 */
public record UnresolvedInvocation(
        String statementId,
        UnresolvedReason reason,
        String detail,
        SourceLocation location) {

    public UnresolvedInvocation {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(location, "location");
        statementId = statementId == null ? "" : statementId;
        detail = detail == null ? "" : detail;
    }
}
