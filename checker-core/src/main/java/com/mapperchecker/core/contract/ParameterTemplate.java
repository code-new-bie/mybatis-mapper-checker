package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.naming.OgnlIdentifierExtractor;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.core.naming.SqlParameterExtractor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 参数名里嵌了 {@code ${}} 占位符的原始片段，例如 {@code #{${prefix}poiId}}、
 * {@code <if test="${prefix}poiId != null">}。这种参数名要等 {@code <include>} 的 {@code <property>}
 * 替换之后才知道，索引阶段只能把原文存下来，展开时再替换、再提取。
 * <p>
 * 注意只有"参数名内部"的占位符才走这里：{@code ${alias}.poi_id = #{poiId}} 里的 {@code ${alias}}
 * 是普通的 SQL 拼接，照常按替换型参数处理，不进模板，免得平白把一堆语句降级成部分解析。
 */
public final class ParameterTemplate {

    /** 文本片段（#{} / ${} 混排）。 */
    public static final char TEXT = 'x';
    /** OGNL 判断表达式（if / when 的 test）。 */
    public static final char TEST = 't';

    /** 提取结果：完整路径 + 来源类型。 */
    public record Extracted(String path, ParameterSourceType sourceType) {
    }

    private ParameterTemplate() {
    }

    public static String encode(char kind, String raw) {
        return kind + ":" + raw;
    }

    /** 文本里是否存在 {@code #{...}}，且它的内部又嵌了 {@code ${...}}。 */
    public static boolean hasPlaceholderInsideParam(String text) {
        if (text == null) {
            return false;
        }
        int i = 0;
        while ((i = text.indexOf("#{", i)) >= 0) {
            int close = text.indexOf('}', i + 2);
            int inner = text.indexOf("${", i + 2);
            if (inner >= 0 && (close < 0 || inner < close)) {
                return true;
            }
            if (close < 0) {
                return false;
            }
            i = close + 1;
        }
        return false;
    }

    /** 把 {@code ${k}} 换成 properties 里的值；没给值的原样留着，由调用方判定为部分解析。 */
    public static String substitute(String raw, Map<String, String> properties) {
        if (raw == null || raw.isEmpty() || raw.indexOf("${") < 0 || properties == null || properties.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String out = raw;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    /**
     * 替换后提取参数。
     *
     * @return null 表示占位符没给全，调用方应把语句标为部分解析
     */
    public static List<Extracted> resolve(String encoded, Map<String, String> properties) {
        if (encoded == null || encoded.length() < 2) {
            return List.of();
        }
        char kind = encoded.charAt(0);
        String raw = substitute(encoded.substring(2), properties);
        if (raw.contains("${")) {
            return null;
        }
        List<Extracted> out = new ArrayList<>();
        if (kind == TEST) {
            for (String path : OgnlIdentifierExtractor.extractPaths(raw, Set.of())) {
                out.add(new Extracted(path, ParameterSourceType.XML_TEST_EXPR));
            }
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (SqlParameterExtractor.Match m : SqlParameterExtractor.extractMyBatis(raw)) {
            String path = m.path() == null || m.path().isEmpty() ? m.rootName() : m.path();
            String root = ParameterNameNormalizer.rootName(path);
            if (root == null || !ParameterNameNormalizer.isIdentifier(root) || !seen.add(path)) {
                continue;
            }
            out.add(new Extracted(path, m.substitution() ? ParameterSourceType.XML_SUBSTITUTION : ParameterSourceType.XML_INLINE));
        }
        return out;
    }
}
