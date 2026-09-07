package com.mapperchecker.core.model;

import com.mapperchecker.core.contract.Exemption;

import java.util.Objects;

/**
 * 被豁免的问题：问题本身 + 命中的豁免记录。仍然进汇总，只是不算问题。
 */
public record ExemptedIssue(ContractIssue issue, Exemption exemption) {

    public ExemptedIssue {
        Objects.requireNonNull(issue, "issue");
        Objects.requireNonNull(exemption, "exemption");
    }
}
