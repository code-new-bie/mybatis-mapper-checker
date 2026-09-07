package com.mapperchecker.core.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一处 {@code <include refid="..."><property name="k" value="v"/></include>}。
 * <p>
 * 片段是"单文件事实"，而 {@code ${k}} 的值只有引用点知道，所以把 property 一起存进索引，
 * 在 {@link com.mapperchecker.core.contract.StatementAssembler} 展开片段时再替换。
 * <p>
 * 为了不改动索引的序列化格式（include 就是一串字符串），编码成
 * {@code refidk=vk=v}；没有 property 时就是 refid 本身。
 *
 * @param refid      引用的片段 id，可能是短 id
 * @param properties {@code <property>} 名值对，按声明顺序
 */
public record IncludeRef(String refid, Map<String, String> properties) {

    private static final char SEP = '';

    public IncludeRef {
        refid = refid == null ? "" : refid;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public static String encode(String refid, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return refid;
        }
        StringBuilder sb = new StringBuilder(refid);
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (e.getKey().indexOf(SEP) >= 0 || e.getKey().indexOf('=') >= 0) {
                continue; // 名字里带分隔符的，宁可当没写
            }
            sb.append(SEP).append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    public static IncludeRef parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new IncludeRef("", Map.of());
        }
        int first = encoded.indexOf(SEP);
        if (first < 0) {
            return new IncludeRef(encoded, Map.of());
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String part : encoded.substring(first + 1).split(String.valueOf(SEP), -1)) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                props.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return new IncludeRef(encoded.substring(0, first), props);
    }
}
