package com.mapperchecker.idea.report;

import com.mapperchecker.core.model.CheckResult;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.Statistics;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.idea.MapperCheckerBundle;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 报告导出为 Markdown / CSV。纯字符串生成，便于测试。
 * <p>
 * Markdown 分三部分：统计、按 statement 分组的详细列表（完整文案、参数、Java 与 Mapper 完整路径和行号、置信度、备注、调用路径、候选）、无法解析列表。
 */
public final class ReportExporter {

    private ReportExporter() {
    }

    public static @NotNull String toMarkdown(@NotNull CheckResult result) {
        StringBuilder sb = new StringBuilder();
        Statistics s = result.statistics();
        sb.append("# MyBatis Mapper Checker 报告\n\n");
        sb.append("- 范围：").append(result.scopeName()).append('\n');
        sb.append("- Mapper 接口：").append(s.mapperInterfaces()).append('\n');
        sb.append("- DAO 调用：").append(s.daoInvocations())
                .append("，成功解析 ").append(s.resolvedInvocations())
                .append("，无法解析 ").append(s.unresolvedInvocations())
                .append("，已抑制 ").append(s.suppressedIssues()).append('\n');
        sb.append("- 问题：").append(s.totalIssues())
                .append("（高 ").append(s.highIssues()).append(" / 中 ").append(s.mediumIssues())
                .append(" / 低 ").append(s.lowIssues()).append("）\n");
        sb.append("- 已豁免：").append(s.exemptedIssues());
        if (s.invalidExemptions() > 0) {
            sb.append("，另有 ").append(s.invalidExemptions()).append(" 条豁免记录缺 reason / by / at 未生效");
        }
        sb.append('\n');
        sb.append("- 已排除：").append(s.autoExcludedIssues())
                .append("（上游赋值分析确认从未真正赋值，SQL 未使用完全说得通，判定无害）\n");

        // 追了调用链但没能确定有没有赋值的，跟已确认的问题分开列，别混在一起看
        List<ContractIssue> solid = new java.util.ArrayList<>();
        List<ContractIssue> insufficient = new java.util.ArrayList<>();
        for (ContractIssue i : result.issues()) {
            (i.isEvidenceInsufficient() ? insufficient : solid).add(i);
        }
        sb.append("- 其中证据不足待人工确认：").append(insufficient.size()).append("\n\n");

        sb.append("## 问题概览\n\n");
        if (solid.isEmpty()) {
            sb.append(MapperCheckerBundle.message("report.empty.no.issue")).append("\n\n");
        } else {
            sb.append(overviewTable(solid));

            sb.append("## 问题详情\n\n");
            Map<String, List<ContractIssue>> byStatement = new LinkedHashMap<>();
            for (ContractIssue i : solid) {
                byStatement.computeIfAbsent(i.statementId().isEmpty() ? "-" : i.statementId(), k -> new java.util.ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<ContractIssue>> e : byStatement.entrySet()) {
                sb.append("### ").append(e.getKey()).append("\n\n");
                for (ContractIssue i : e.getValue()) {
                    sb.append("- **").append(i.ruleId().code()).append("** ").append(i.message()).append('\n');
                    if (!i.parameterName().isEmpty()) {
                        sb.append("  - 参数 / 属性：`").append(i.parameterName()).append("`\n");
                    }
                    sb.append("  - Java 位置：").append(fullLocation(i.primaryLocation())).append('\n');
                    if (i.secondaryLocation().isKnown()) {
                        sb.append("  - Mapper 位置：").append(fullLocation(i.secondaryLocation())).append('\n');
                    }
                    sb.append("  - 置信度：").append(confidence(i)).append('\n');
                    if (!i.remark().isEmpty()) {
                        sb.append("  - 备注：").append(i.remark()).append('\n');
                    }
                    if (!i.callPath().isEmpty()) {
                        sb.append("  - 调用路径：").append(String.join(" → ", i.callPath())).append('\n');
                    }
                    if (!i.candidates().isEmpty()) {
                        sb.append("  - 候选：\n");
                        for (SourceLocation c : i.candidates()) {
                            sb.append("    - ").append(fullLocation(c)).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
        }

        sb.append("## 证据不足，待人工确认\n\n");
        if (insufficient.isEmpty()) {
            sb.append("无\n\n");
        } else {
            sb.append("上游赋值分析沿调用链追过，但没能确定这些参数 / 属性到底有没有被赋过值")
                    .append("（对象经 Builder 或跨方法构造、值由反射拷贝填充、调用链超出深度上限、调用点在扫描范围之外）。")
                    .append("既不等于\"确认没赋值\"（那种已自动排除），也不等于\"确认有问题\"。\n\n");
            sb.append(overviewTable(insufficient));
        }

        sb.append("## 已豁免（已人工确认，不算问题）\n\n");
        if (result.exempted().isEmpty()) {
            sb.append("无\n\n");
        } else {
            sb.append("| 规则 | 参数 / 属性 | statement | Java 位置 | 豁免人 | 时间 | 理由 | 记录位置 |\n|---|---|---|---|---|---|---|---|\n");
            for (com.mapperchecker.core.model.ExemptedIssue e : result.exempted()) {
                ContractIssue i = e.issue();
                var x = e.exemption();
                sb.append("| ").append(i.ruleId().code())
                        .append(" | ").append(escape(i.parameterName()))
                        .append(" | ").append(escape(i.statementId()))
                        .append(" | ").append(escape(fullLocation(i.primaryLocation())))
                        .append(" | ").append(escape(x.by()))
                        .append(" | ").append(escape(x.at()))
                        .append(" | ").append(escape(x.reason()))
                        .append(" | ").append(escape(x.sourcePath().isEmpty() ? "-" : x.sourcePath() + ":" + x.line()))
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## 无法解析\n\n");
        if (result.unresolved().isEmpty()) {
            sb.append("无\n");
        } else {
            sb.append("| statement | 原因 | 说明 | 位置 |\n|---|---|---|---|\n");
            for (UnresolvedInvocation u : result.unresolved()) {
                sb.append("| ").append(escape(u.statementId()))
                        .append(" | ").append(MapperCheckerBundle.message("unresolved." + u.reason().name()))
                        .append(" | ").append(escape(u.detail()))
                        .append(" | ").append(escape(fullLocation(u.location())))
                        .append(" |\n");
            }
        }
        return sb.toString();
    }

    public static @NotNull String toCsv(@NotNull CheckResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("状态,规则,参数或属性,statement,说明,Java 文件,Java 行,Mapper 文件,Mapper 行,置信度,备注,调用路径,豁免人,豁免时间,豁免理由\n");
        for (com.mapperchecker.core.model.ExemptedIssue e : result.exempted()) {
            ContractIssue i = e.issue();
            sb.append("已豁免,").append(csv(i.ruleId().code())).append(',')
                    .append(csv(i.parameterName())).append(',')
                    .append(csv(i.statementId())).append(',')
                    .append(csv(i.message())).append(',')
                    .append(csv(i.primaryLocation().filePath())).append(',')
                    .append(csv(lineOf(i.primaryLocation()))).append(',')
                    .append(csv(i.secondaryLocation().isKnown() ? i.secondaryLocation().filePath() : "")).append(',')
                    .append(csv(lineOf(i.secondaryLocation()))).append(',')
                    .append(csv(confidence(i))).append(',')
                    .append(csv(i.remark())).append(',')
                    .append(csv(String.join(" -> ", i.callPath()))).append(',')
                    .append(csv(e.exemption().by())).append(',')
                    .append(csv(e.exemption().at())).append(',')
                    .append(csv(e.exemption().reason())).append('\n');
        }
        for (ContractIssue i : result.issues()) {
            sb.append(i.isEvidenceInsufficient() ? "证据不足," : "问题,").append(csv(i.ruleId().code())).append(',')
                    .append(csv(i.parameterName())).append(',')
                    .append(csv(i.statementId())).append(',')
                    .append(csv(i.message())).append(',')
                    .append(csv(i.primaryLocation().filePath())).append(',')
                    .append(csv(lineOf(i.primaryLocation()))).append(',')
                    .append(csv(i.secondaryLocation().isKnown() ? i.secondaryLocation().filePath() : "")).append(',')
                    .append(csv(lineOf(i.secondaryLocation()))).append(',')
                    .append(csv(confidence(i))).append(',')
                    .append(csv(i.remark())).append(',')
                    .append(csv(String.join(" -> ", i.callPath()))).append(",,,\n");
        }
        return sb.toString();
    }

    /** 概览表：两个分组共用同一张表头，对照着看不用换脑子。 */
    private static String overviewTable(List<ContractIssue> issues) {
        StringBuilder sb = new StringBuilder("| 规则 | 参数 / 属性 | statement | Java 位置 | 置信度 |\n|---|---|---|---|---|\n");
        for (ContractIssue i : issues) {
            sb.append("| ").append(i.ruleId().code())
                    .append(" | ").append(escape(i.parameterName()))
                    .append(" | ").append(escape(i.statementId()))
                    .append(" | ").append(escape(i.primaryLocation().display()))
                    .append(" | ").append(confidence(i))
                    .append(" |\n");
        }
        return sb.append('\n').toString();
    }

    static String confidence(ContractIssue i) {
        return MapperCheckerBundle.message("confidence." + i.confidence().name().toLowerCase(Locale.ROOT));
    }

    static String remarkWithPath(ContractIssue i) {
        if (i.callPath().isEmpty()) {
            return i.remark();
        }
        String path = MapperCheckerBundle.message("remark.call.path", String.join(" → ", i.callPath()));
        return i.remark().isEmpty() ? path : i.remark() + " " + path;
    }

    /** 完整路径:行号。 */
    static String fullLocation(SourceLocation loc) {
        if (loc == null || !loc.isKnown()) {
            return "-";
        }
        return loc.line() > 0 ? loc.filePath() + ":" + loc.line() : loc.filePath();
    }

    private static String lineOf(SourceLocation loc) {
        return loc != null && loc.line() > 0 ? String.valueOf(loc.line()) : "";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        boolean needQuote = s.contains(",") || s.contains("\"") || s.contains("\n");
        String v = s.replace("\"", "\"\"");
        return needQuote ? "\"" + v + "\"" : v;
    }
}
