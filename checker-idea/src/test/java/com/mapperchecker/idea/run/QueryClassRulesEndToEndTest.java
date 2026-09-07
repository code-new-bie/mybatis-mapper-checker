package com.mapperchecker.idea.run;

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** DAL-010（删条件后残留）与 DAL-011（死字段）：实体级聚合。 */
public class QueryClassRulesEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

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
        myFixture.addClass("""
                package com.example;
                public class BaseQuery {
                    private Integer pageNum;
                    public Integer getPageNum() { return pageNum; } public void setPageNum(Integer v) { pageNum = v; }
                }
                """);
        myFixture.addClass("""
                package com.example;
                public class OrderQuery extends BaseQuery {
                    private Long merchantId;   // list 用
                    private Long poiId;        // count 用
                    private String status;     // 有人 set，两条语句都不用 → DAL-010
                    private String remark;     // 无人 set，也不用 → DAL-011
                    private Address address;   // list 用 address.city
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
                    public String getRemark() { return remark; } public void setRemark(String v) { remark = v; }
                    public Address getAddress() { return address; } public void setAddress(Address v) { address = v; }
                }
                """);
        myFixture.addClass("package com.example; public class Address { private String city; public String getCity() { return city; } }");
    }

    private CheckRunner.Outcome runProject() {
        return new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
    }

    private static List<ContractIssue> ofRule(CheckRunner.Outcome o, RuleId r) {
        return o.result().issues().stream().filter(i -> i.ruleId() == r).toList();
    }

    private static Set<String> props(CheckRunner.Outcome o, RuleId r) {
        Set<String> s = new TreeSet<>();
        for (ContractIssue i : ofRule(o, r)) {
            s.add(i.parameterName());
        }
        return s;
    }

    private void addMapperAndCaller() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="list">SELECT * FROM t WHERE m = #{query.merchantId} <if test="query.address != null">AND c = #{query.address.city}</if></select>
                    <select id="count">SELECT count(*) FROM t WHERE p = #{poiId}</select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    java.util.List<Object> list(@Param("query") OrderQuery query);
                    long count(OrderQuery q);
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                import com.example.dao.OrderMapper;
                public class OrderService {
                    private OrderMapper mapper;
                    public void run(Long m, Long p, String s, Integer page) {
                        OrderQuery q = new OrderQuery();
                        q.setMerchantId(m);
                        q.setPoiId(p);
                        q.setStatus(s);
                        q.setPageNum(page);
                        mapper.list(q);
                        mapper.count(q);
                    }
                }
                """);
    }

    public void testDAL010与DAL011() {
        addMapperAndCaller();
        CheckRunner.Outcome o = runProject();

        // status：有人 set，list / count 都不引用 → DAL-010，高置信度，锚点在实体字段
        List<ContractIssue> residue = ofRule(o, RuleId.DAL_010);
        assertEquals(Set.of("status"), props(o, RuleId.DAL_010));
        ContractIssue r = residue.get(0);
        assertEquals(Confidence.HIGH, r.confidence());
        assertEquals("com.example.OrderQuery", r.statementId());
        assertTrue(r.message(), r.message().contains("OrderService.java:"));
        assertTrue(r.message(), r.message().contains("2 条 statement"));
        assertTrue(r.remark(), r.remark().contains("OrderMapper.list") && r.remark().contains("OrderMapper.count"));
        assertTrue(r.primaryLocation().filePath().endsWith("OrderQuery.java"));

        // remark：无人 set 也无引用 → DAL-011，低置信度
        assertEquals(Set.of("remark"), props(o, RuleId.DAL_011));
        assertEquals(Confidence.LOW, ofRule(o, RuleId.DAL_011).get(0).confidence());

        // merchantId / poiId / address 被引用，pageNum 是分页字段：都不报
        Set<String> all = new TreeSet<>(props(o, RuleId.DAL_010));
        all.addAll(props(o, RuleId.DAL_011));
        assertEquals(Set.of("remark", "status"), all);

        // 可导航到实体字段
        assertTrue(o.reported().stream().filter(x -> x.issue().ruleId() == RuleId.DAL_010)
                .allMatch(x -> x.javaElement() != null && x.javaElement().getContainingFile().getName().equals("OrderQuery.java")));
    }

    public void test单Bean无Param与Param前缀都能聚合() {
        // count(OrderQuery q) 无 @Param 用 #{poiId}，list 用 @Param("query") 的 query.merchantId —— 两种都要算进"被引用"
        addMapperAndCaller();
        CheckRunner.Outcome o = runProject();
        assertFalse(props(o, RuleId.DAL_010).contains("poiId"));
        assertFalse(props(o, RuleId.DAL_010).contains("merchantId"));
        assertFalse(props(o, RuleId.DAL_011).contains("poiId"));
        assertFalse(props(o, RuleId.DAL_011).contains("merchantId"));
    }

    public void test反射拷贝目标附备注() {
        addMapperAndCaller();
        myFixture.addClass("""
                package com.example.service;
                import com.example.*;
                public class Copier {
                    public OrderQuery from(Object web) {
                        OrderQuery q = new OrderQuery();
                        org.springframework.beans.BeanUtils.copyProperties(web, q);
                        return q;
                    }
                }
                """);
        CheckRunner.Outcome o = runProject();
        for (ContractIssue i : ofRule(o, RuleId.DAL_011)) {
            assertTrue(i.remark(), i.remark().contains("反射赋值的目标"));
        }
        for (ContractIssue i : ofRule(o, RuleId.DAL_010)) {
            assertTrue(i.remark(), i.remark().contains("反射赋值的目标"));
        }
    }

    public void test没有关联statement的实体不聚合() {
        myFixture.addClass("""
                package com.example;
                public class LonelyQuery { private String x; public String getX() { return x; } public void setX(String v) { x = v; } }
                """);
        myFixture.addFileToProject("mapper/OrderMapper.xml", "<mapper namespace=\"%s\"><select id=\"a\">1</select></mapper>".formatted(NS));
        myFixture.addClass("package com.example.dao; public interface OrderMapper { int a(); }");
        CheckRunner.Outcome o = runProject();
        assertTrue(ofRule(o, RuleId.DAL_010).isEmpty());
        assertTrue(ofRule(o, RuleId.DAL_011).isEmpty());
    }

    public void test单文件范围不跑聚合() {
        addMapperAndCaller();
        var mapperFile = myFixture.findClass("com.example.dao.OrderMapper").getContainingFile().getVirtualFile();
        CheckRunner.Outcome o = new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.file(mapperFile), null);
        assertTrue(ofRule(o, RuleId.DAL_010).isEmpty());
        assertTrue(ofRule(o, RuleId.DAL_011).isEmpty());
    }

    public void test关闭规则() {
        addMapperAndCaller();
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true,
                Set.of(RuleId.DAL_010, RuleId.DAL_011), java.util.Map.of());
        CheckRunner.Outcome o = new CheckRunner(getProject(), s).run(CheckScope.project(), null);
        assertTrue(ofRule(o, RuleId.DAL_010).isEmpty());
        assertTrue(ofRule(o, RuleId.DAL_011).isEmpty());
    }
}
