package com.mapperchecker.core.naming;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MyBatis 无 @Param 时的参数别名组。方案 9.2。
 * <p>
 * 第 N 个参数（0 起）可被 {@code argN}、{@code param(N+1)} 引用；编译开了 -parameters 时还能用实际名。
 * 静态分析无法确定编译参数，三种一起算，宁可漏报。
 */
public final class AliasGroupBuilder {

    private AliasGroupBuilder() {
    }

    /** 普通位置参数。 */
    public static Set<String> forPositional(String actualName, int index) {
        Set<String> names = new LinkedHashSet<>();
        if (actualName != null && !actualName.isBlank()) {
            names.add(actualName);
        }
        names.add("arg" + index);
        names.add("param" + (index + 1));
        return Collections.unmodifiableSet(names);
    }

    /** 单个 Collection / List 参数（无 @Param）。 */
    public static Set<String> forSingleCollection(String actualName, boolean isList) {
        Set<String> names = new LinkedHashSet<>();
        names.add("collection");
        if (isList) {
            names.add("list");
        }
        names.addAll(forPositional(actualName, 0));
        return Collections.unmodifiableSet(names);
    }

    /** 单个数组参数（无 @Param）。 */
    public static Set<String> forSingleArray(String actualName) {
        Set<String> names = new LinkedHashSet<>();
        names.add("array");
        names.addAll(forPositional(actualName, 0));
        return Collections.unmodifiableSet(names);
    }
}
