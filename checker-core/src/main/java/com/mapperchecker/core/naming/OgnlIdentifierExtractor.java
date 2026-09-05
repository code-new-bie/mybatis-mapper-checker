package com.mapperchecker.core.naming;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 从 MyBatis {@code <if test="...">} 等 OGNL 表达式中提取参数引用。方案 8.6。
 * <pre>
 * 排除关键字          and or not null true false in instanceof ...
 * 排除 ( 之前的标识符   list.size() 中 size 是方法；foo(x) 中 foo 是方法
 * 排除 @ 开头的静态访问 @com.x.Const@VALUE
 * 排除内置变量         _parameter _databaseId
 * 排除局部名           foreach item/index、bind name
 * 排除字符串字面量内部
 * </pre>
 * {@link #extract} 返回根名（query.poiId → query），{@link #extractPaths} 返回完整路径（query.poiId），
 * 方法调用段之后的部分截断（list.size() → list；q.addr.city.length() → q.addr.city）。
 */
public final class OgnlIdentifierExtractor {

    private static final Set<String> KEYWORDS = Set.of(
            "and", "or", "not", "null", "true", "false", "in", "instanceof", "new",
            "eq", "neq", "lt", "gt", "lte", "gte", "band", "bor", "xor", "shl", "shr", "ushr",
            "this", "class");

    private static final Set<String> BUILTINS = Set.of("_parameter", "_databaseId");

    private OgnlIdentifierExtractor() {
    }

    public static Set<String> extract(String expression) {
        return extract(expression, Set.of());
    }

    /** 根名集合，保持出现顺序。 */
    public static Set<String> extract(String expression, Set<String> localNames) {
        Set<String> roots = new LinkedHashSet<>();
        for (String path : extractPaths(expression, localNames)) {
            String root = ParameterNameNormalizer.rootName(path);
            if (root != null) {
                roots.add(root);
            }
        }
        return Collections.unmodifiableSet(roots);
    }

    /**
     * 完整路径集合，保持出现顺序。
     *
     * @param expression OGNL 表达式
     * @param localNames 局部名（foreach item / index、bind name），以其为根的路径不计入
     */
    public static Set<String> extractPaths(String expression, Set<String> localNames) {
        Set<String> result = new LinkedHashSet<>();
        if (expression == null || expression.isBlank()) {
            return result;
        }
        String s = expression;
        int n = s.length();
        int i = 0;
        // instanceof / new 之后跟的是类型名（可能带包名），整段跳过
        boolean inTypeName = false;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipString(s, i, c);
                inTypeName = false;
                continue;
            }
            if (c == '@') {
                // 静态访问：@pkg.Class@member 或 @pkg.Class@method(
                i++;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '@')) {
                    i++;
                }
                inTypeName = false;
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                // 读一段点分路径：ident(.ident)*，遇到方法调用段停止
                StringBuilder path = new StringBuilder();
                boolean truncatedByCall = false;
                boolean first = true;
                while (i < n && Character.isJavaIdentifierStart(s.charAt(i))) {
                    int start = i;
                    while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                        i++;
                    }
                    String ident = s.substring(start, i);
                    int next = skipSpaces(s, i);
                    boolean isCall = next < n && s.charAt(next) == '(';
                    if (isCall) {
                        truncatedByCall = true;
                        // 跳过方法名，路径到此为止；括号内的内容由主循环继续处理
                        break;
                    }
                    if (!first) {
                        path.append('.');
                    }
                    path.append(ident);
                    first = false;
                    int afterDot = skipSpaces(s, i);
                    if (afterDot < n && s.charAt(afterDot) == '.') {
                        int identStart = skipSpaces(s, afterDot + 1);
                        if (identStart < n && Character.isJavaIdentifierStart(s.charAt(identStart))) {
                            i = identStart;
                            continue;
                        }
                    }
                    break;
                }
                String p = path.toString();
                if (p.isEmpty()) {
                    inTypeName = false;
                    continue;
                }
                String root = ParameterNameNormalizer.rootName(p);
                if (inTypeName) {
                    inTypeName = false;
                    continue;
                }
                if ("instanceof".equals(p) || "new".equals(p)) {
                    inTypeName = true;
                    continue;
                }
                if (root != null && !KEYWORDS.contains(root) && !BUILTINS.contains(root)
                        && !localNames.contains(root) && !isNumericLike(root)
                        && !(truncatedByCall && p.indexOf('.') < 0 && path.length() == 0)) {
                    result.add(p);
                }
                continue;
            }
            if (Character.isDigit(c)) {
                while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                inTypeName = false;
                continue;
            }
            if (!Character.isWhitespace(c) && c != '.') {
                inTypeName = false;
            }
            i++;
        }
        return Collections.unmodifiableSet(result);
    }

    private static int skipString(String s, int i, char quote) {
        int n = s.length();
        i++;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static int skipSpaces(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isNumericLike(String ident) {
        return ident.isEmpty() || Character.isDigit(ident.charAt(0));
    }
}
