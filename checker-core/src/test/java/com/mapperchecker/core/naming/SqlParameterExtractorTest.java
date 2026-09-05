package com.mapperchecker.core.naming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterExtractorTest {

    private static List<String> roots(List<SqlParameterExtractor.Match> ms) {
        return ms.stream().map(SqlParameterExtractor.Match::rootName).toList();
    }

    @Test
    void mybatis基本() {
        var ms = SqlParameterExtractor.extractMyBatis(
                "SELECT * FROM t WHERE a = #{merchantId} AND b = #{query.poiId, jdbcType=BIGINT} ORDER BY ${orderBy}");
        assertEquals(List.of("merchantId", "query", "orderBy"), roots(ms));
        assertFalse(ms.get(0).substitution());
        assertTrue(ms.get(2).substitution());
        assertEquals("merchantId", ms.get(0).inner());
    }

    @Test
    void mybatis偏移正确() {
        String sql = "x = #{a}";
        var ms = SqlParameterExtractor.extractMyBatis(sql);
        assertEquals(4, ms.get(0).offset());
        assertEquals(4, ms.get(0).length());
        assertEquals("#{a}", sql.substring(ms.get(0).offset(), ms.get(0).offset() + ms.get(0).length()));
    }

    @Test
    void mybatis静态访问排除() {
        var ms = SqlParameterExtractor.extractMyBatis("a = ${@com.x.Const@V} and b = #{x}");
        assertEquals(List.of("x"), roots(ms));
    }

    @Test
    void mybatis注释内照常提取() {
        var ms = SqlParameterExtractor.extractMyBatis("-- #{poiId}\nSELECT 1");
        assertEquals(List.of("poiId"), roots(ms));
    }

    @Test
    void mybatis下标取根名() {
        var ms = SqlParameterExtractor.extractMyBatis("in (#{ids[0]}, #{ids[1]})");
        assertEquals(List.of("ids", "ids"), roots(ms));
    }

    @Test
    void ibatis基本() {
        var ms = SqlParameterExtractor.extractIBatis(
                "SELECT * FROM t WHERE a = #merchantId# AND b = #query.poiId:BIGINT# ORDER BY $orderBy$");
        assertEquals(List.of("merchantId", "query", "orderBy"), roots(ms));
        assertTrue(ms.get(2).substitution());
    }

    @Test
    void ibatis迭代语法() {
        var ms = SqlParameterExtractor.extractIBatis("IN <iterate> #ids[]# </iterate> AND #ids[].id#");
        assertEquals(List.of("ids", "ids"), roots(ms));
    }

    @Test
    void ibatis含空白的不算参数() {
        var ms = SqlParameterExtractor.extractIBatis("-- # comment here # \n a = #x#");
        assertEquals(List.of("x"), roots(ms));
    }

    @Test
    void 空输入() {
        assertTrue(SqlParameterExtractor.extractMyBatis(null).isEmpty());
        assertTrue(SqlParameterExtractor.extractIBatis("").isEmpty());
        assertTrue(SqlParameterExtractor.extractMyBatis("#{").isEmpty());
    }
}
