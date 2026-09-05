package com.mapperchecker.core.naming;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobAndAliasTest {

    @Test
    void 参数名通配() {
        assertTrue(GlobMatcher.matches("page*", "pageSize"));
        assertTrue(GlobMatcher.matches("*Sort", "defaultSort"));
        assertTrue(GlobMatcher.matches("poiId", "poiId"));
        assertFalse(GlobMatcher.matches("poiId", "PoiId"));
        assertTrue(GlobMatcher.matches("p?iId", "poiId"));
        assertTrue(GlobMatcher.matchesAny(List.of("a", "page*"), "pageNo"));
        assertFalse(GlobMatcher.matchesAny(List.of(), "x"));
    }

    @Test
    void 路径通配() {
        assertTrue(GlobMatcher.matchesPath("**/mapper/oracle/**", "src/main/resources/mapper/oracle/OrderMapper.xml"));
        assertFalse(GlobMatcher.matchesPath("**/mapper/oracle/**", "src/main/resources/mapper/mysql/OrderMapper.xml"));
        assertTrue(GlobMatcher.matchesPath("mapper/oracle/*.xml", "C:/proj/src/main/resources/mapper/oracle/A.xml"));
        assertFalse(GlobMatcher.matchesPath("mapper/oracle/*.xml", "mapper/oracle/sub/A.xml"));
        assertTrue(GlobMatcher.matchesPath("**/*Test.xml", "a\\b\\FooTest.xml"));
    }

    @Test
    void 位置参数别名组() {
        assertEquals(Set.of("a", "arg0", "param1"), AliasGroupBuilder.forPositional("a", 0));
        assertEquals(Set.of("arg1", "param2"), AliasGroupBuilder.forPositional(null, 1));
    }

    @Test
    void 单集合与数组别名组() {
        assertEquals(Set.of("collection", "list", "ids", "arg0", "param1"), AliasGroupBuilder.forSingleCollection("ids", true));
        assertEquals(Set.of("collection", "ids", "arg0", "param1"), AliasGroupBuilder.forSingleCollection("ids", false));
        assertEquals(Set.of("array", "ids", "arg0", "param1"), AliasGroupBuilder.forSingleArray("ids"));
    }
}
