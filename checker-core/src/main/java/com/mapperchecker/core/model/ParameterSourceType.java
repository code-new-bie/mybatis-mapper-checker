package com.mapperchecker.core.model;

/** 参数引用的来源，决定默认置信度。 */
public enum ParameterSourceType {
    // ---- Java 侧 ----
    /** 接口方法参数上的 @Param。 */
    PARAM_ANNOTATION(Confidence.HIGH),
    /** 接口方法参数，无 @Param，使用别名组。 */
    METHOD_PARAM(Confidence.MEDIUM),
    /** map.put("k", v)。 */
    MAP_PUT(Confidence.HIGH),
    /** Map.of / ImmutableMap.of 等。 */
    MAP_OF(Confidence.HIGH),
    /** bean.setX(v)。 */
    BEAN_SETTER(Confidence.LOW),
    /** 实体类声明的属性（单 Bean 参数或 @Param Bean 参数展开）。 */
    BEAN_PROPERTY(Confidence.LOW),

    // ---- Mapper 侧 ----
    /** #{x} / #x# 内联引用。 */
    XML_INLINE(Confidence.HIGH),
    /** ${x} / $x$ 文本替换。 */
    XML_SUBSTITUTION(Confidence.HIGH),
    /** 动态标签属性：property= / collection= / compareProperty= 等。 */
    XML_DYNAMIC_ATTR(Confidence.HIGH),
    /** <if test="..."> 表达式中提取的标识符。 */
    XML_TEST_EXPR(Confidence.HIGH),
    /** iBatis 2 parameterMap 中的 property。 */
    XML_PARAMETER_MAP(Confidence.HIGH),
    /** 注解 SQL 中的引用。 */
    ANNOTATION_SQL(Confidence.HIGH);

    private final Confidence defaultConfidence;

    ParameterSourceType(Confidence defaultConfidence) {
        this.defaultConfidence = defaultConfidence;
    }

    public Confidence defaultConfidence() {
        return defaultConfidence;
    }

    public boolean isJavaSide() {
        return this == PARAM_ANNOTATION || this == METHOD_PARAM
                || this == MAP_PUT || this == MAP_OF || this == BEAN_SETTER || this == BEAN_PROPERTY;
    }
}
