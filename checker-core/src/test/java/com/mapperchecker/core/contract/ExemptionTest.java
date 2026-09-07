package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.core.model.SourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExemptionTest {

    private static ContractIssue issue(RuleId rule, String statementId, String param) {
        return new ContractIssue(rule, Severity.WARNING, Confidence.HIGH, "m", "", param, statementId,
                List.of(), SourceLocation.UNKNOWN, SourceLocation.UNKNOWN, List.of());
    }

    @Test
    void 解析列表与注释与引号() {
        String yml = """
                # 团队豁免
                - rule: DAL-010
                  target: com.hjly.order.query.OrderQuery#poiId
                  reason: "poiId 由分库路由层读取: 不进 SQL"   # 行内注释
                  by: li.ruifeng
                  at: 2026-09-07
                -
                  target: com.hjly.order.dao.OrderMapper.legacy
                  reason: '历史遗留'
                  by: zhang
                  at: 2026-01-01
                - rule: MMC001
                  target: a#b
                  reason: 缺 by
                  at: 2026-01-01
                """;
        List<Exemption> list = ExemptionFileParser.parse(yml, "/repo/.binding-scan-ignore.yml");
        assertEquals(3, list.size());
        Exemption e0 = list.get(0);
        assertEquals(RuleId.DAL_010, e0.rule());
        assertEquals("com.hjly.order.query.OrderQuery#poiId", e0.target());
        assertEquals("poiId 由分库路由层读取: 不进 SQL", e0.reason());
        assertEquals("li.ruifeng", e0.by());
        assertEquals("2026-09-07", e0.at());
        assertEquals(2, e0.line());
        assertTrue(e0.isComplete());
        assertNull(list.get(1).rule());
        assertEquals("历史遗留", list.get(1).reason());
        assertEquals(RuleId.DAL_001, list.get(2).rule());
        assertFalse(list.get(2).isComplete());
    }

    @Test
    void 匹配规则与定位键() {
        Exemption exact = new Exemption(RuleId.DAL_010, "com.x.OrderQuery#poiId", "r", "b", "2026-01-01", "f", 1);
        assertTrue(exact.matches(issue(RuleId.DAL_010, "com.x.OrderQuery", "poiId")));
        assertFalse(exact.matches(issue(RuleId.DAL_011, "com.x.OrderQuery", "poiId")));
        assertFalse(exact.matches(issue(RuleId.DAL_010, "com.x.OrderQuery", "status")));

        Exemption anyRule = new Exemption(null, "com.x.OrderMapper.query#*", "r", "b", "2026-01-01", "f", 1);
        assertTrue(anyRule.matches(issue(RuleId.DAL_001, "com.x.OrderMapper.query", "poiId")));
        assertTrue(anyRule.matches(issue(RuleId.DAL_022, "com.x.OrderMapper.query", "q")));

        Exemption statementOnly = new Exemption(null, "com.x.OrderMapper.legacy", "r", "b", "2026-01-01", "f", 1);
        assertTrue(statementOnly.matches(issue(RuleId.DAL_005, "com.x.OrderMapper.legacy", "")));
        assertFalse(statementOnly.matches(issue(RuleId.DAL_005, "com.x.OrderMapper.other", "")));

        Exemption incomplete = new Exemption(RuleId.DAL_010, "com.x.OrderQuery#poiId", "", "b", "2026-01-01", "f", 1);
        assertFalse(incomplete.matches(issue(RuleId.DAL_010, "com.x.OrderQuery", "poiId")));
    }

    @Test
    void 格式化后可再解析() {
        Exemption e = new Exemption(RuleId.DAL_030, "com.x.Svc.convert#a<-b", "含: 冒号 # 井号", "li", "2026-09-07", "", 0);
        String text = ExemptionFileParser.format(e);
        List<Exemption> back = ExemptionFileParser.parse(text, "f");
        assertEquals(1, back.size());
        assertEquals(e.rule(), back.get(0).rule());
        assertEquals(e.target(), back.get(0).target());
        assertEquals(e.reason(), back.get(0).reason());
        assertEquals(e.by(), back.get(0).by());
        assertEquals(e.at(), back.get(0).at());
    }
}
