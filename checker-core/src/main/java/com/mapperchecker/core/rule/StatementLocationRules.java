package com.mapperchecker.core.rule;

import com.mapperchecker.core.Messages;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * DAL-005（statement 不存在）与 DAL-006（多候选）。方案 6.2 / 6.3。
 */
public final class StatementLocationRules {

    /** 定位结果：候选列表与是否可能定义在未索引依赖中。 */
    public record Lookup(List<ResolvedStatement> candidates, boolean maybeInLibrary, boolean dbVariantSuspected) {
        public Lookup {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static Lookup notFound(boolean maybeInLibrary) {
            return new Lookup(List.of(), maybeInLibrary, false);
        }

        public static Lookup single(ResolvedStatement statement) {
            return new Lookup(List.of(statement), false, false);
        }

        public boolean isUnique() {
            return candidates.size() == 1;
        }
    }

    private final CheckSettings settings;

    public StatementLocationRules(CheckSettings settings) {
        this.settings = settings == null ? CheckSettings.defaults() : settings;
    }

    /**
     * @return DAL-005 或 DAL-006 的问题；唯一命中时为空列表
     */
    public List<ContractIssue> check(DaoInvocation invocation, Lookup lookup) {
        List<ContractIssue> issues = new ArrayList<>();
        if (invocation == null || !invocation.hasStatementId() || lookup == null) {
            return issues;
        }
        if (settings.ignoredStatements().contains(invocation.statementId())) {
            return issues;
        }
        if (lookup.candidates().isEmpty()) {
            if (settings.isEnabled(RuleId.DAL_005)) {
                issues.add(new ContractIssue(
                        RuleId.DAL_005,
                        settings.severityOf(RuleId.DAL_005),
                        lookup.maybeInLibrary() ? Confidence.MEDIUM : Confidence.HIGH,
                        Messages.get("issue.DAL-005", invocation.shortStatementId()),
                        lookup.maybeInLibrary() ? Messages.get("remark.maybe.in.library") : "",
                        "",
                        invocation.statementId(),
                        List.of(),
                        invocation.location(),
                        SourceLocation.UNKNOWN,
                        List.of()));
            }
            return issues;
        }
        if (lookup.candidates().size() > 1 && settings.isEnabled(RuleId.DAL_006)) {
            List<SourceLocation> locations = new ArrayList<>();
            for (ResolvedStatement c : lookup.candidates()) {
                locations.addAll(c.definitions());
            }
            issues.add(new ContractIssue(
                    RuleId.DAL_006,
                    settings.severityOf(RuleId.DAL_006),
                    Confidence.HIGH,
                    Messages.get("issue.DAL-006", invocation.shortStatementId()),
                    lookup.dbVariantSuspected() ? Messages.get("remark.db.variant") : "",
                    "",
                    invocation.statementId(),
                    List.of(),
                    invocation.location(),
                    locations.isEmpty() ? SourceLocation.UNKNOWN : locations.get(0),
                    locations));
        }
        return issues;
    }
}
