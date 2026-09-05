package com.mapperchecker.idea.run;

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 真机反馈的三个问题：分页参数误报、实体参数属性级检查、报告详情。 */
public class BeanPropertyEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String NS = "com.example.dao.OrderMapper";

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        com.mapperchecker.idea.java.MyBatisStubs.addAll(myFixture);
        myFixture.addClass("""
                package com.example;
                public class BaseQuery {
                    private Integer pageNum;
                    private Integer pageSize;
                    private String orderBy;
                    public Integer getPageNum() { return pageNum; } public void setPageNum(Integer v) { pageNum = v; }
                    public Integer getPageSize() { return pageSize; } public void setPageSize(Integer v) { pageSize = v; }
                    public String getOrderBy() { return orderBy; } public void setOrderBy(String v) { orderBy = v; }
                }
                """);
        myFixture.addClass("""
                package com.example;
                import java.io.Serializable;
                public class OrderQuery extends BaseQuery implements Serializable {
                    private static final long serialVersionUID = 1L;
                    private Long merchantId;
                    private Long poiId;
                    private String status;
                    private transient String cacheKey;
                    private Address address;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
                    public Address getAddress() { return address; } public void setAddress(Address v) { address = v; }
                }
                """);
        myFixture.addClass("package com.example; public class Address { private String city; public String getCity() { return city; } }");
    }

    private CheckRunner.Outcome run(CheckSettings s) {
        return new CheckRunner(getProject(), s).run(CheckScope.project(), null);
    }

    private static Set<String> params(CheckRunner.Outcome o, RuleId r) {
        Set<String> out = new TreeSet<>();
        for (ContractIssue i : o.result().issues()) {
            if (i.ruleId() == r) {
                out.add(i.parameterName());
            }
        }
        return out;
    }

    public void test单Bean属性级检查_分页属性默认忽略() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="query">
                        SELECT * FROM orders WHERE merchant_id = #{merchantId}
                        <if test="address != null and address.city != null">AND city = #{address.city}</if>
                    </select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                public interface OrderMapper { java.util.List<Object> query(OrderQuery q); }
                """);
        CheckRunner.Outcome o = run(CheckSettings.defaults());
        // pageNum / pageSize / orderBy 被内置分页名单忽略；address 被 address.city 覆盖；cacheKey transient 不算
        assertEquals(Set.of("poiId", "status"), params(o, RuleId.MMC001));
        for (ContractIssue i : o.result().issues()) {
            assertEquals(Confidence.LOW, i.confidence());
            assertTrue(i.message(), i.message().startsWith("实体 'OrderQuery' 的属性 '"));
            assertTrue(i.primaryLocation().filePath().endsWith("OrderQuery.java"));
        }
        // 锚点在实体字段上，可导航
        for (ReportedIssue ri : o.reported()) {
            assertNotNull(ri.javaElement());
            assertTrue(ri.javaElement().getContainingFile().getName().equals("OrderQuery.java"));
        }
    }

    public void test关闭分页忽略后分页属性照常报() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"query\">SELECT #{merchantId}, #{poiId}, #{status}, #{address}</select></mapper>".formatted(NS));
        myFixture.addClass("package com.example.dao; import com.example.OrderQuery; public interface OrderMapper { int query(OrderQuery q); }");
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of(), false, true);
        assertEquals(Set.of("orderBy", "pageNum", "pageSize"), params(run(s), RuleId.MMC001));
    }

    public void test关闭实体属性检查后单Bean不报() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"query\">SELECT 1</select></mapper>".formatted(NS));
        myFixture.addClass("package com.example.dao; import com.example.OrderQuery; public interface OrderMapper { int query(OrderQuery q); }");
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true, Set.of(), Map.of(), true, false);
        assertTrue(run(s).result().issues().isEmpty());
    }

    public void testParam_Bean按前缀展开() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="query">
                        SELECT * FROM orders WHERE merchant_id = #{q.merchantId} AND status = #{q.status} AND x = #{extra}
                        <if test="q.address != null">AND 1 = 1</if>
                    </select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { java.util.List<Object> query(@Param("q") OrderQuery q, @Param("extra") String extra); }
                """);
        CheckRunner.Outcome o = run(CheckSettings.defaults());
        assertEquals(Set.of("q.poiId"), params(o, RuleId.MMC001));
    }

    public void testParam_Bean整体未用只报根() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"query\">SELECT #{extra}</select></mapper>".formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { int query(@Param("q") OrderQuery q, @Param("extra") String extra); }
                """);
        CheckRunner.Outcome o = run(CheckSettings.defaults());
        assertEquals(Set.of("q"), params(o, RuleId.MMC001));
        assertEquals(Confidence.HIGH, o.result().issues().get(0).confidence());
    }

    public void test库类型与Map不展开() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"a\">SELECT 1</select><select id=\"b\">SELECT 1</select></mapper>".formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                public interface OrderMapper { int a(Object o); int b(java.util.Date d); }
                """);
        assertTrue(run(CheckSettings.defaults()).result().issues().isEmpty());
    }

    public void test调用点Map的分页key忽略() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT #{merchantId}</select></mapper>".formatted(NS));
        myFixture.addClass("package com.example.dao; public interface OrderMapper { java.util.List<Object> q(java.util.Map<String, Object> p); }");
        myFixture.addClass("""
                package com.example.service;
                import java.util.*;
                import com.example.dao.OrderMapper;
                public class S {
                    private OrderMapper m;
                    public void run(Long merchantId, int pageNo, int pageSize) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("merchantId", merchantId);
                        p.put("pageNo", pageNo);
                        p.put("pageSize", pageSize);
                        p.put("offset", (pageNo - 1) * pageSize);
                        p.put("poiId", 1L);
                        m.q(p);
                    }
                }
                """);
        assertEquals(Set.of("poiId"), params(run(CheckSettings.defaults()), RuleId.MMC001));
    }
}
