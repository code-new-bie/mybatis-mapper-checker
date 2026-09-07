package com.mapperchecker.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型冒烟测试：验证默认值与展示逻辑。 */
class ModelSmokeTest {

    @Test
    void 别名组包含根名() {
        ParameterReference ref = ParameterReference.withAliases(
                "a", Set.of("arg0", "param1"), ParameterSourceType.METHOD_PARAM, SourceLocation.UNKNOWN);
        assertEquals(Set.of("a", "arg0", "param1"), ref.candidateNames());
        assertEquals(Confidence.MEDIUM, ref.confidence());
    }

    @Test
    void 无别名时候选名只有根名() {
        ParameterReference ref = ParameterReference.of("poiId", ParameterSourceType.MAP_PUT, SourceLocation.UNKNOWN);
        assertEquals(Set.of("poiId"), ref.candidateNames());
        assertEquals(Confidence.HIGH, ref.confidence());
    }

    @Test
    void statement短名取最后两段() {
        DaoInvocation inv = new DaoInvocation(InvocationKind.MAPPER_METHOD, Operation.SELECT,
                "com.example.order.dao.OrderMapper.queryOrder", List.of(), null, SourceLocation.UNKNOWN, "m");
        assertEquals("OrderMapper.queryOrder", inv.shortStatementId());
        assertTrue(inv.hasComparableParameters());
    }

    @Test
    void 参数为null表示不可比较() {
        DaoInvocation inv = new DaoInvocation(InvocationKind.MAPPER_METHOD, Operation.SELECT,
                "A.b", null, null, SourceLocation.UNKNOWN, null);
        assertFalse(inv.hasComparableParameters());
        assertNull(inv.parameters());
        assertEquals("", inv.moduleName());
    }

    @Test
    void 位置展示为文件名加行号() {
        SourceLocation loc = SourceLocation.of("src/main/java/com/example/OrderMapper.java", 10, 20, 18);
        assertEquals("OrderMapper.java:18", loc.display());
        assertTrue(loc.isKnown());
        assertFalse(SourceLocation.UNKNOWN.isKnown());
    }

    @Test
    void 规则默认级别() {
        assertEquals(Severity.WARNING, RuleId.DAL_001.defaultSeverity());
        assertEquals(Severity.ERROR, RuleId.DAL_005.defaultSeverity());
        assertEquals(Severity.WARNING, RuleId.DAL_006.defaultSeverity());
    }

    @Test
    void 置信度取低() {
        assertEquals(Confidence.LOW, Confidence.HIGH.min(Confidence.LOW));
        assertEquals(Confidence.MEDIUM, Confidence.MEDIUM.min(Confidence.HIGH));
    }

    @Test
    void 标签名映射() {
        assertEquals(StatementType.SELECT, StatementType.fromTagName("select"));
        assertEquals(StatementType.SQL_FRAGMENT, StatementType.fromTagName("sql"));
        assertNull(StatementType.fromTagName("resultMap"));
        assertEquals(Operation.DELETE, StatementType.DELETE.toOperation());
    }
}
