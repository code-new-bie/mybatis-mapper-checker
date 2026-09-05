package com.mapperchecker.core.model;

/**
 * 置信度。只影响报告排序与展示，不影响是否报告。
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW;

    /** 取两者中较低的一个。 */
    public Confidence min(Confidence other) {
        return this.ordinal() >= other.ordinal() ? this : other;
    }
}
