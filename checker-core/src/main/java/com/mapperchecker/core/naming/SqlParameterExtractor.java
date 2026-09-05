package com.mapperchecker.core.naming;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 SQL 文本中提取内联参数引用。纯字符串处理，XML 与注解 SQL 共用。
 * <pre>
 * MyBatis   #{poiId}  #{poiId, jdbcType=VARCHAR}  ${orderBy}
 * iBatis 2  #poiId#   #poiId:VARCHAR#             $orderBy$
 * </pre>
 * 注释内的引用照常提取：MyBatis 不识别 SQL 注释，照样替换。
 */
public final class SqlParameterExtractor {

    /**
     * 一处引用。offset 为在原文本中的起始偏移（含定界符），inner 为定界符内的原始内容，
     * path 为去掉属性与下标的完整点分路径（如 query.poiId）。
     */
    public record Match(String rootName, String path, String inner, boolean substitution, int offset, int length) {
    }

    private SqlParameterExtractor() {
    }

    /** MyBatis 语法：#{...} 与 ${...}。 */
    public static List<Match> extractMyBatis(String sql) {
        List<Match> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        int n = sql.length();
        int i = 0;
        while (i < n - 1) {
            char c = sql.charAt(i);
            if ((c == '#' || c == '$') && sql.charAt(i + 1) == '{') {
                int close = sql.indexOf('}', i + 2);
                if (close < 0) {
                    break;
                }
                String inner = sql.substring(i + 2, close);
                String root = ParameterNameNormalizer.inlineExpressionRoot(inner);
                if (root != null && ParameterNameNormalizer.isIdentifier(root)) {
                    String path = ParameterNameNormalizer.inlineExpressionPath(inner);
                    out.add(new Match(root, path == null ? root : path, inner, c == '$', i, close + 1 - i));
                }
                i = close + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    /** iBatis 2 语法：#...# 与 $...$。 */
    public static List<Match> extractIBatis(String sql) {
        List<Match> out = new ArrayList<>();
        if (sql == null) {
            return out;
        }
        extractDelimited(sql, '#', false, out);
        extractDelimited(sql, '$', true, out);
        out.sort((a, b) -> Integer.compare(a.offset(), b.offset()));
        return out;
    }

    private static void extractDelimited(String sql, char delim, boolean substitution, List<Match> out) {
        int n = sql.length();
        int i = 0;
        while (i < n) {
            int open = sql.indexOf(delim, i);
            if (open < 0) {
                return;
            }
            int close = sql.indexOf(delim, open + 1);
            if (close < 0) {
                return;
            }
            String inner = sql.substring(open + 1, close);
            // ## 是转义的单个 #，空内容或含空白 / 换行的不是参数
            if (!inner.isEmpty() && inner.chars().noneMatch(Character::isWhitespace)) {
                String root = ParameterNameNormalizer.inlineExpressionRoot(inner);
                if (root != null && ParameterNameNormalizer.isIdentifier(root)) {
                    String path = ParameterNameNormalizer.inlineExpressionPath(inner);
                    out.add(new Match(root, path == null ? root : path, inner, substitution, open, close + 1 - open));
                }
                i = close + 1;
            } else {
                // 不成对或非法：从 close 处继续，它可能是下一个引用的起点
                i = inner.isEmpty() ? close + 1 : close;
            }
        }
    }
}
