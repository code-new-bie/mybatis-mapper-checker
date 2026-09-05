package com.mapperchecker.core.model;

/**
 * 规则编号。前缀 MMC = MyBatis Mapper Checker。
 */
public enum RuleId {
    /** 参数已声明或传入，但 Mapper SQL 未使用。 */
    MMC001,
    /** statement 不存在。 */
    MMC002,
    /** statement 存在多个候选，无法确定目标。 */
    MMC003;

    /** 默认级别。 */
    public Severity defaultSeverity() {
        return switch (this) {
            case MMC001, MMC003 -> Severity.WARNING;
            case MMC002 -> Severity.ERROR;
        };
    }
}
