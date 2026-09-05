package com.mapperchecker.core.naming;

import java.util.Collection;
import java.util.regex.Pattern;

/**
 * 简单通配匹配：{@code *} 匹配任意字符序列，{@code ?} 匹配单个字符。用于忽略参数、忽略路径模式。
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String glob, String text) {
        if (glob == null || text == null) {
            return false;
        }
        if (glob.indexOf('*') < 0 && glob.indexOf('?') < 0) {
            return glob.equals(text);
        }
        return toPattern(glob).matcher(text).matches();
    }

    public static boolean matchesAny(Collection<String> globs, String text) {
        if (globs == null) {
            return false;
        }
        for (String g : globs) {
            if (matches(g, text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 路径通配：{@code **} 匹配任意层目录，{@code *} 不跨 /。路径统一用 / 分隔。
     */
    public static boolean matchesPath(String glob, String path) {
        if (glob == null || path == null) {
            return false;
        }
        String p = path.replace('\\', '/');
        String g = glob.replace('\\', '/');
        return toPathPattern(g).matcher(p).matches();
    }

    public static boolean matchesAnyPath(Collection<String> globs, String path) {
        if (globs == null) {
            return false;
        }
        for (String g : globs) {
            if (matchesPath(g, path)) {
                return true;
            }
        }
        return false;
    }

    static Pattern toPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    static Pattern toPathPattern(String glob) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    // "**/" 匹配零或多层目录；单独 "**" 匹配任意
                    if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        // 允许模式不带前导路径时匹配任意前缀
        String regex = sb.append('$').toString();
        if (!glob.startsWith("**") && !glob.startsWith("/")) {
            regex = "^(?:.*/)?" + regex.substring(1);
        }
        return Pattern.compile(regex);
    }
}
