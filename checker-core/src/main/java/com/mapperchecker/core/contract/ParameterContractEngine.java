package com.mapperchecker.core.contract;

import com.mapperchecker.core.Messages;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.naming.ParameterNameNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MMC001：Java 侧参数与 Mapper 侧参数做差集。方案第 11 节。
 * <pre>
 * 根级引用：候选 = Java 侧 rootName（含别名组）集合 − Mapper 侧 rootName 集合
 * 属性级引用（BEAN_PROPERTY）：propertyPath 未被任一 Mapper 路径覆盖（等于或以其为前缀）即为未使用
 * 别名组内任一名字被 Mapper 引用即视为已使用
 * 精确匹配，大小写敏感；仅大小写不同时仍报，附备注
 * 命中忽略配置（用户列表 + 内置分页名单）的不进入候选
 * 根级参数整体未使用时，不再逐个报它的属性
 * statement 为 partiallyParsed 时不报
 * 参数集合为 null（单标量等）时不报
 * </pre>
 */
public final class ParameterContractEngine {

    private final CheckSettings settings;

    public ParameterContractEngine(CheckSettings settings) {
        this.settings = settings == null ? CheckSettings.defaults() : settings;
    }

    /**
     * @param invocation Java 侧调用
     * @param statement  已解析的 Mapper statement
     * @return MMC001 问题列表；不产生问题时为空列表
     */
    public List<ContractIssue> check(DaoInvocation invocation, ResolvedStatement statement) {
        List<ContractIssue> issues = new ArrayList<>();
        if (!settings.isEnabled(RuleId.MMC001)) {
            return issues;
        }
        if (invocation == null || statement == null || !invocation.hasComparableParameters()) {
            return issues;
        }
        if (statement.partiallyParsed()) {
            return issues;
        }
        if (settings.ignoredStatements().contains(statement.fullId())) {
            return issues;
        }
        Set<String> mapperNames = statement.parameterNames();

        // 第一遍：根级引用
        Set<String> unusedRoots = new HashSet<>();
        for (ParameterReference ref : invocation.parameters()) {
            if (ref.sourceType() == ParameterSourceType.BEAN_PROPERTY) {
                continue;
            }
            if (isRootUsed(ref, mapperNames)) {
                continue;
            }
            unusedRoots.add(ref.rootName());
            if (settings.isIgnoredParameter(ref.rootName())) {
                continue;
            }
            issues.add(buildIssue(invocation, statement, ref, mapperNames, false));
        }

        // 第二遍：属性级引用
        if (!settings.checkBeanProperties()) {
            return issues;
        }
        for (ParameterReference ref : invocation.parameters()) {
            if (ref.sourceType() != ParameterSourceType.BEAN_PROPERTY) {
                continue;
            }
            // 所属根参数整体未使用：已报根级问题，属性不再逐个报
            if (ref.propertyPath().indexOf('.') > 0 && unusedRoots.contains(ref.rootName())) {
                continue;
            }
            if (statement.usesPath(ref.propertyPath())) {
                continue;
            }
            if (settings.isIgnoredParameter(ref.propertyPath())) {
                continue;
            }
            issues.add(buildIssue(invocation, statement, ref, mapperNames, true));
        }
        return issues;
    }

    private static boolean isRootUsed(ParameterReference ref, Set<String> mapperNames) {
        for (String name : ref.candidateNames()) {
            if (mapperNames.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private ContractIssue buildIssue(DaoInvocation invocation, ResolvedStatement statement,
                                     ParameterReference ref, Set<String> mapperNames, boolean property) {
        String shortId = invocation.shortStatementId();
        String message;
        if (property) {
            message = Messages.get("issue.MMC001.property", ref.ownerName(), ref.propertyPath(), shortId);
        } else {
            boolean declared = invocation.kind() == InvocationKind.MAPPER_METHOD
                    && (ref.sourceType() == ParameterSourceType.PARAM_ANNOTATION
                    || ref.sourceType() == ParameterSourceType.METHOD_PARAM);
            message = declared
                    ? Messages.get("issue.MMC001.declared", ref.rootName(), shortId)
                    : Messages.get("issue.MMC001.passed", ref.rootName(), shortId);
        }

        Confidence confidence = ref.confidence();
        if (!invocation.callPath().isEmpty()) {
            confidence = confidence.min(Confidence.MEDIUM);
        }

        StringBuilder remark = new StringBuilder();
        if (ref.sourceType() == ParameterSourceType.BEAN_SETTER) {
            remark.append(Messages.get("remark.bean.setter"));
        } else if (ref.sourceType() == ParameterSourceType.BEAN_PROPERTY) {
            remark.append(Messages.get("remark.bean.property"));
        }
        String caseVariant = property ? findCaseVariantPath(ref, statement) : findCaseVariant(ref, mapperNames);
        if (caseVariant != null) {
            if (remark.length() > 0) {
                remark.append(' ');
            }
            remark.append(Messages.get("remark.case.mismatch", caseVariant));
        }

        return new ContractIssue(
                RuleId.MMC001,
                settings.severityOf(RuleId.MMC001),
                confidence,
                message,
                remark.toString(),
                property ? ref.propertyPath() : ref.rootName(),
                statement.fullId(),
                invocation.callPath(),
                ref.location().isKnown() ? ref.location() : invocation.location(),
                statement.primaryLocation(),
                List.of());
    }

    private static String findCaseVariant(ParameterReference ref, Set<String> mapperNames) {
        for (String candidate : ref.candidateNames()) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            for (String m : mapperNames) {
                if (!m.equals(candidate) && m.toLowerCase(Locale.ROOT).equals(lower)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String findCaseVariantPath(ParameterReference ref, ResolvedStatement statement) {
        String lower = ref.propertyPath().toLowerCase(Locale.ROOT);
        for (String p : statement.parameterPaths()) {
            String root = ParameterNameNormalizer.rootName(p);
            if (root == null) {
                continue;
            }
            if (!p.equals(ref.propertyPath()) && p.toLowerCase(Locale.ROOT).equals(lower)) {
                return p;
            }
        }
        return null;
    }
}
