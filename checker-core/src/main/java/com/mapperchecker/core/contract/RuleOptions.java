package com.mapperchecker.core.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 团队规范相关规则（DAL-003 / 004 / 010 / 011 / 020 / 021 / 022 / 030）的可配置项。
 *
 * @param queryClassSuffixes     什么算 Query 类：简单类名以这些后缀结尾（默认 Query）
 * @param copyMethodSourceIndex  反射拷贝方法 "声明类全限定名#方法名" → 源对象参数下标（0 或 1）
 * @param templateStatementIds   DAL-003 只查这些模板 statement id（短 id）
 * @param transformMethodPrefixes DAL-020 认定为"转换方法"的方法名前缀；返回 Query 类的方法不看前缀也算
 */
public record RuleOptions(
        List<String> queryClassSuffixes,
        Map<String, Integer> copyMethodSourceIndex,
        Set<String> templateStatementIds,
        List<String> transformMethodPrefixes) {

    /** 内置的拷贝方法参数顺序。com.hjly 那个是团队自定义工具，源在后。 */
    public static final Map<String, Integer> BUILTIN_COPY_METHODS = Map.of(
            "org.springframework.beans.BeanUtils#copyProperties", 0,
            "org.apache.commons.beanutils.BeanUtils#copyProperties", 1,
            "org.apache.commons.beanutils.PropertyUtils#copyProperties", 1,
            "cn.hutool.core.bean.BeanUtil#copyProperties", 0,
            "org.springframework.cglib.beans.BeanCopier#copy", 0,
            "com.hjly.commontool.util.core.BeanUtils#copyProperties", 1);

    public static final Set<String> DEFAULT_TEMPLATE_STATEMENT_IDS = Set.of(
            "listByQuery", "listPageByQuery", "getByQuery", "countByQuery", "save", "update", "updateByQuery");

    public RuleOptions {
        queryClassSuffixes = queryClassSuffixes == null || queryClassSuffixes.isEmpty()
                ? List.of("Query") : List.copyOf(queryClassSuffixes);
        Map<String, Integer> merged = new LinkedHashMap<>(BUILTIN_COPY_METHODS);
        if (copyMethodSourceIndex != null) {
            merged.putAll(copyMethodSourceIndex);
        }
        copyMethodSourceIndex = Map.copyOf(merged);
        templateStatementIds = templateStatementIds == null || templateStatementIds.isEmpty()
                ? DEFAULT_TEMPLATE_STATEMENT_IDS : Set.copyOf(templateStatementIds);
        transformMethodPrefixes = transformMethodPrefixes == null || transformMethodPrefixes.isEmpty()
                ? List.of("transform", "convert", "to", "build") : List.copyOf(transformMethodPrefixes);
    }

    public static RuleOptions defaults() {
        return new RuleOptions(null, null, null, null);
    }

    /** 简单类名是否为 Query 类。 */
    public boolean isQueryClassName(String simpleName) {
        if (simpleName == null) {
            return false;
        }
        for (String suffix : queryClassSuffixes) {
            if (simpleName.endsWith(suffix) && simpleName.length() > suffix.length()) {
                return true;
            }
        }
        return false;
    }

    /** 反射拷贝方法的源参数下标；未登记返回 -1。 */
    public int copySourceIndex(String declaringClassFqn, String methodName) {
        Integer idx = copyMethodSourceIndex.get(declaringClassFqn + "#" + methodName);
        return idx == null ? -1 : idx;
    }

    /** 方法名是否像转换方法。 */
    public boolean looksLikeTransformName(String methodName) {
        if (methodName == null) {
            return false;
        }
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String p : transformMethodPrefixes) {
            if (lower.startsWith(p.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public boolean isTemplateStatement(String shortId) {
        return shortId != null && templateStatementIds.contains(shortId);
    }

    /** 解析设置里的 "fqn#method=index" 行。 */
    public static Map<String, Integer> parseCopyMethodLines(List<String> lines) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            try {
                int idx = Integer.parseInt(line.substring(eq + 1).trim());
                if (idx == 0 || idx == 1) {
                    out.put(key, idx);
                }
            } catch (NumberFormatException ignored) {
                // 跳过格式不对的行
            }
        }
        return out;
    }
}
