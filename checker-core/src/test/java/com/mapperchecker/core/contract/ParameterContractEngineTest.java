package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.Operation;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterContractEngineTest {

    private static final String FULL_ID = "com.example.order.dao.OrderMapper.queryOrder";
    private static final SourceLocation JAVA_LOC = SourceLocation.of("OrderMapper.java", 100, 110, 18);
    private static final SourceLocation XML_LOC = SourceLocation.of("OrderMapper.xml", 50, 60, 7);

    private static ResolvedStatement statement(String... names) {
        return new ResolvedStatement(FULL_ID, StatementType.SELECT, Set.of(names), List.of(), List.of(XML_LOC), false, false);
    }

    private static DaoInvocation invocation(InvocationKind kind, List<ParameterReference> params) {
        return new DaoInvocation(kind, Operation.SELECT, FULL_ID, params, null, JAVA_LOC, "order-dao");
    }

    private static ParameterReference param(String name) {
        return ParameterReference.of(name, ParameterSourceType.PARAM_ANNOTATION, JAVA_LOC);
    }

    @Test
    void 声明级参数未使用报高置信度() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var issues = engine.check(
                invocation(InvocationKind.MAPPER_METHOD, List.of(param("merchantId"), param("poiId"), param("status"))),
                statement("merchantId", "status"));
        assertEquals(1, issues.size());
        ContractIssue issue = issues.get(0);
        assertEquals(RuleId.MMC001, issue.ruleId());
        assertEquals(Severity.WARNING, issue.severity());
        assertEquals(Confidence.HIGH, issue.confidence());
        assertEquals("poiId", issue.parameterName());
        assertEquals("参数 'poiId' 已声明在 'OrderMapper.queryOrder'，但对应 Mapper SQL 未使用该参数。", issue.message());
        assertEquals("", issue.remark());
        assertEquals(JAVA_LOC, issue.primaryLocation());
        assertEquals(XML_LOC, issue.secondaryLocation());
    }

    @Test
    void 全部使用无问题() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        assertTrue(engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("a"), param("b"))),
                statement("a", "b", "c")).isEmpty());
    }

    @Test
    void 别名组任一命中即算使用() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var a = ParameterReference.withAliases("a", Set.of("arg0", "param1"), ParameterSourceType.METHOD_PARAM, JAVA_LOC);
        var b = ParameterReference.withAliases("b", Set.of("arg1", "param2"), ParameterSourceType.METHOD_PARAM, JAVA_LOC);
        var issues = engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(a, b)), statement("param2"));
        assertEquals(1, issues.size());
        assertEquals("a", issues.get(0).parameterName());
        assertEquals(Confidence.MEDIUM, issues.get(0).confidence());
    }

    @Test
    void Map传参文案与位置在put行() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        SourceLocation putLoc = SourceLocation.of("OrderDao.java", 300, 307, 42);
        var ref = ParameterReference.of("poiId", ParameterSourceType.MAP_PUT, putLoc);
        var issues = engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(ref)), statement("merchantId"));
        assertEquals("参数 'poiId' 已传入 'OrderMapper.queryOrder'，但对应 Mapper SQL 未使用该参数。", issues.get(0).message());
        assertEquals(putLoc, issues.get(0).primaryLocation());
    }

    @Test
    void Bean_setter低置信度带备注() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var ref = ParameterReference.of("poiId", ParameterSourceType.BEAN_SETTER, JAVA_LOC);
        var issues = engine.check(invocation(InvocationKind.SQL_SESSION_CALL, List.of(ref)), statement("x"));
        assertEquals(Confidence.LOW, issues.get(0).confidence());
        assertEquals("参数来自 Bean setter，该属性可能另有用途，请人工确认。", issues.get(0).remark());
    }

    @Test
    void 跨方法降为中置信度并带路径() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var ref = ParameterReference.of("poiId", ParameterSourceType.MAP_PUT, JAVA_LOC);
        var inv = new DaoInvocation(InvocationKind.MAPPER_METHOD, Operation.SELECT, FULL_ID, List.of(ref),
                List.of("OrderDao.query()", "OrderDao.buildParams()"), JAVA_LOC, "m");
        var issues = engine.check(inv, statement("x"));
        assertEquals(Confidence.MEDIUM, issues.get(0).confidence());
        assertEquals(List.of("OrderDao.query()", "OrderDao.buildParams()"), issues.get(0).callPath());
    }

    @Test
    void 大小写不一致附备注() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var issues = engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("poiId"))), statement("poiid"));
        assertEquals(1, issues.size());
        assertEquals("Mapper 中存在 'poiid'，疑似大小写不一致。", issues.get(0).remark());
    }

    @Test
    void 忽略参数通配() {
        var settings = new CheckSettings(List.of("page*"), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of());
        var engine = new ParameterContractEngine(settings);
        var issues = engine.check(invocation(InvocationKind.MAPPER_METHOD,
                List.of(param("pageNo"), param("pageSize"), param("poiId"))), statement("x"));
        assertEquals(1, issues.size());
        assertEquals("poiId", issues.get(0).parameterName());
    }

    @Test
    void 忽略statement() {
        var settings = new CheckSettings(List.of(), Set.of(FULL_ID), Set.of(), List.of(), 3, true, Set.of(), Map.of());
        var engine = new ParameterContractEngine(settings);
        assertTrue(engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("a"))), statement("x")).isEmpty());
    }

    @Test
    void 规则关闭() {
        var settings = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(RuleId.MMC001), Map.of());
        var engine = new ParameterContractEngine(settings);
        assertTrue(engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("a"))), statement("x")).isEmpty());
    }

    @Test
    void 级别覆盖() {
        var settings = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(),
                Map.of(RuleId.MMC001, Severity.ERROR));
        var engine = new ParameterContractEngine(settings);
        assertEquals(Severity.ERROR, engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("a"))),
                statement("x")).get(0).severity());
    }

    @Test
    void 参数不可比较时不报() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        assertTrue(engine.check(invocation(InvocationKind.MAPPER_METHOD, null), statement()).isEmpty());
    }

    @Test
    void 部分解析的statement不报() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var partial = new ResolvedStatement(FULL_ID, StatementType.SELECT, Set.of(), List.of(), List.of(XML_LOC), true, false);
        assertTrue(engine.check(invocation(InvocationKind.MAPPER_METHOD, List.of(param("a"))), partial).isEmpty());
    }
}
