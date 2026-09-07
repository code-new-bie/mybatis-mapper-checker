package com.mapperchecker.core.naming;

import com.mapperchecker.core.contract.RuleOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 规范 2.1 里列出的真实案例必须全部判对。 */
class NameSimilarityTest {

    @Test
    void 规范案例_手滑() {
        assertEquals(NameSimilarity.Kind.TYPO, NameSimilarity.classify("customId", "customerId"));
        assertEquals(NameSimilarity.Kind.TYPO, NameSimilarity.classify("protocolCateGory", "protocolCategory"));
        assertEquals(NameSimilarity.Kind.TYPO, NameSimilarity.classify("datasetLabeIds", "datasetLabelIds"));
        assertEquals(NameSimilarity.Kind.TYPO, NameSimilarity.classify("stockDateDetailId", "stockDataDetailId"));
    }

    @Test
    void 规范案例_单复数() {
        assertEquals(NameSimilarity.Kind.PLURAL, NameSimilarity.classify("dataStatuses", "dataStatus"));
        assertEquals(NameSimilarity.Kind.PLURAL, NameSimilarity.classify("id", "ids"));
        assertEquals(NameSimilarity.Kind.PLURAL, NameSimilarity.classify("labelId", "labelIdList"));
        assertEquals(NameSimilarity.Kind.PLURAL, NameSimilarity.classify("category", "categories"));
    }

    @Test
    void 相同与不同() {
        assertEquals(NameSimilarity.Kind.SAME, NameSimilarity.classify("poiId", "poiId"));
        assertEquals(NameSimilarity.Kind.SAME, NameSimilarity.classify("poi_id", "poiId"));
        assertEquals(NameSimilarity.Kind.DIFFERENT, NameSimilarity.classify("merchantId", "poiId"));
        assertEquals(NameSimilarity.Kind.DIFFERENT, NameSimilarity.classify("status", "statusCode"));
        // 短名字不判手滑
        assertEquals(NameSimilarity.Kind.DIFFERENT, NameSimilarity.classify("id", "ip"));
    }

    @Test
    void 编辑距离() {
        assertEquals(0, NameSimilarity.levenshtein("abc", "abc"));
        assertEquals(1, NameSimilarity.levenshtein("abc", "abd"));
        assertEquals(2, NameSimilarity.levenshtein("customid", "customerid"));
        assertEquals(3, NameSimilarity.levenshtein("", "abc"));
    }

    @Test
    void 最相似候选() {
        assertEquals("poiId", NameSimilarity.closest("poild", List.of("merchantId", "poiId", "status")));
        assertEquals("statuses", NameSimilarity.closest("status", List.of("statuses", "merchantId")));
        assertNull(NameSimilarity.closest("poiId", List.of("merchantId", "status")));
    }

    @Test
    void 规则选项() {
        RuleOptions o = RuleOptions.defaults();
        assertTrue(o.isQueryClassName("OrderQuery"));
        assertTrue(o.isQueryClassName("StockDetailWebQuery"));
        assertFalse(o.isQueryClassName("Query"));
        assertFalse(o.isQueryClassName("OrderDO"));
        assertEquals(0, o.copySourceIndex("org.springframework.beans.BeanUtils", "copyProperties"));
        assertEquals(1, o.copySourceIndex("com.hjly.commontool.util.core.BeanUtils", "copyProperties"));
        assertEquals(-1, o.copySourceIndex("com.x.Unknown", "copyProperties"));
        assertTrue(o.looksLikeTransformName("transformToQuery"));
        assertTrue(o.looksLikeTransformName("convert"));
        assertFalse(o.looksLikeTransformName("query"));
        assertTrue(o.isTemplateStatement("listByQuery"));
        assertFalse(o.isTemplateStatement("queryOrder"));

        RuleOptions custom = new RuleOptions(List.of("Query", "Param"),
                RuleOptions.parseCopyMethodLines(List.of("com.x.Copier#copy=1", "bad line", "com.y.C#m=5")),
                null, null);
        assertTrue(custom.isQueryClassName("SearchParam"));
        assertEquals(1, custom.copySourceIndex("com.x.Copier", "copy"));
        assertEquals(-1, custom.copySourceIndex("com.y.C", "m"));
        assertEquals(0, custom.copySourceIndex("org.springframework.beans.BeanUtils", "copyProperties"));
    }
}
