package com.mapperchecker.core.naming;

import java.util.Locale;

/**
 * 字段名相似度判定，供 DAL-030（跨层改名）与 DAL-002（改名漏改 XML 的近似提示）使用。
 * <pre>
 * SAME       归一化后完全相同
 * TYPO       编辑距离 ≤ 2 且不同：customId / customerId、protocolCateGory / protocolCategory
 * PLURAL     单复数或集合变体：status / statuses、id / ids / idList、labelId / labelIds
 * DIFFERENT  其他
 * </pre>
 */
public final class NameSimilarity {

    public enum Kind { SAME, TYPO, PLURAL, DIFFERENT }

    /** 编辑距离阈值：≤ 2 视为疑似手滑。 */
    public static final int TYPO_THRESHOLD = 2;

    private static final String[] COLLECTION_SUFFIXES = {"list", "set", "array", "collection", "ids", "es", "s"};

    private NameSimilarity() {
    }

    /** 小写、去下划线，用于编辑距离与单复数比较。 */
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace("_", "");
    }

    /** 下划线转驼峰但保留大小写：poi_id → poiId。用于"是否完全相同"的判定。 */
    public static String canonical(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean upperNext = false;
        for (char c : name.trim().toCharArray()) {
            if (c == '_') {
                upperNext = sb.length() > 0;
                continue;
            }
            sb.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return sb.toString();
    }

    public static Kind classify(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return Kind.DIFFERENT;
        }
        if (canonical(a).equals(canonical(b))) {
            return Kind.SAME;
        }
        // 仅大小写不同（protocolCateGory / protocolCategory）：在 Java 里就是两个字段，属手滑
        if (x.equals(y)) {
            return Kind.TYPO;
        }
        if (isPluralVariant(x, y)) {
            return Kind.PLURAL;
        }
        // 太短的名字编辑距离没有区分度：两边都 ≤3（id / ip）不判；一边短时只允许距离 1（cty / city）
        int longer = Math.max(x.length(), y.length());
        int shorter = Math.min(x.length(), y.length());
        if (longer <= 3) {
            return Kind.DIFFERENT;
        }
        int distance = levenshtein(x, y);
        int allowed = shorter <= 3 ? 1 : TYPO_THRESHOLD;
        return distance <= allowed ? Kind.TYPO : Kind.DIFFERENT;
    }

    /** 去掉集合后缀后相同，即为单复数 / 集合变体。 */
    static boolean isPluralVariant(String x, String y) {
        String sx = stripCollectionSuffix(x);
        String sy = stripCollectionSuffix(y);
        if (sx.equals(sy) && !x.equals(y)) {
            return true;
        }
        // status / statuses：es 复数；category / categories：ies → y
        return singular(x).equals(singular(y)) && !x.equals(y);
    }

    private static String stripCollectionSuffix(String s) {
        String base = singular(s);
        for (String suffix : COLLECTION_SUFFIXES) {
            if (base.endsWith(suffix) && base.length() > suffix.length() + 1) {
                return singular(base.substring(0, base.length() - suffix.length()));
            }
        }
        return base;
    }

    private static String singular(String s) {
        if (s.endsWith("ies") && s.length() > 4) {
            return s.substring(0, s.length() - 3) + "y";
        }
        if (s.endsWith("ses") || s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 2) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    public static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** 在候选集合里找与 name 最相似（TYPO 或 PLURAL）的一个；没有返回 null。 */
    public static String closest(String name, Iterable<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String c : candidates) {
            Kind k = classify(name, c);
            if (k == Kind.SAME) {
                return c;
            }
            if (k == Kind.TYPO || k == Kind.PLURAL) {
                int d = levenshtein(normalize(name), normalize(c));
                if (d < bestDistance) {
                    bestDistance = d;
                    best = c;
                }
            }
        }
        return best;
    }
}
