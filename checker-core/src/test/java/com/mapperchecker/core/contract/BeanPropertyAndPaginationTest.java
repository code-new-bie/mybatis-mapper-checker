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
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementType;
import com.mapperchecker.core.naming.OgnlIdentifierExtractor;
import com.mapperchecker.core.naming.PaginationDefaults;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实体属性级比对、内置分页忽略、OGNL 路径提取。 */
class BeanPropertyAndPaginationTest {

    private static final String FULL_ID = "com.example.dao.OrderMapper.q";
    private static final SourceLocation LOC = SourceLocation.of("OrderQuery.java", 1, 2, 3);

    private static ResolvedStatement statement(Set<String> paths) {
        Set<String> roots = new java.util.LinkedHashSet<>();
        for (String p : paths) {
            roots.add(ParameterNameNormalizer.rootName(p));
        }
        return new ResolvedStatement(FULL_ID, StatementType.SELECT, roots, paths, List.of(),
                List.of(SourceLocation.of("OrderMapper.xml", 1, 2, 3)), false, false);
    }

    private static DaoInvocation inv(List<ParameterReference> params) {
        return new DaoInvocation(InvocationKind.MAPPER_METHOD, Operation.SELECT, FULL_ID, params, null,
                SourceLocation.of("OrderMapper.java", 1, 2, 3), "m");
    }

    private static ParameterReference prop(String name) {
        return ParameterReference.beanProperty(name, name, "OrderQuery", LOC);
    }

    private static ParameterReference prop(String root, String path) {
        return ParameterReference.beanProperty(root, path, "OrderQuery", LOC);
    }

    @Test
    void 单Bean属性未使用报低置信度() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var issues = engine.check(inv(List.of(prop("merchantId"), prop("poiId"), prop("status"))),
                statement(Set.of("merchantId", "status")));
        assertEquals(1, issues.size());
        ContractIssue i = issues.get(0);
        assertEquals("poiId", i.parameterName());
        assertEquals(Confidence.LOW, i.confidence());
        assertEquals("实体 'OrderQuery' 的属性 'poiId' 未被 'OrderMapper.q' 的 Mapper SQL 使用。", i.message());
        assertEquals("属性来自实体类声明，该实体可能被多个 statement 共用，请人工确认。", i.remark());
        assertEquals(LOC, i.primaryLocation());
    }

    @Test
    void 嵌套路径覆盖算使用() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        // SQL 用了 address.city，属性 address 视为已使用
        assertTrue(engine.check(inv(List.of(prop("address"))), statement(Set.of("address.city"))).isEmpty());
    }

    @Test
    void Param_Bean属性按q前缀比对() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var root = ParameterReference.of("q", ParameterSourceType.PARAM_ANNOTATION, LOC);
        var issues = engine.check(inv(List.of(root, prop("q", "q.merchantId"), prop("q", "q.poiId"))),
                statement(Set.of("q.merchantId")));
        assertEquals(1, issues.size());
        assertEquals("q.poiId", issues.get(0).parameterName());
    }

    @Test
    void 根整体未使用时不逐个报属性() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var root = ParameterReference.of("q", ParameterSourceType.PARAM_ANNOTATION, LOC);
        var issues = engine.check(inv(List.of(root, prop("q", "q.a"), prop("q", "q.b"))), statement(Set.of("x")));
        assertEquals(1, issues.size());
        assertEquals("q", issues.get(0).parameterName());
        assertEquals(Confidence.HIGH, issues.get(0).confidence());
    }

    @Test
    void 关闭属性检查后不报() {
        var s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of(), true, false);
        var engine = new ParameterContractEngine(s);
        assertTrue(engine.check(inv(List.of(prop("poiId"))), statement(Set.of("x"))).isEmpty());
    }

    @Test
    void 内置分页参数默认忽略() {
        var engine = new ParameterContractEngine(CheckSettings.defaults());
        var params = List.of(
                ParameterReference.of("pageNum", ParameterSourceType.PARAM_ANNOTATION, LOC),
                ParameterReference.of("pageSize", ParameterSourceType.MAP_PUT, LOC),
                ParameterReference.of("orderBy", ParameterSourceType.MAP_PUT, LOC),
                ParameterReference.of("startRow", ParameterSourceType.BEAN_SETTER, LOC),
                prop("q", "q.pageSize"),
                prop("limit"),
                ParameterReference.of("poiId", ParameterSourceType.MAP_PUT, LOC));
        var issues = engine.check(inv(params), statement(Set.of("merchantId")));
        assertEquals(1, issues.size());
        assertEquals("poiId", issues.get(0).parameterName());
    }

    @Test
    void 关闭内置分页忽略后照常报() {
        var s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of(), false, true);
        var engine = new ParameterContractEngine(s);
        var issues = engine.check(inv(List.of(ParameterReference.of("pageSize", ParameterSourceType.MAP_PUT, LOC))),
                statement(Set.of("x")));
        assertEquals(1, issues.size());
    }

    @Test
    void 分页名单匹配() {
        assertTrue(PaginationDefaults.matches("pageNum"));
        assertTrue(PaginationDefaults.matches("query.pageSize"));
        assertTrue(PaginationDefaults.matches("curPageNo"));
        assertTrue(PaginationDefaults.matches("orderByClause"));
        assertTrue(PaginationDefaults.matches("maxLimit"));
        assertFalse(PaginationDefaults.matches("poiId"));
        assertFalse(PaginationDefaults.matches("merchantId"));
        assertFalse(PaginationDefaults.matches("packageName"));
    }

    @Test
    void 忽略参数按最后一段匹配() {
        var s = new CheckSettings(List.of("debug*"), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of(), false, true);
        assertTrue(s.isIgnoredParameter("q.debugFlag"));
        assertTrue(s.isIgnoredParameter("debugFlag"));
        assertFalse(s.isIgnoredParameter("q.poiId"));
    }

    @Test
    void OGNL路径提取() {
        assertEquals(List.of("query.poiId", "ids", "status"),
                List.copyOf(OgnlIdentifierExtractor.extractPaths(
                        "query.poiId != null and ids.size() > 0 and status == 'ON'", Set.of())));
        assertEquals(List.of("q.addr.city", "x"),
                List.copyOf(OgnlIdentifierExtractor.extractPaths("q.addr.city.length() > 0 and foo(x)", Set.of())));
        assertEquals(List.of("a"),
                List.copyOf(OgnlIdentifierExtractor.extractPaths("a instanceof java.lang.String", Set.of())));
        assertEquals(List.of("ids"),
                List.copyOf(OgnlIdentifierExtractor.extractPaths("ids != null and it.id != null", Set.of("it"))));
        assertEquals(List.of("status"),
                List.copyOf(OgnlIdentifierExtractor.extractPaths("status == @com.x.Const@ON", Set.of())));
    }

    @Test
    void 路径归一化() {
        assertEquals("ids.id", ParameterNameNormalizer.normalizePath("ids[].id"));
        assertEquals("a.b", ParameterNameNormalizer.normalizePath("a[0].b"));
        assertEquals("q.poiId", ParameterNameNormalizer.inlineExpressionPath("q.poiId, jdbcType=BIGINT"));
        assertEquals("poiId", ParameterNameNormalizer.inlineExpressionPath("poiId:VARCHAR"));
        assertTrue(ParameterNameNormalizer.pathCovers("q.addr.city", "q.addr"));
        assertFalse(ParameterNameNormalizer.pathCovers("q.address", "q.addr"));
    }

    @Test
    void 语句路径集合() {
        var st = statement(Set.of("q.a.b", "c"));
        assertTrue(st.usesPath("q"));
        assertTrue(st.usesPath("q.a"));
        assertTrue(st.usesPath("q.a.b"));
        assertFalse(st.usesPath("q.a.b.c"));
        assertFalse(st.usesPath("a"));
        assertTrue(st.usesPath("c"));
    }
}
