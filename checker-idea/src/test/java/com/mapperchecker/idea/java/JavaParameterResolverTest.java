package com.mapperchecker.idea.java;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.UnresolvedReason;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Map / Bean 数据流追踪与跨方法追踪。DAO 调用点统一为 orderMapper.query(<arg>)。 */
public class JavaParameterResolverTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MyBatisStubs.addAll(myFixture);
        myFixture.addClass("""
                package com.example;
                public class OrderQuery {
                    public void setMerchantId(Long v) {}
                    public void setPoiId(Long v) {}
                    public OrderQuery withA(Long v) { return this; }
                    public static Builder builder() { return new Builder(); }
                    public static class Builder { public Builder poiId(Long v) { return this; } public OrderQuery build() { return null; } }
                }
                """);
        myFixture.addClass("""
                package com.example;
                public class ChainQuery {
                    public ChainQuery setA(Long v) { return this; }
                    public ChainQuery setB(Long v) { return this; }
                }
                """);
        myFixture.addClass("package com.example.dao; public interface OrderMapper { java.util.List<Object> query(Object p); }");
        myFixture.addClass("package com.example; public class Consts { public static final String POI = \"poiId\"; }");
    }

    /** 把 body 放进 Dao.run 方法体，追踪其中 orderMapper.query(...) 的实参。 */
    private int counter;

    private TraceResult traceBody(String body, String extraMembers) {
        // 每次调用用独立包名，避免同名文件冲突；类名固定为 Dao 以便断言调用路径
        String pkg = "com.example.t" + (++counter);
        PsiFile file = myFixture.addFileToProject(pkg.replace('.', '/') + "/Dao.java", """
                package %s;
                import java.util.*;
                import com.example.*;
                import com.google.common.collect.*;
                import com.example.dao.OrderMapper;
                public class Dao {
                    private OrderMapper orderMapper;
                    private Map<String, Object> fieldMap = new HashMap<>();
                    %s
                    public void run(Long merchantId, Long poiId, String status, Map<String, Object> incoming, List<Long> ids) {
                %s
                    }
                }
                """.formatted(pkg, extraMembers, body));
        PsiMethodCallExpression daoCall = null;
        for (PsiMethodCallExpression c : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            if ("query".equals(c.getMethodExpression().getReferenceName())
                    && c.getMethodExpression().getQualifierExpression() != null
                    && "orderMapper".equals(c.getMethodExpression().getQualifierExpression().getText())) {
                daoCall = c;
            }
        }
        assertNotNull("未找到 orderMapper.query 调用", daoCall);
        PsiExpression arg = daoCall.getArgumentList().getExpressions()[0];
        return new JavaParameterResolver(getProject(), 3).trace(arg, daoCall);
    }

    private TraceResult traceBody(String body) {
        return traceBody(body, "");
    }

    private static Set<String> names(TraceResult r) {
        Set<String> s = new TreeSet<>();
        for (ParameterReference p : r.parameters()) {
            s.add(p.rootName());
        }
        return s;
    }

    private static void assertUnresolved(TraceResult r, UnresolvedReason reason) {
        assertEquals("status", TraceResult.Status.UNRESOLVED, r.status());
        assertEquals(reason, r.reason());
    }

    // ---------------------------------------------------------------- Map

    public void testMap_put基本() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put("merchantId", merchantId);
                params.put("poiId", poiId);
                orderMapper.query(params);
                """);
        assertTrue(r.isResolved());
        assertEquals(Set.of("merchantId", "poiId"), names(r));
        assertEquals(ParameterSourceType.MAP_PUT, r.parameters().get(0).sourceType());
        assertTrue(r.callPath().isEmpty());
        // 位置在 key 字面量上
        assertTrue(r.parameters().get(0).location().line() > 0);
    }

    public void testMap_put常量key() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new LinkedHashMap<>();
                params.put(Consts.POI, poiId);
                params.put("a" + "b", 1);
                orderMapper.query(params);
                """);
        assertEquals(Set.of("poiId", "ab"), names(r));
    }

    public void testMap_put动态key() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put(status, poiId);
                orderMapper.query(params);
                """);
        assertUnresolved(r, UnresolvedReason.MAP_KEY_DYNAMIC);
    }

    public void testMap_of与ImmutableMap() {
        assertEquals(Set.of("a", "b"), names(traceBody("orderMapper.query(Map.of(\"a\", 1, \"b\", 2));")));
        assertEquals(Set.of("x"), names(traceBody("orderMapper.query(ImmutableMap.of(\"x\", 1));")));
        assertEquals(Set.of("k1", "k2"), names(traceBody(
                "orderMapper.query(ImmutableMap.builder().put(\"k1\", 1).put(\"k2\", 2).build());")));
        TraceResult viaVar = traceBody("""
                Map<String, Object> p = Map.of("m", merchantId);
                orderMapper.query(p);
                """);
        assertEquals(Set.of("m"), names(viaVar));
        assertEquals(ParameterSourceType.MAP_OF, viaVar.parameters().get(0).sourceType());
    }

    public void testMap_ofEntries() {
        TraceResult r = traceBody("orderMapper.query(Map.ofEntries(Map.entry(\"a\", 1), Map.entry(\"b\", 2)));");
        assertEquals(Set.of("a", "b"), names(r));
    }

    public void test双花括号初始化() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<String, Object>() {{
                    put("a", 1);
                    put("b", 2);
                }};
                orderMapper.query(params);
                """);
        assertEquals(Set.of("a", "b"), names(r));
    }

    public void testMaps_newHashMap() {
        TraceResult r = traceBody("""
                Map<String, Object> params = Maps.newHashMap();
                params.put("a", 1);
                orderMapper.query(params);
                """);
        assertEquals(Set.of("a"), names(r));
    }

    public void testMap_putAll可追踪则合并() {
        TraceResult r = traceBody("""
                Map<String, Object> base = new HashMap<>();
                base.put("m", merchantId);
                Map<String, Object> params = new HashMap<>();
                params.putAll(base);
                params.put("s", status);
                orderMapper.query(params);
                """);
        assertEquals(Set.of("m", "s"), names(r));
    }

    public void testMap_putAll不可追踪与remove_clear() {
        assertUnresolved(traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.putAll(incoming);
                orderMapper.query(params);
                """), UnresolvedReason.MAP_MUTATED);
        assertUnresolved(traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put("a", 1);
                params.remove("a");
                orderMapper.query(params);
                """), UnresolvedReason.MAP_MUTATED);
        assertUnresolved(traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.clear();
                orderMapper.query(params);
                """), UnresolvedReason.MAP_MUTATED);
    }

    public void testMap传给其他方法视为逃逸() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put("a", 1);
                fill(params);
                orderMapper.query(params);
                """, "void fill(Map<String, Object> m) { m.put(\"z\", 1); }");
        assertUnresolved(r, UnresolvedReason.MAP_MUTATED);
    }

    public void test调用之后的put不计入() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put("a", 1);
                orderMapper.query(params);
                params.put("later", 2);
                """);
        assertEquals(Set.of("a"), names(r));
    }

    public void test分支put取并集() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                params.put("m", merchantId);
                if (poiId != null) {
                    params.put("p", poiId);
                } else {
                    params.put("q", 1);
                }
                orderMapper.query(params);
                """);
        assertEquals(Set.of("m", "p", "q"), names(r));
    }

    public void testLambda内put视为方法内() {
        TraceResult r = traceBody("""
                Map<String, Object> params = new HashMap<>();
                ids.forEach(id -> params.put("id", id));
                orderMapper.query(params);
                """);
        assertEquals(Set.of("id"), names(r));
    }

    public void test入参字段不可解析() {
        assertUnresolved(traceBody("orderMapper.query(incoming);"), UnresolvedReason.METHOD_PARAM);
        assertUnresolved(traceBody("orderMapper.query(fieldMap);"), UnresolvedReason.FIELD);
    }

    public void test单标量不可比较() {
        assertEquals(TraceResult.Status.NOT_COMPARABLE, traceBody("orderMapper.query(poiId);").status());
        assertEquals(TraceResult.Status.NOT_COMPARABLE, traceBody("orderMapper.query(status);").status());
        assertEquals(TraceResult.Status.NOT_COMPARABLE, traceBody("Long x = 1L; orderMapper.query(x);").status());
    }

    // ---------------------------------------------------------------- Bean

    public void testBean_setter() {
        TraceResult r = traceBody("""
                OrderQuery q = new OrderQuery();
                q.setMerchantId(merchantId);
                q.setPoiId(poiId);
                orderMapper.query(q);
                """);
        assertTrue(r.isResolved());
        assertEquals(Set.of("merchantId", "poiId"), names(r));
        assertEquals(ParameterSourceType.BEAN_SETTER, r.parameters().get(0).sourceType());
    }

    public void testBean链式setter() {
        TraceResult r = traceBody("""
                ChainQuery q = new ChainQuery();
                q.setA(1L).setB(2L);
                orderMapper.query(q);
                """);
        assertEquals(Set.of("a", "b"), names(r));
    }

    public void testBean无setter与builder() {
        assertUnresolved(traceBody("""
                OrderQuery q = new OrderQuery();
                orderMapper.query(q);
                """), UnresolvedReason.BEAN_NO_SETTER);
        assertUnresolved(traceBody("""
                OrderQuery q = OrderQuery.builder().poiId(poiId).build();
                orderMapper.query(q);
                """), UnresolvedReason.BEAN_BUILDER);
        assertUnresolved(traceBody("orderMapper.query(new OrderQuery());"), UnresolvedReason.BEAN_NO_SETTER);
    }

    // ---------------------------------------------------------------- 跨方法

    public void test跨方法基本() {
        TraceResult r = traceBody("""
                Map<String, Object> params = buildParams(merchantId, poiId);
                orderMapper.query(params);
                """, """
                private Map<String, Object> buildParams(Long m, Long p) {
                    Map<String, Object> x = new HashMap<>();
                    x.put("merchantId", m);
                    x.put("poiId", p);
                    return x;
                }
                """);
        assertTrue(r.isResolved());
        assertEquals(Set.of("merchantId", "poiId"), names(r));
        assertEquals(List.of("Dao.run()", "Dao.buildParams()"), r.callPath());
        // 位置在被调方法的 put 上
        assertTrue(r.parameters().get(0).location().line() > 0);
    }

    public void test跨方法直接作为实参() {
        TraceResult r = traceBody("orderMapper.query(build());", """
                private Map<String, Object> build() { Map<String, Object> x = new HashMap<>(); x.put("a", 1); return x; }
                """);
        assertEquals(Set.of("a"), names(r));
        assertEquals(List.of("Dao.run()", "Dao.build()"), r.callPath());
    }

    public void test跨方法多return并集与返回后补充() {
        TraceResult r = traceBody("""
                Map<String, Object> params = build(poiId);
                params.put("status", status);
                orderMapper.query(params);
                """, """
                private Map<String, Object> build(Long p) {
                    if (p == null) { Map<String, Object> a = new HashMap<>(); a.put("x", 1); return a; }
                    Map<String, Object> b = new HashMap<>(); b.put("y", 2); return b;
                }
                """);
        assertEquals(Set.of("x", "y", "status"), names(r));
        assertEquals(List.of("Dao.run()", "Dao.build()"), r.callPath());
    }

    public void test跨方法链式与深度() {
        String members = """
                private Map<String, Object> l1() { return l2(); }
                private Map<String, Object> l2() { return l3(); }
                private Map<String, Object> l3() { Map<String, Object> m = new HashMap<>(); m.put("deep", 1); return m; }
                private Map<String, Object> l0() { return l1(); }
                """;
        // run → l1 → l2 → l3 = 深度 3，可达
        assertEquals(Set.of("deep"), names(traceBody("orderMapper.query(l1());", members)));
        // run → l0 → l1 → l2 → l3 = 深度 4，超限
        assertUnresolved(traceBody("orderMapper.query(l0());", members), UnresolvedReason.DEPTH_EXCEEDED);
    }

    public void test跨方法循环() {
        TraceResult r = traceBody("orderMapper.query(a());", """
                private Map<String, Object> a() { return b(); }
                private Map<String, Object> b() { return a(); }
                """);
        assertTrue(r.reason() == UnresolvedReason.CYCLE || r.reason() == UnresolvedReason.DEPTH_EXCEEDED);
    }

    public void test跨方法其他类静态方法() {
        myFixture.addClass("""
                package com.example;
                import java.util.*;
                public class ParamUtil { public static Map<String, Object> scope(Long m) { Map<String, Object> x = new HashMap<>(); x.put("merchantId", m); return x; } }
                """);
        TraceResult r = traceBody("orderMapper.query(ParamUtil.scope(merchantId));");
        assertEquals(Set.of("merchantId"), names(r));
        assertEquals(List.of("Dao.run()", "ParamUtil.scope()"), r.callPath());
    }

    public void test跨方法接口多实现与库代码() {
        myFixture.addClass("package com.example; public interface ParamBuilder { java.util.Map<String, Object> build(); }");
        TraceResult r = traceBody("orderMapper.query(pb.build());", "private ParamBuilder pb;");
        assertUnresolved(r, UnresolvedReason.MULTI_IMPL);
        // 库方法：Collections.emptyMap() 不在项目源码
        assertUnresolved(traceBody("orderMapper.query(Collections.emptyMap());"), UnresolvedReason.LIBRARY_CODE);
    }

    public void test跨方法返回Bean() {
        TraceResult r = traceBody("orderMapper.query(q());", """
                private OrderQuery q() { OrderQuery o = new OrderQuery(); o.setPoiId(1L); return o; }
                """);
        assertEquals(Set.of("poiId"), names(r));
        assertEquals(ParameterSourceType.BEAN_SETTER, r.parameters().get(0).sourceType());
    }
}
