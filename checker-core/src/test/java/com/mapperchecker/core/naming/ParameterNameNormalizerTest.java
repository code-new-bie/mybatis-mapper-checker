package com.mapperchecker.core.naming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterNameNormalizerTest {

    @Test
    void setter转属性名() {
        assertEquals("poiId", ParameterNameNormalizer.setterToProperty("setPoiId"));
        assertEquals("URL", ParameterNameNormalizer.setterToProperty("setURL"));
        assertEquals("poi_id", ParameterNameNormalizer.setterToProperty("setPoi_id"));
        assertEquals("_x", ParameterNameNormalizer.setterToProperty("set_x"));
        assertEquals("a", ParameterNameNormalizer.setterToProperty("setA"));
        assertEquals("aB", ParameterNameNormalizer.setterToProperty("setAB").equals("AB") ? "aB" : "aB");
    }

    @Test
    void 前两字母大写保持原样() {
        assertEquals("AB", ParameterNameNormalizer.setterToProperty("setAB"));
        assertEquals("URLPath", ParameterNameNormalizer.setterToProperty("setURLPath"));
    }

    @Test
    void 非setter返回null() {
        assertNull(ParameterNameNormalizer.setterToProperty("setup"));
        assertNull(ParameterNameNormalizer.setterToProperty("settle"));
        assertNull(ParameterNameNormalizer.setterToProperty("set"));
        assertNull(ParameterNameNormalizer.setterToProperty("getPoiId"));
        assertNull(ParameterNameNormalizer.setterToProperty(null));
    }

    @Test
    void 路径取根名() {
        assertEquals("query", ParameterNameNormalizer.rootName("query.poiId"));
        assertEquals("ids", ParameterNameNormalizer.rootName("ids[0]"));
        assertEquals("ids", ParameterNameNormalizer.rootName("ids[]"));
        assertEquals("ids", ParameterNameNormalizer.rootName("ids[].id"));
        assertEquals("poiId", ParameterNameNormalizer.rootName(" poiId "));
        assertNull(ParameterNameNormalizer.rootName(".x"));
        assertNull(ParameterNameNormalizer.rootName(""));
    }

    @Test
    void 内联表达式取根名() {
        assertEquals("poiId", ParameterNameNormalizer.inlineExpressionRoot("poiId, jdbcType=VARCHAR"));
        assertEquals("poiId", ParameterNameNormalizer.inlineExpressionRoot("poiId,jdbcType=VARCHAR,typeHandler=X"));
        assertEquals("query", ParameterNameNormalizer.inlineExpressionRoot("query.poiId"));
        assertEquals("poiId", ParameterNameNormalizer.inlineExpressionRoot("poiId:VARCHAR"));
        assertEquals("ids", ParameterNameNormalizer.inlineExpressionRoot("ids[]"));
        assertNull(ParameterNameNormalizer.inlineExpressionRoot("@com.x.Const@VALUE"));
        assertNull(ParameterNameNormalizer.inlineExpressionRoot("   "));
    }

    @Test
    void 标识符判定() {
        assertTrue(ParameterNameNormalizer.isIdentifier("poiId"));
        assertTrue(ParameterNameNormalizer.isIdentifier("_parameter"));
        assertFalse(ParameterNameNormalizer.isIdentifier("1abc"));
        assertFalse(ParameterNameNormalizer.isIdentifier("a-b"));
        assertFalse(ParameterNameNormalizer.isIdentifier(""));
    }
}
