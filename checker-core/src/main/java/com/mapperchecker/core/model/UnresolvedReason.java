package com.mapperchecker.core.model;

/** 无法解析的原因。文案键为 unresolved.<name>。 */
public enum UnresolvedReason {
    /** 参数对象是方法入参。 */
    METHOD_PARAM,
    /** 参数对象是类字段。 */
    FIELD,
    /** 被调方法存在多个实现。 */
    MULTI_IMPL,
    /** 跨方法追踪深度超限。 */
    DEPTH_EXCEEDED,
    /** 方法调用链循环。 */
    CYCLE,
    /** 被调方法位于库代码。 */
    LIBRARY_CODE,
    /** Map 被 putAll / remove / clear 修改。 */
    MAP_MUTATED,
    /** Map key 不是可求值常量。 */
    MAP_KEY_DYNAMIC,
    /** Provider 注解动态 SQL。 */
    PROVIDER,
    /** include refid 不可静态确定。 */
    DYNAMIC_INCLUDE,
    /** statementId 不可静态确定。 */
    STATEMENT_ID_DYNAMIC,
    /** Bean 参数无 setter 调用。 */
    BEAN_NO_SETTER,
    /** Bean 由 builder 链构造。 */
    BEAN_BUILDER,
    /** 其他无法归类的情形。 */
    OTHER
}
