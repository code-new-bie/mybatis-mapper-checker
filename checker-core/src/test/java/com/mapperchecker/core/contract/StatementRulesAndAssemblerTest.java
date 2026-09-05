package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.DaoInvocation;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.Operation;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementSource;
import com.mapperchecker.core.model.StatementType;
import com.mapperchecker.core.rule.StatementLocationRules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementRulesAndAssemblerTest {

    private static final String NS = "com.example.order.dao.OrderMapper";
    private static final SourceLocation LOC = SourceLocation.of("OrderMapper.xml", 1, 2, 3);

    private static MapperStatement st(String ns, String id, StatementType type, List<String> params,
                                      List<String> includes, String dbId) {
        return new MapperStatement(StatementSource.MYBATIS_XML, ns, id, type, dbId,
                params.stream().map(p -> ParameterReference.of(p, ParameterSourceType.XML_INLINE, LOC)).toList(),
                includes, "", false, LOC, "m");
    }

    private static DaoInvocation inv(String statementId) {
        return new DaoInvocation(InvocationKind.MAPPER_METHOD, Operation.SELECT, statementId, List.of(), null,
                SourceLocation.of("OrderMapper.java", 1, 2, 3), "m");
    }

    private static ResolvedStatement resolved(String fullId, SourceLocation loc) {
        return new ResolvedStatement(fullId, StatementType.SELECT, Set.of(), List.of(), List.of(loc), false, false);
    }

    /** 内存版仓库，模拟 Index。 */
    private static final class MemRepo implements StatementAssembler.Repository {
        final Map<String, List<MapperStatement>> fragments = new HashMap<>();
        final Map<String, List<String>> paramMaps = new HashMap<>();

        @Override
        public List<MapperStatement> findFragments(String refid, String currentNamespace) {
            if (!refid.contains(".") && fragments.containsKey(currentNamespace + "." + refid)) {
                return fragments.get(currentNamespace + "." + refid);
            }
            return fragments.getOrDefault(refid, List.of());
        }

        @Override
        public List<String> findParameterMapProperties(String ref, String currentNamespace) {
            return paramMaps.getOrDefault(ref.contains(".") ? ref : currentNamespace + "." + ref, List.of());
        }

        @Override
        public boolean isLibraryLocation(SourceLocation location) {
            return location.filePath().contains(".jar!");
        }
    }

    @Test
    void MMC002未找到() {
        var rules = new StatementLocationRules(CheckSettings.defaults());
        var issues = rules.check(inv(NS + ".queryOrder"), StatementLocationRules.Lookup.notFound(false));
        assertEquals(1, issues.size());
        assertEquals(RuleId.MMC002, issues.get(0).ruleId());
        assertEquals(Confidence.HIGH, issues.get(0).confidence());
        assertEquals("'OrderMapper.queryOrder' 未找到对应的 Mapper statement 或 SQL 注解。", issues.get(0).message());
    }

    @Test
    void MMC002可能在依赖中降置信度() {
        var rules = new StatementLocationRules(CheckSettings.defaults());
        var issues = rules.check(inv(NS + ".queryOrder"), StatementLocationRules.Lookup.notFound(true));
        assertEquals(Confidence.MEDIUM, issues.get(0).confidence());
        assertEquals("可能定义在未索引的依赖中。", issues.get(0).remark());
    }

    @Test
    void MMC003多候选列出位置() {
        var rules = new StatementLocationRules(CheckSettings.defaults());
        var a = resolved(NS + ".queryOrder", SourceLocation.of("a/OrderMapper.xml", 1, 2, 3));
        var b = resolved(NS + ".queryOrder", SourceLocation.of("b/OrderMapper.xml", 1, 2, 3));
        var issues = rules.check(inv(NS + ".queryOrder"), new StatementLocationRules.Lookup(List.of(a, b), false, true));
        assertEquals(1, issues.size());
        assertEquals(RuleId.MMC003, issues.get(0).ruleId());
        assertEquals(2, issues.get(0).candidates().size());
        assertEquals("可能为多数据库变体。", issues.get(0).remark());
    }

    @Test
    void 唯一命中无问题() {
        var rules = new StatementLocationRules(CheckSettings.defaults());
        assertTrue(rules.check(inv(NS + ".q"), StatementLocationRules.Lookup.single(resolved(NS + ".q", LOC))).isEmpty());
    }

    @Test
    void 忽略statement不报定位问题() {
        var settings = new CheckSettings(List.of(), Set.of(NS + ".q"), Set.of(), List.of(), 3, true, Set.of(), Map.of());
        var rules = new StatementLocationRules(settings);
        assertTrue(rules.check(inv(NS + ".q"), StatementLocationRules.Lookup.notFound(false)).isEmpty());
    }

    @Test
    void include链式展开() {
        var repo = new MemRepo();
        repo.fragments.put("com.example.common.dao.Common.scope",
                List.of(st("com.example.common.dao.Common", "scope", StatementType.SQL_FRAGMENT,
                        List.of("merchantId"), List.of("poiScope"), "")));
        repo.fragments.put("com.example.common.dao.Common.poiScope",
                List.of(st("com.example.common.dao.Common", "poiScope", StatementType.SQL_FRAGMENT,
                        List.of("poiId"), List.of(), "")));
        var assembler = new StatementAssembler(repo);
        var def = st(NS, "queryOrder", StatementType.SELECT, List.of("status"), List.of("com.example.common.dao.Common.scope"), "");
        var resolved = assembler.assemble(NS + ".queryOrder", List.of(def));
        assertEquals(Set.of("status", "merchantId", "poiId"), resolved.parameterNames());
        assertFalse(resolved.partiallyParsed());
        assertEquals(StatementType.SELECT, resolved.type());
    }

    @Test
    void include短id在当前namespace解析() {
        var repo = new MemRepo();
        repo.fragments.put(NS + ".cols", List.of(st(NS, "cols", StatementType.SQL_FRAGMENT, List.of("a"), List.of(), "")));
        var resolved = new StatementAssembler(repo).assemble(NS + ".q",
                List.of(st(NS, "q", StatementType.SELECT, List.of(), List.of("cols"), "")));
        assertEquals(Set.of("a"), resolved.parameterNames());
    }

    @Test
    void include循环不死锁标部分解析() {
        var repo = new MemRepo();
        repo.fragments.put(NS + ".a", List.of(st(NS, "a", StatementType.SQL_FRAGMENT, List.of("x"), List.of("b"), "")));
        repo.fragments.put(NS + ".b", List.of(st(NS, "b", StatementType.SQL_FRAGMENT, List.of("y"), List.of("a"), "")));
        var resolved = new StatementAssembler(repo).assemble(NS + ".q",
                List.of(st(NS, "q", StatementType.SELECT, List.of(), List.of("a"), "")));
        assertTrue(resolved.partiallyParsed());
        assertEquals(Set.of("x", "y"), resolved.parameterNames());
    }

    @Test
    void 动态refid与缺失片段标部分解析() {
        var repo = new MemRepo();
        var r1 = new StatementAssembler(repo).assemble(NS + ".q",
                List.of(st(NS, "q", StatementType.SELECT, List.of(), List.of("${frag}"), "")));
        assertTrue(r1.partiallyParsed());
        var r2 = new StatementAssembler(repo).assemble(NS + ".q",
                List.of(st(NS, "q", StatementType.SELECT, List.of(), List.of("missing"), "")));
        assertTrue(r2.partiallyParsed());
    }

    @Test
    void databaseId变体合并取并集() {
        var repo = new MemRepo();
        var mysql = st(NS, "q", StatementType.SELECT, List.of("a"), List.of(), "mysql");
        var oracle = st(NS, "q", StatementType.SELECT, List.of("b"), List.of(), "oracle");
        var resolved = new StatementAssembler(repo).assemble(NS + ".q", List.of(mysql, oracle));
        assertEquals(Set.of("a", "b"), resolved.parameterNames());
        assertEquals(2, resolved.definitions().size());
    }

    @Test
    void parameterMap补入属性() {
        var repo = new MemRepo();
        repo.paramMaps.put("Order.pm", List.of("merchantId", "poiId"));
        var def = new MapperStatement(StatementSource.IBATIS_XML, "Order", "q", StatementType.SELECT, "",
                List.of(), List.of(), "pm", false, LOC, "m");
        var resolved = new StatementAssembler(repo).assemble("Order.q", List.of(def));
        assertEquals(Set.of("merchantId", "poiId"), resolved.parameterNames());
        assertFalse(resolved.partiallyParsed());
    }

    @Test
    void 库位置标记() {
        var repo = new MemRepo();
        var def = new MapperStatement(StatementSource.MYBATIS_XML, NS, "q", StatementType.SELECT, "",
                List.of(), List.of(), "", false, SourceLocation.of("dep.jar!/mapper/OrderMapper.xml", 1, 2, 3), "");
        assertTrue(new StatementAssembler(repo).assemble(NS + ".q", List.of(def)).fromLibrary());
    }
}
