package com.mapperchecker.idea.java;

/**
 * 扫描过程中登记的一个属性的 setter 调用证据："实体全限定名#属性" → 这条记录。
 * 供 DAL-010（有没有人 set）与 DAL-001 属性级的上游赋值分析共用。
 *
 * @param exampleLocation 第一处调用点的展示位置（DAL-010 消息里"如 xxx"用）
 * @param anyRealValue    是否至少有一次传的不是字面量 null——只把 {@code x.setFoo(null)} 当"没给值"，
 *                        其余（变量、方法调用……）一律算"给了值"，宁可漏报也不装作能看穿数据流
 */
public record SetterEvidence(String exampleLocation, boolean anyRealValue) {

    /** 合并同一属性的多处调用证据：位置留第一次见到的，anyRealValue 只要有一次为真就为真。 */
    public SetterEvidence merge(SetterEvidence other) {
        return new SetterEvidence(exampleLocation, anyRealValue || other.anyRealValue());
    }
}
