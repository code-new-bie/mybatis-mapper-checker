package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.RuleId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 {@code .binding-scan-ignore.yml}。只支持"列表 + 平铺键值"这一种形态，够用且不引入 YAML 库：
 * <pre>
 * # 注释
 * - rule: DAL-010
 *   target: com.x.OrderQuery#poiId
 *   reason: "理由"
 *   by: 张三
 *   at: 2026-09-07
 * - target: com.x.OrderMapper.query
 *   reason: ...
 * </pre>
 * 不完整的条目（缺 reason / by / at）照常返回，由调用方登记为"无效豁免"提示，不静默丢弃。
 */
public final class ExemptionFileParser {

    public static final String FILE_NAME = ".binding-scan-ignore.yml";

    private ExemptionFileParser() {
    }

    public static List<Exemption> parse(String text, String sourcePath) {
        List<Exemption> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Map<String, String> current = null;
        int currentLine = 0;
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("- ")) {
                flush(out, current, currentLine, sourcePath);
                current = new LinkedHashMap<>();
                currentLine = i + 1;
                line = line.substring(2).trim();
                if (line.isEmpty()) {
                    continue;
                }
            } else if (line.equals("-")) {
                flush(out, current, currentLine, sourcePath);
                current = new LinkedHashMap<>();
                currentLine = i + 1;
                continue;
            }
            if (current == null) {
                continue; // 列表之外的内容忽略
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            current.put(key, value);
        }
        flush(out, current, currentLine, sourcePath);
        return out;
    }

    private static void flush(List<Exemption> out, Map<String, String> entry, int line, String sourcePath) {
        if (entry == null || entry.isEmpty()) {
            return;
        }
        RuleId rule = RuleId.fromCode(entry.get("rule"));
        out.add(new Exemption(rule, entry.get("target"), entry.get("reason"), entry.get("by"), entry.get("at"), sourcePath, line));
    }

    /** 去掉行内注释：引号外、且 # 前面有空白或在行首才算注释，避免误伤 target 里的 # 和引号内的文字。 */
    static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"");
        }
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** 生成一条可追加到文件末尾的记录文本。 */
    public static String format(Exemption e) {
        StringBuilder sb = new StringBuilder();
        if (e.rule() != null) {
            sb.append("- rule: ").append(e.rule().code()).append('\n');
            sb.append("  target: ").append(e.target()).append('\n');
        } else {
            sb.append("- target: ").append(e.target()).append('\n');
        }
        sb.append("  reason: ").append(quoteIfNeeded(e.reason())).append('\n');
        sb.append("  by: ").append(quoteIfNeeded(e.by())).append('\n');
        sb.append("  at: ").append(e.at()).append('\n');
        return sb.toString();
    }

    private static String quoteIfNeeded(String v) {
        if (v.indexOf(':') >= 0 || v.indexOf('#') >= 0 || v.startsWith("-") || v.startsWith("\"")) {
            return "\"" + v.replace("\"", "\\\"") + "\"";
        }
        return v;
    }
}
