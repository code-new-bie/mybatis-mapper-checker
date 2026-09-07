package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.naming.GlobMatcher;

/**
 * 一条团队共享的豁免记录，来自各模块仓库根目录的 {@code .binding-scan-ignore.yml}。规范第六节。
 * <pre>
 * - rule: DAL-010
 *   target: com.hjly.order.query.OrderQuery#poiId
 *   reason: poiId 由分库路由层读取，不进 SQL
 *   by: li.ruifeng
 *   at: 2026-09-07
 * </pre>
 * 被豁免的条目不算问题，但必须在汇总里可见：它是"已确认的记录"，不是"删掉"。
 *
 * @param rule       规则；为 null 表示任意规则
 * @param target     定位键，与 {@link ContractIssue#suppressionKey()}（statementId#参数）匹配，支持 * 通配；
 *                   不含 # 时只匹配 statementId / 类名部分
 * @param reason     理由（必填）
 * @param by         确认人（必填）
 * @param at         确认时间（必填）
 * @param sourcePath 所在 yml 文件
 * @param line       所在行（1 起始）
 */
public record Exemption(RuleId rule, String target, String reason, String by, String at, String sourcePath, int line) {

    public Exemption {
        target = target == null ? "" : target.trim();
        reason = reason == null ? "" : reason.trim();
        by = by == null ? "" : by.trim();
        at = at == null ? "" : at.trim();
        sourcePath = sourcePath == null ? "" : sourcePath;
    }

    /** reason / by / at 缺一不可，缺了的条目不生效（规范要求可追溯）。 */
    public boolean isComplete() {
        return !target.isEmpty() && !reason.isEmpty() && !by.isEmpty() && !at.isEmpty();
    }

    public boolean matches(ContractIssue issue) {
        if (issue == null || !isComplete()) {
            return false;
        }
        if (rule != null && rule != issue.ruleId()) {
            return false;
        }
        if (target.indexOf('#') < 0) {
            return GlobMatcher.matches(target, issue.statementId());
        }
        return GlobMatcher.matches(target, issue.suppressionKey());
    }

    /** 展示用：by @ at。 */
    public String signature() {
        return by + " @ " + at;
    }
}
