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

    /** 上调一级（HIGH 不变）。用于"有新证据支持，但静态分析仍不能打包票"的场景，不越级到 HIGH。 */
    public Confidence raise() {
        return switch (this) {
            case LOW -> MEDIUM;
            case MEDIUM, HIGH -> HIGH;
        };
    }

    /** 下调一级（LOW 不变）。 */
    public Confidence lower() {
        return switch (this) {
            case HIGH -> MEDIUM;
            case MEDIUM, LOW -> LOW;
        };
    }
}
