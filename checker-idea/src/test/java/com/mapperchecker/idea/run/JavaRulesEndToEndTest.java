package com.mapperchecker.idea.run;

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.UnresolvedReason;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 团队规范第一批规则：DAL-022 / 020 / 004 / 030 / 021，以及"部分解析登记为覆盖缺口"、"反射拷贝原因"。
 */
public class JavaRulesEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String NS = "com.example.dao.OrderMapper";

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        com.mapperchecker.idea.java.MyBatisStubs.addAll(myFixture);
        myFixture.addClass("package org.springframework.beans; public class BeanUtils { public static void copyProperties(Object source, Object target) {} }");
        myFixture.addClass("package com.hjly.commontool.util.core; public class BeanUtils { public static void copyProperties(Object dest, Object orig) {} }");
        myFixture.addClass("""
                package com.example;
                public class OrderQuery {
                    private Long merchantId; private Long poiId; private java.util.List<String> statuses; private Long stockDateDetailId;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                    public java.util.List<String> getStatuses() { return statuses; } public void setStatuses(java.util.List<String> v) { statuses = v; }
                    public Long getStockDateDetailId() { return stockDateDetailId; } public void setStockDateDetailId(Long v) { stockDateDetailId = v; }
                }
                """);
        myFixture.addClass("""
                package com.example.web;
                public class OrderWebQuery {
                    private Long merchantId; private Long poiId; private String status; private Long stockDataDetailId;
                    public Long getMerchantId() { return merchantId; }
                    public Long getPoiId() { return poiId; }
                    public String getStatus() { return status; }
                    public Long getStockDataDetailId() { return stockDataDetailId; }
                }
                """);
    }

    private CheckRunner.Outcome runProject() {
        return new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
    }

    private static List<ContractIssue> ofRule(CheckRunner.Outcome o, RuleId r) {
        return o.result().issues().stream().filter(i -> i.ruleId() == r).toList();
    }

    private static Set<String> params(CheckRunner.Outcome o, RuleId r) {
        Set<String> s = new TreeSet<>();
        for (ContractIssue i : ofRule(o, r)) {
            s.add(i.parameterName());
        }
        return s;
    }

    public void testDAL022_Query参数Param命名() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"a\">1</select><select id=\"b\">1</select><select id=\"c\">1</select></mapper>".formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    int a(@Param("query") OrderQuery q);
                    int b(@Param("orderQuery") OrderQuery q, @Param("limit") Long limit);
                    int c(OrderQuery q);
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_022);
        assertEquals(2, issues.size());
        for (ContractIssue i : issues) {
            assertEquals("q", i.parameterName());
            assertTrue(i.primaryLocation().filePath().endsWith("OrderMapper.java"));
        }
        assertTrue(issues.stream().anyMatch(i -> i.statementId().endsWith(".b") && i.message().contains("@Param(\"orderQuery\")")));
        assertTrue(issues.stream().anyMatch(i -> i.statementId().endsWith(".c") && i.message().contains("无 @Param")));
    }

    public void testDAL020_transform内反射拷贝_与数据流覆盖缺口原因() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT #{merchantId}</select></mapper>".formatted(NS));
        myFixture.addClass("package com.example.dao; import com.example.OrderQuery; public interface OrderMapper { int q(OrderQuery q); }");
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                import com.example.web.OrderWebQuery;
                import org.springframework.beans.BeanUtils;
                public class Converter {
                    public OrderQuery transform(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        BeanUtils.copyProperties(web, q);
                        return q;
                    }
                    public void notTransform(OrderWebQuery web, OrderQuery q) {
                        BeanUtils.copyProperties(web, q);
                    }
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                import org.apache.ibatis.session.SqlSession;
                import org.springframework.beans.BeanUtils;
                public class Dao {
                    private SqlSession s;
                    public Object run(Object web) {
                        OrderQuery q = new OrderQuery();
                        BeanUtils.copyProperties(web, q);
                        return s.selectOne("com.example.dao.OrderMapper.q", q);
                    }
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_020);
        // Converter.transform 报，notTransform（void）不报，Dao.run（返回 Object）不报
        assertEquals(1, issues.size());
        assertEquals("com.example.service.Converter.transform", issues.get(0).statementId());
        assertTrue(issues.get(0).message().contains("BeanUtils.copyProperties()"));
        // Dao.run 里 q 被传给 copyProperties：数据流标 REFLECTIVE_COPY 覆盖缺口
        assertTrue(o.result().unresolved().stream().anyMatch(u -> u.reason() == UnresolvedReason.REFLECTIVE_COPY));
    }

    public void testDAL004_copyProperties参数顺序() {
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                import com.example.web.OrderWebQuery;
                public class Bad {
                    public OrderQuery spring(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        org.springframework.beans.BeanUtils.copyProperties(q, web);   // 源在前，却传了空对象 → 报
                        return q;
                    }
                    public OrderQuery hjly(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        com.hjly.commontool.util.core.BeanUtils.copyProperties(web, q); // hjly 源在后，传了空对象 → 报
                        return q;
                    }
                    public OrderQuery ok(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        org.springframework.beans.BeanUtils.copyProperties(web, q);   // 正确
                        com.hjly.commontool.util.core.BeanUtils.copyProperties(q, web); // 正确
                        return q;
                    }
                    public OrderQuery filled(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        q.setPoiId(1L);
                        org.springframework.beans.BeanUtils.copyProperties(q, web);   // q 已填过值，不算空对象
                        return q;
                    }
                    public void inline(OrderWebQuery web) {
                        org.springframework.beans.BeanUtils.copyProperties(new OrderQuery(), web); // 直接 new → 报
                    }
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_004);
        Set<String> methods = new TreeSet<>();
        for (ContractIssue i : issues) {
            methods.add(i.statementId());
        }
        assertEquals(Set.of("com.example.service.Bad.spring", "com.example.service.Bad.hjly", "com.example.service.Bad.inline"), methods);
        assertTrue(issues.stream().allMatch(i -> i.message().contains("参数顺序疑似写反")));
    }

    public void testDAL030_跨层改名() {
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                import com.example.web.OrderWebQuery;
                public class Mapping {
                    public OrderQuery convert(OrderWebQuery web) {
                        OrderQuery q = new OrderQuery();
                        q.setMerchantId(web.getMerchantId());              // 相同，不报
                        q.setStockDateDetailId(web.getStockDataDetailId()); // Date / Data，手滑
                        q.setStatuses(java.util.List.of(web.getStatus()));  // 参数不是直接 getter，不报
                        q.setPoiId(web.getMerchantId());                   // 完全不同，不报
                        return q;
                    }
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                public class Plural {
                    public void x(OrderQuery a, OrderQuery b) {
                        a.setStatuses(b.getStatuses());
                    }
                    public void y(OrderQuery a, com.example.web.OrderWebQuery b) {
                        java.util.List<String> l = null;
                        a.setStatuses(l);
                    }
                }
                """);
        // 单复数：setDataStatuses(getStatus()) 这类要有 setter 名与 getter 名互为变体
        myFixture.addClass("""
                package com.example.service;
                public class PluralCase {
                    static class Web { public String getStatus() { return null; } }
                    static class Q { public void setStatuses(java.util.List<String> v) {} }
                    public void z(Web w, Q q) { q.setStatuses(java.util.List.of(w.getStatus())); }
                    public void direct(Web w, com.example.OrderQuery q) { q.setStatuses((java.util.List) (Object) w.getStatus()); }
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_030);
        Set<String> keys = params(o, RuleId.DAL_030);
        assertTrue(keys.toString(), keys.contains("stockDateDetailId<-stockDataDetailId"));
        assertTrue(keys.toString(), keys.contains("statuses<-status"));
        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("疑似手滑")));
        assertTrue(issues.stream().anyMatch(i -> i.message().contains("单复数")));
    }

    public void testDAL021_同名Query类() {
        myFixture.addClass("package com.a; public class StockDetailWebQuery { }");
        myFixture.addClass("package com.b; public class StockDetailWebQuery { }");
        myFixture.addClass("package com.c; public class UniqueQuery { }");
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_021);
        assertEquals(2, issues.size());
        for (ContractIssue i : issues) {
            assertTrue(i.message().contains("StockDetailWebQuery"));
            assertTrue(i.message().contains("com.a.StockDetailWebQuery") && i.message().contains("com.b.StockDetailWebQuery"));
        }
        assertTrue(issues.stream().noneMatch(i -> i.statementId().contains("UniqueQuery")));
    }

    public void test部分解析的statement登记为覆盖缺口() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <sql id="a">#{x} <include refid="b"/></sql>
                    <sql id="b">#{y} <include refid="a"/></sql>
                    <select id="q"><include refid="a"/></select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { int q(@Param("x") Long x, @Param("z") Long z); }
                """);
        CheckRunner.Outcome o = runProject();
        // 不报 DAL-001（拿不准），但必须在无法解析里可见
        assertTrue(ofRule(o, RuleId.DAL_001).isEmpty());
        assertTrue(o.result().unresolved().stream().anyMatch(
                u -> u.reason() == UnresolvedReason.PARTIAL_STATEMENT && u.statementId().equals(NS + ".q")));
    }

    public void test单文件范围也跑纯Java规则() {
        var file = myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                public class OneFile {
                    public OrderQuery t(OrderQuery src) {
                        OrderQuery q = new OrderQuery();
                        org.springframework.beans.BeanUtils.copyProperties(q, src);
                        return q;
                    }
                }
                """).getContainingFile();
        CheckRunner.Outcome o = new CheckRunner(getProject(), CheckSettings.defaults())
                .run(CheckScope.file(file.getVirtualFile()), null);
        assertEquals(1, ofRule(o, RuleId.DAL_004).size());
        assertEquals(1, ofRule(o, RuleId.DAL_020).size());
        // 单文件不跑全局规则
        assertTrue(ofRule(o, RuleId.DAL_021).isEmpty());
    }
}
