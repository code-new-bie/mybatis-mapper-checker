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
 * @param upstream          上游赋值分析的结论；没跑过分析的一律 {@link UpstreamStatus#NOT_ANALYZED}。
 *                          报告与导出靠它把"追了但追不出结论"的单独分组，见 11 参构造的说明
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
        List<SourceLocation> candidates,
        UpstreamStatus upstream) {

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
        upstream = upstream == null ? UpstreamStatus.NOT_ANALYZED : upstream;
    }

    /**
     * 规则生成问题时用的构造：那时还没跑上游分析，{@code upstream} 一律 NOT_ANALYZED。
     * 只有 {@code UpstreamAssignmentAnalyzer} 会在收尾阶段带着结论重建这条问题。
     */
    public ContractIssue(RuleId ruleId, Severity severity, Confidence confidence, String message, String remark,
                         String parameterName, String statementId, List<String> callPath,
                         SourceLocation primaryLocation, SourceLocation secondaryLocation,
                         List<SourceLocation> candidates) {
        this(ruleId, severity, confidence, message, remark, parameterName, statementId, callPath,
                primaryLocation, secondaryLocation, candidates, UpstreamStatus.NOT_ANALYZED);
    }

    /**
     * 这条问题成不成立取决于上游有没有赋值，而上游追不出结论：报告里单独分组，不跟已确认的问题混在一起。
     * <p>
     * 只有"实体属性没被 SQL 使用"这类才会走到这里——实体被多个 statement 共用，某条 SQL 不用某个属性
     * 本来就正常，是否有问题全看这条链上到底有没有给它赋过值。方法签名声明了参数、SQL 却不用它，
     * 那是代码自身就能证实的契约不符，跟上游证据无关，不会被标成"证据不足"。
     */
    public boolean isEvidenceInsufficient() {
        return upstream == UpstreamStatus.UNKNOWN;
    }

    /** 抑制匹配用的键：statementId#parameterName。 */
    public String suppressionKey() {
        return statementId + "#" + parameterName;
    }
}
