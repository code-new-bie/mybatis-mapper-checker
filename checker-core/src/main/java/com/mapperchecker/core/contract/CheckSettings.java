package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检查所需的配置快照。由 idea 层的 Project 设置转换而来，core 只读。
 *
 * @param ignoredParameterPatterns 忽略参数（支持通配）
 * @param ignoredStatements        忽略 statement（完整 id）
 * @param suppressedPairs          组合抑制 statementId#parameterName
 * @param ignoredPathPatterns      忽略路径模式
 * @param traceDepth               跨方法追踪深度
 * @param strictVisibility         true 严格模块依赖；false 整项目兼容
 * @param disabledRules            关闭的规则
 * @param severityOverrides        规则级别覆盖
 * @param ignoreBuiltinPagination  是否忽略内置分页 / 排序参数名单（默认开）
 * @param checkBeanProperties      是否展开实体参数逐属性检查（默认开，低置信度）
 * @param rules                    团队规范规则的可配置项
 */
public record CheckSettings(
        List<String> ignoredParameterPatterns,
        Set<String> ignoredStatements,
        Set<String> suppressedPairs,
        List<String> ignoredPathPatterns,
        int traceDepth,
        boolean strictVisibility,
        Set<RuleId> disabledRules,
        Map<RuleId, Severity> severityOverrides,
        boolean ignoreBuiltinPagination,
        boolean checkBeanProperties,
        RuleOptions rules) {

    public static final int DEFAULT_TRACE_DEPTH = 3;

    /** 兼容构造：无规则选项。 */
    public CheckSettings(List<String> ignoredParameterPatterns, Set<String> ignoredStatements, Set<String> suppressedPairs,
                         List<String> ignoredPathPatterns, int traceDepth, boolean strictVisibility,
                         Set<RuleId> disabledRules, Map<RuleId, Severity> severityOverrides,
                         boolean ignoreBuiltinPagination, boolean checkBeanProperties) {
        this(ignoredParameterPatterns, ignoredStatements, suppressedPairs, ignoredPathPatterns, traceDepth,
                strictVisibility, disabledRules, severityOverrides, ignoreBuiltinPagination, checkBeanProperties, null);
    }

    /** 兼容旧构造：分页忽略与实体属性检查均为默认开。 */
    public CheckSettings(List<String> ignoredParameterPatterns, Set<String> ignoredStatements, Set<String> suppressedPairs,
                         List<String> ignoredPathPatterns, int traceDepth, boolean strictVisibility,
                         Set<RuleId> disabledRules, Map<RuleId, Severity> severityOverrides) {
        this(ignoredParameterPatterns, ignoredStatements, suppressedPairs, ignoredPathPatterns, traceDepth,
                strictVisibility, disabledRules, severityOverrides, true, true, null);
    }

    public CheckSettings {
        ignoredParameterPatterns = ignoredParameterPatterns == null ? List.of() : List.copyOf(ignoredParameterPatterns);
        ignoredStatements = ignoredStatements == null ? Set.of() : Set.copyOf(ignoredStatements);
        suppressedPairs = suppressedPairs == null ? Set.of() : Set.copyOf(suppressedPairs);
        ignoredPathPatterns = ignoredPathPatterns == null ? List.of() : List.copyOf(ignoredPathPatterns);
        traceDepth = traceDepth <= 0 ? DEFAULT_TRACE_DEPTH : traceDepth;
        disabledRules = disabledRules == null ? Set.of() : Set.copyOf(disabledRules);
        severityOverrides = severityOverrides == null ? Map.of() : Map.copyOf(severityOverrides);
        rules = rules == null ? RuleOptions.defaults() : rules;
    }

    public static CheckSettings defaults() {
        return new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), DEFAULT_TRACE_DEPTH, true,
                Set.of(), new EnumMap<>(RuleId.class), true, true, RuleOptions.defaults());
    }

    /** 该参数名 / 路径是否命中忽略配置（用户列表 + 内置分页名单）。 */
    public boolean isIgnoredParameter(String nameOrPath) {
        if (nameOrPath == null) {
            return false;
        }
        int dot = nameOrPath.lastIndexOf('.');
        String last = dot < 0 ? nameOrPath : nameOrPath.substring(dot + 1);
        if (com.mapperchecker.core.naming.GlobMatcher.matchesAny(ignoredParameterPatterns, nameOrPath)
                || com.mapperchecker.core.naming.GlobMatcher.matchesAny(ignoredParameterPatterns, last)) {
            return true;
        }
        return ignoreBuiltinPagination && com.mapperchecker.core.naming.PaginationDefaults.matches(nameOrPath);
    }

    public boolean isEnabled(RuleId rule) {
        return !disabledRules.contains(rule);
    }

    public Severity severityOf(RuleId rule) {
        return severityOverrides.getOrDefault(rule, rule.defaultSeverity());
    }
}
