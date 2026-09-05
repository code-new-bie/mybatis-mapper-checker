package com.mapperchecker.core.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一次参数引用，Java 侧与 Mapper 侧共用。
 * 根级比较看 {@link #rootName()} 与 {@link #aliases()}；属性级比较看 {@link #propertyPath()}。
 *
 * @param rootName     比较用的根名，如 query.poiId 的根名是 query
 * @param aliases      无 @Param 时的别名组（如 {a, arg0, param1}）；有明确名字时为空集合
 * @param propertyPath 完整路径，如 query.poiId；无路径时等于 rootName
 * @param ownerName    属性所属实体类简名（BEAN_PROPERTY 用），其他为空串
 * @param sourceType   来源
 * @param confidence   置信度
 * @param location     位置
 */
public record ParameterReference(
        String rootName,
        Set<String> aliases,
        String propertyPath,
        String ownerName,
        ParameterSourceType sourceType,
        Confidence confidence,
        SourceLocation location) {

    public ParameterReference {
        Objects.requireNonNull(rootName, "rootName");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(location, "location");
        aliases = aliases == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(aliases));
        propertyPath = propertyPath == null || propertyPath.isEmpty() ? rootName : propertyPath;
        ownerName = ownerName == null ? "" : ownerName;
    }

    /** 兼容旧构造：无 ownerName。 */
    public ParameterReference(String rootName, Set<String> aliases, String propertyPath,
                              ParameterSourceType sourceType, Confidence confidence, SourceLocation location) {
        this(rootName, aliases, propertyPath, "", sourceType, confidence, location);
    }

    /** 简单构造：无别名组，置信度取来源默认值。 */
    public static ParameterReference of(String rootName, ParameterSourceType type, SourceLocation location) {
        return new ParameterReference(rootName, Set.of(), rootName, "", type, type.defaultConfidence(), location);
    }

    /** 带完整路径的构造。 */
    public static ParameterReference of(String rootName, String propertyPath,
                                        ParameterSourceType type, SourceLocation location) {
        return new ParameterReference(rootName, Set.of(), propertyPath, "", type, type.defaultConfidence(), location);
    }

    /** 带别名组的构造（无 @Param 的方法参数）。 */
    public static ParameterReference withAliases(String rootName, Set<String> aliases,
                                                 ParameterSourceType type, SourceLocation location) {
        return new ParameterReference(rootName, aliases, rootName, "", type, type.defaultConfidence(), location);
    }

    /**
     * 实体属性引用。
     *
     * @param rootName     根名：单 Bean 无 @Param 时就是属性名；@Param("q") 时是 q
     * @param propertyPath 属性路径：poiId 或 q.poiId
     * @param ownerName    实体类简名
     */
    public static ParameterReference beanProperty(String rootName, String propertyPath, String ownerName,
                                                  SourceLocation location) {
        return new ParameterReference(rootName, Set.of(), propertyPath, ownerName, ParameterSourceType.BEAN_PROPERTY,
                ParameterSourceType.BEAN_PROPERTY.defaultConfidence(), location);
    }

    /** 复制并替换置信度。 */
    public ParameterReference withConfidence(Confidence newConfidence) {
        return new ParameterReference(rootName, aliases, propertyPath, ownerName, sourceType, newConfidence, location);
    }

    /**
     * 该引用可被哪些名字命中：根名 + 全部别名。
     */
    public Set<String> candidateNames() {
        if (aliases.isEmpty()) {
            return Set.of(rootName);
        }
        Set<String> names = new LinkedHashSet<>(aliases);
        names.add(rootName);
        return Collections.unmodifiableSet(names);
    }
}
