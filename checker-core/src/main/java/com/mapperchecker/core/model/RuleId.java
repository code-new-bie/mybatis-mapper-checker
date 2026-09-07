package com.mapperchecker.core.model;

/**
 * 规则编号。采用团队规范《开发规范：Query 与 Mapper 绑定》的 DAL-xxx 编号，
 * 豁免文件、报告、文档统一认这一套。枚举名用下划线，{@link #code()} 给出带连字符的正式编号。
 * <p>
 * 级别按规范映射：Blocker / Critical → ERROR，Major → WARNING，Minor → WEAK_WARNING。
 * DAL-001 规范标 Blocker，但它带置信度且实体属性级结果量大，默认保持 WARNING，可在设置里改。
 */
public enum RuleId {
    /** 条件失效：参数已声明或传入，但该条语句的 SQL 未使用（原 A）。 */
    DAL_001(Severity.WARNING, false),
    /** 改字段名未同步 XML：Mapper 引用了实体类里不存在的属性（原 B1/B2）。 */
    DAL_002(Severity.ERROR, false),
    /** 标准模板语句里 {@code <if test="a">} 块内绑定的不是 a（原 C）。 */
    DAL_003(Severity.ERROR, false),
    /** copyProperties 参数顺序写反，空对象被当作拷贝源（原 CP）。 */
    DAL_004(Severity.ERROR, false),
    /** statement 不存在。 */
    DAL_005(Severity.ERROR, false),
    /** statement 存在多个候选，无法确定目标。 */
    DAL_006(Severity.WARNING, false),
    /** 删条件未清理：调用方 set 了字段，但该 Query 类关联的所有语句都不引用它（原 D1）。 */
    DAL_010(Severity.ERROR, true),
    /** 死字段：既无人 set 也无语句引用（原 D2）。 */
    DAL_011(Severity.WEAK_WARNING, true),
    /** transform 方法内使用反射拷贝（原 S1）。 */
    DAL_020(Severity.WARNING, false),
    /** 跨模块同名 Query 类（原 S2）。 */
    DAL_021(Severity.WARNING, true),
    /** DAO 方法的 Query 参数 @Param 未统一命名为 query（原 S3）。 */
    DAL_022(Severity.WEAK_WARNING, false),
    /** 跨层转换改了字段名：setA(getB()) 且 A、B 高度相似（原 N1/N2）。 */
    DAL_030(Severity.WARNING, false);

    private final Severity defaultSeverity;
    private final boolean global;

    RuleId(Severity defaultSeverity, boolean global) {
        this.defaultSeverity = defaultSeverity;
        this.global = global;
    }

    /** 正式编号，如 DAL-001。 */
    public String code() {
        return name().replace('_', '-');
    }

    /** 默认级别。 */
    public Severity defaultSeverity() {
        return defaultSeverity;
    }

    /**
     * 全局规则：需要看完整个范围才能下结论（Query 类级聚合、跨模块重名），
     * 只在 Module / 项目级检查里运行，单文件检查与实时高亮不跑。
     */
    public boolean isGlobal() {
        return global;
    }

    /** 纯 Java 单文件规则，可选实时高亮。 */
    public boolean isPureJava() {
        return this == DAL_004 || this == DAL_020 || this == DAL_022 || this == DAL_030;
    }

    /**
     * 由编号解析：接受 DAL-001、DAL_001、dal-001；插件早期的 MMC 编号 001/002/003 映射到 DAL-001/005/006。
     * 不认识返回 null。
     */
    public static RuleId fromCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        switch (c) {
            case "MMC001":
                return DAL_001;
            case "MMC002":
                return DAL_005;
            case "MMC003":
                return DAL_006;
            default:
                break;
        }
        try {
            return valueOf(c);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
