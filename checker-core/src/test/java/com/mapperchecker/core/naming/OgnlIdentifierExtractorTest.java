package com.mapperchecker.core.naming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OgnlIdentifierExtractorTest {

    @Test
    void 基本表达式() {
        assertEquals(List.of("poiId", "status"),
                List.copyOf(OgnlIdentifierExtractor.extract("poiId != null and status > 0")));
    }

    @Test
    void 属性访问只取根名() {
        assertEquals(List.of("query", "ids", "status"),
                List.copyOf(OgnlIdentifierExtractor.extract(
                        "query.poiId != null and ids.size() > 0 and status == 'ON'")));
    }

    @Test
    void 方法调用排除方法名保留参数() {
        assertEquals(List.of("x"), List.copyOf(OgnlIdentifierExtractor.extract("foo(x) != null")));
        assertEquals(List.of("list"), List.copyOf(OgnlIdentifierExtractor.extract("list.size ( ) > 0")));
    }

    @Test
    void 静态访问排除() {
        assertEquals(List.of("status"),
                List.copyOf(OgnlIdentifierExtractor.extract("status == @com.example.Const@ON")));
        assertEquals(List.of("s"),
                List.copyOf(OgnlIdentifierExtractor.extract("@org.apache.commons.lang3.StringUtils@isNotBlank(s)")));
    }

    @Test
    void 内置变量与关键字排除() {
        assertEquals(List.of("poiId"),
                List.copyOf(OgnlIdentifierExtractor.extract("_parameter != null and poiId != null and true")));
        assertEquals(List.of("a", "b"),
                List.copyOf(OgnlIdentifierExtractor.extract("a instanceof java.lang.String or b eq null")));
    }

    @Test
    void 字符串字面量内部排除() {
        assertEquals(List.of("type"),
                List.copyOf(OgnlIdentifierExtractor.extract("type == 'poiId and merchantId'")));
        assertEquals(List.of("type"),
                List.copyOf(OgnlIdentifierExtractor.extract("type == \"x.y\"")));
        assertEquals(List.of("s"),
                List.copyOf(OgnlIdentifierExtractor.extract("s == 'it''s'.toString()")));
    }

    @Test
    void 局部名排除() {
        assertEquals(List.of("ids"),
                List.copyOf(OgnlIdentifierExtractor.extract("ids != null and it != null and i > 0", Set.of("it", "i"))));
    }

    @Test
    void 数字排除() {
        assertEquals(List.of("a"), List.copyOf(OgnlIdentifierExtractor.extract("a > 10 and 1.5 < 2e3")));
    }

    @Test
    void 空表达式() {
        assertTrue(OgnlIdentifierExtractor.extract(null).isEmpty());
        assertTrue(OgnlIdentifierExtractor.extract("  ").isEmpty());
    }

    @Test
    void 保持出现顺序去重() {
        assertEquals(List.of("a", "b"),
                List.copyOf(OgnlIdentifierExtractor.extract("a != null and b != null and a > b")));
    }
}
