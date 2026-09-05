package com.mapperchecker.core.naming;

/**
 * 参数名归一化。方案 8.5 / 10.2。
 */
public final class ParameterNameNormalizer {

    private ParameterNameNormalizer() {
    }

    /**
     * setter 方法名转属性名，按 JavaBeans 规范（{@code Introspector.decapitalize}）。
     * <pre>
     * setPoiId  → poiId
     * setURL    → URL      （前两个字母都大写时保持原样）
     * setPoi_id → poi_id
     * setup     → null     （不是 setter）
     * set       → null
     * </pre>
     *
     * @return 属性名；不是 setter 时返回 null
     */
    public static String setterToProperty(String methodName) {
        if (methodName == null || methodName.length() <= 3 || !methodName.startsWith("set")) {
            return null;
        }
        String rest = methodName.substring(3);
        char first = rest.charAt(0);
        // setup / settle 之类不是 setter：set 后必须是大写字母、下划线或 $
        if (!Character.isUpperCase(first) && first != '_' && first != '$') {
            return null;
        }
        return decapitalize(rest);
    }

    /** {@code java.beans.Introspector.decapitalize} 的等价实现，避免依赖 java.desktop 模块。 */
    public static String decapitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1)) && Character.isUpperCase(name.charAt(0))) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * 属性路径取根名。
     * <pre>
     * query.poiId → query
     * ids[0]      → ids
     * ids[]       → ids
     * ids[].id    → ids
     * poiId       → poiId
     * </pre>
     */
    public static String rootName(String path) {
        if (path == null) {
            return null;
        }
        String s = path.trim();
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '[') {
                end = i;
                break;
            }
        }
        String root = s.substring(0, end).trim();
        return root.isEmpty() ? null : root;
    }

    /**
     * 内联参数表达式内容取根名。
     * <pre>
     * MyBatis  #{poiId, jdbcType=VARCHAR}  → poiId
     * MyBatis  #{query.poiId}              → query
     * iBatis   #poiId:VARCHAR#             → poiId
     * OGNL     @com.x.Const@VALUE          → null（静态访问，不是参数）
     * </pre>
     *
     * @return 根名；不是参数引用时返回 null
     */
    public static String inlineExpressionRoot(String inner) {
        if (inner == null) {
            return null;
        }
        String s = inner.trim();
        if (s.isEmpty() || s.charAt(0) == '@') {
            return null;
        }
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == ':' || Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
        return rootName(s.substring(0, cut));
    }

    /**
     * 内联参数表达式内容取完整属性路径（去掉 jdbcType 等属性与下标）。
     * <pre>
     * poiId, jdbcType=VARCHAR  → poiId
     * query.poiId              → query.poiId
     * ids[0]                   → ids
     * ids[].id                 → ids.id
     * poiId:VARCHAR            → poiId
     * @com.x.Const@V           → null
     * </pre>
     */
    public static String inlineExpressionPath(String inner) {
        if (inner == null) {
            return null;
        }
        String s = inner.trim();
        if (s.isEmpty() || s.charAt(0) == '@') {
            return null;
        }
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == ':' || Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
        return normalizePath(s.substring(0, cut));
    }

    /** 去掉下标，保留点分路径：ids[].id → ids.id，a[0].b → a.b。 */
    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (char c : path.trim().toCharArray()) {
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        String out = sb.toString();
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        while (out.endsWith(".")) {
            out = out.substring(0, out.length() - 1);
        }
        return out.isEmpty() ? null : out;
    }

    /** 路径 a.b.c 是否使用了属性 prefix（等于 prefix 或以 prefix. 开头）。 */
    public static boolean pathCovers(String path, String prefix) {
        return path != null && prefix != null && (path.equals(prefix) || path.startsWith(prefix + "."));
    }

    /** 是否为合法 Java 标识符形态（用于过滤明显不是参数名的内容）。 */
    public static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
