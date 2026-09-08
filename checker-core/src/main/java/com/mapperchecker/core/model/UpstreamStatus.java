package com.mapperchecker.core.model;

/**
 * DAL-001（声明的参数、实体属性）与 DAL-010 共用的"上游有没有真的给过值"判定。
 * <p>
 * 判定很朴素：只把字面量 {@code null} 当"没给值"，其余（变量、方法调用、new 出来的对象……）
 * 一律当"给了值"。静态分析看不出变量运行时是不是恰好是 null，宁可漏报，不装作能看穿数据流。
 * <p>
 * 四个取值里 {@link #UNKNOWN} 与 {@link #NOT_ANALYZED} 必须分开：前者是"追了但追不出结论"，
 * 后者是"压根没追"（分析开关关掉、或这条规则不走上游分析）。报告要把前者单独分组给人工确认，
 * 混在一起会把没分析过的问题也一并打上"证据不足"的标签。
 */
public enum UpstreamStatus {
    /** 至少一处上游确实给了值（非 null 字面量）：值可能被静默丢弃，更值得关注。 */
    ASSIGNED,
    /** 找到了赋值 / 传参的位置，但全部是字面量 null：大概率是可以直接删除的死参数。 */
    NOT_ASSIGNED,
    /** 追了调用链但没能确定（超出深度上限、接口多实现分不清、反射拷贝等看不见的来源）：不下结论。 */
    UNKNOWN,
    /** 没有做过上游分析（开关关闭，或该规则本来就不走这套判定）：不是"证据不足"，别混为一谈。 */
    NOT_ANALYZED;

    /** 多处证据合并：只要有一处 ASSIGNED 就算 ASSIGNED，否则只要有一处 NOT_ASSIGNED 就算 NOT_ASSIGNED。 */
    public UpstreamStatus merge(UpstreamStatus other) {
        if (this == ASSIGNED || other == ASSIGNED) {
            return ASSIGNED;
        }
        if (this == NOT_ASSIGNED || other == NOT_ASSIGNED) {
            return NOT_ASSIGNED;
        }
        return this == NOT_ANALYZED ? other : this;
    }
}
