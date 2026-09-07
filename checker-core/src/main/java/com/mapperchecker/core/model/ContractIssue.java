package com.mapperchecker.core.model;

import java.util.List;
import java.util.Objects;

/**
 * 一条检查结果。
 *
 * @param ruleId            规则
 * @param severity          级别
 * @param confidence        置信度
 * @param message           中文主文案
 * @param remark            中文备注，可为空串
 * @param parameterName     涉及的参数名（DAL-001），其他规则为空串
 * @param statementId       涉及的 statement 完整 id
 * @param callPath          跨方法调用路径，无则空列表
 * @param primaryLocation   报告双击跳转位置（Java 侧）
 * @param secondaryLocation Mapper statement 位置，未知时为 UNKNOWN
 * @param candidates        DAL-006 的候选位置列表；其他规则为空列表
 */
public record ContractIssue(
        RuleId ruleId,
        Severity severity,
        Confidence confidence,
        String message,
        String remark,
        String parameterName,
        String statementId,
        List<String> callPath,
        SourceLocation primaryLocation,
        SourceLocation secondaryLocation,
        List<SourceLocation> candidates) {

    public ContractIssue {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(primaryLocation, "primaryLocation");
        remark = remark == null ? "" : remark;
        parameterName = parameterName == null ? "" : parameterName;
        statementId = statementId == null ? "" : statementId;
        callPath = callPath == null ? List.of() : List.copyOf(callPath);
        secondaryLocation = secondaryLocation == null ? SourceLocation.UNKNOWN : secondaryLocation;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 抑制匹配用的键：statementId#parameterName。 */
    public String suppressionKey() {
        return statementId + "#" + parameterName;
    }
}
