package com.mapperchecker.core.model;

/**
 * DAL-001（声明的参数、实体属性）与 DAL-010 共用的"上游有没有真的给过值"判定。
 * 只影响置信度与备注，不影响是否报告——原则不变：报告可疑，由使用者判断。
 * <p>
 * 判定很朴素：只把字面量 {@code null} 当"没给值"，其余（变量、方法调用、new 出来的对象……）
 * 一律当"给了值"。静态分析看不出变量运行时是不是恰好是 null，宁可漏报，不装作能看穿数据流。
 */
public enum UpstreamStatus {
    /** 至少一处上游确实给了值（非 null 字面量）：值可能被静默丢弃，更值得关注。 */
    ASSIGNED,
    /** 找到了赋值 / 传参的位置，但全部是字面量 null：大概率是可以直接删除的死参数。 */
    NOT_ASSIGNED,
    /** 没能确定（未开启分析、扫描范围外、反射拷贝等看不见的来源）：不下结论。 */
    UNKNOWN;

    /** 多处证据合并：只要有一处 ASSIGNED 就算 ASSIGNED，否则只要有一处 NOT_ASSIGNED 就算 NOT_ASSIGNED。 */
    public UpstreamStatus merge(UpstreamStatus other) {
        if (this == ASSIGNED || other == ASSIGNED) {
            return ASSIGNED;
        }
        if (this == NOT_ASSIGNED || other == NOT_ASSIGNED) {
            return NOT_ASSIGNED;
        }
        return UNKNOWN;
    }
}
