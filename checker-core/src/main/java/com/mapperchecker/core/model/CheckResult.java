package com.mapperchecker.core.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次检查的完整结果。
 *
 * @param scopeName  范围展示名
 * @param issues     问题列表
 * @param unresolved 无法解析列表
 * @param statistics 统计
 */
public record CheckResult(
        String scopeName,
        List<ContractIssue> issues,
        List<UnresolvedInvocation> unresolved,
        Statistics statistics) {

    public CheckResult {
        Objects.requireNonNull(statistics, "statistics");
        scopeName = scopeName == null ? "" : scopeName;
        issues = issues == null ? List.of() : List.copyOf(issues);
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }

    public static CheckResult empty(String scopeName) {
        return new CheckResult(scopeName, List.of(), List.of(), new Statistics());
    }
}
