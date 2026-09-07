package com.mapperchecker.idea.run;

import com.mapperchecker.core.contract.Exemption;
import org.jetbrains.annotations.NotNull;

/** 报告里的一条已豁免记录：可导航的问题 + 命中的豁免。 */
public record ReportedExemption(@NotNull ReportedIssue reported, @NotNull Exemption exemption) {
}
