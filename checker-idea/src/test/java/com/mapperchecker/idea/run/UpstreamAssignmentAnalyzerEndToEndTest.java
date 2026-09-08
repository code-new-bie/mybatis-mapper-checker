package com.mapperchecker.idea.run;

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.contract.RuleOptions;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "上游有没有真的赋值"分析：DAL-001（声明的参数 / 实体属性）与 DAL-010 的置信度、备注调整。
 * 真机反馈的原始场景：{@code query.dataStatuses} 被声明却没在 SQL 里用，报告看不出这值是被
 * 上游真传了、还是压根没人给过——这批测试锁住两种情形的区分。
 */
public class UpstreamAssignmentAnalyzerEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

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
                public class OrderQuery {
                    private Long merchantId; private Long poiId;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                }
                """);
    }

    private CheckRunner.Outcome runProject() {
        return new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
    }

    private CheckRunner.Outcome runProjectWithout(boolean upstreamAnalysis) {
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, true,
                Set.of(), Map.of(), true, true, RuleOptions.defaults(), upstreamAnalysis);
        return new CheckRunner(getProject(), s).run(CheckScope.project(), null);
    }

    private static ContractIssue single(CheckRunner.Outcome o, RuleId rule, String parameterName) {
        List<ContractIssue> matched = o.result().issues().stream()
                .filter(i -> i.ruleId() == rule && i.parameterName().equals(parameterName)).toList();
        assertEquals(matched.toString(), 1, matched.size());
        return matched.get(0);
    }

    // ---------------------------------------------------------------- 声明的方法参数

    public void test声明参数_上游确实赋值_保持高置信度并说明静默丢弃() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper { int q(@Param("merchantId") Long merchantId, @Param("dataStatuses") List<Object> dataStatuses); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.dao.OrderMapper;
                import java.util.List;
                public class Svc {
                    private OrderMapper orderMapper;
                    public void run(Long merchantId, List<Object> statuses) {
                        orderMapper.q(merchantId, statuses);
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "dataStatuses");
        assertEquals(Confidence.HIGH, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("静默丢弃"));
    }

    public void test声明参数_上游全是null_置信度降级并提示死参数() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper { int q(@Param("merchantId") Long merchantId, @Param("dataStatuses") List<Object> dataStatuses); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.dao.OrderMapper;
                public class Svc {
                    private OrderMapper orderMapper;
                    public void run(Long merchantId) {
                        orderMapper.q(merchantId, null);
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "dataStatuses");
        assertEquals(Confidence.MEDIUM, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("死参数"));
    }

    public void test声明参数_找不到调用点_不下结论保持原状() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper { int q(@Param("merchantId") Long merchantId, @Param("dataStatuses") List<Object> dataStatuses); }
                """);
        // 没有任何调用点：可能是反射调用 / 尚未接入，静态分析看不到，不该下结论
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "dataStatuses");
        assertEquals(Confidence.HIGH, issue.confidence());
        assertFalse(issue.remark(), issue.remark().contains("静默丢弃") || issue.remark().contains("死参数"));
    }

    // ---------------------------------------------------------------- 实体属性

    public void test实体属性_上游确实赋值_置信度从低升到中() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                public interface OrderMapper { java.util.List<Object> q(com.example.OrderQuery query); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                import com.example.dao.OrderMapper;
                public class Svc {
                    private OrderMapper orderMapper;
                    public void run(Long poiId) {
                        OrderQuery query = new OrderQuery();
                        query.setPoiId(poiId);
                        orderMapper.q(query);
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "poiId");
        assertEquals(Confidence.MEDIUM, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("静默丢弃"));
    }

    public void test实体属性_上游只赋null_保持低置信度并提示死参数() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                public interface OrderMapper { java.util.List<Object> q(com.example.OrderQuery query); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                import com.example.dao.OrderMapper;
                public class Svc {
                    private OrderMapper orderMapper;
                    public void run() {
                        OrderQuery query = new OrderQuery();
                        query.setPoiId(null);
                        orderMapper.q(query);
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "poiId");
        assertEquals(Confidence.LOW, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("死参数"));
    }

    // ---------------------------------------------------------------- DAL-010

    public void testDAL010_上游只赋null_置信度降级() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{query.merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { java.util.List<Object> q(@Param("query") com.example.OrderQuery query); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                public class Builder {
                    public OrderQuery build() {
                        OrderQuery query = new OrderQuery();
                        query.setPoiId(null);
                        return query;
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_010, "poiId");
        assertEquals(Confidence.MEDIUM, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("死参数"));
    }

    public void testDAL010_上游赋真值_保持高置信度不加备注() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{query.merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { java.util.List<Object> q(@Param("query") com.example.OrderQuery query); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                public class Builder {
                    public OrderQuery build(Long poiId) {
                        OrderQuery query = new OrderQuery();
                        query.setPoiId(poiId);
                        return query;
                    }
                }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_010, "poiId");
        assertEquals(Confidence.HIGH, issue.confidence());
        assertFalse(issue.remark(), issue.remark().contains("死参数") || issue.remark().contains("静默丢弃"));
    }

    // ---------------------------------------------------------------- 开关

    public void test关闭上游赋值分析_DAL001与DAL010都不调整() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="q">SELECT * FROM t WHERE merchant_id = #{merchantId}</select>
                    <select id="q2">SELECT * FROM t WHERE merchant_id = #{query.merchantId}</select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper {
                    int q(@Param("merchantId") Long merchantId, @Param("dataStatuses") List<Object> dataStatuses);
                    List<Object> q2(@Param("query") com.example.OrderQuery query);
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                import com.example.dao.OrderMapper;
                public class Svc {
                    private OrderMapper orderMapper;
                    public void run(Long merchantId) {
                        orderMapper.q(merchantId, null);
                        OrderQuery query = new OrderQuery();
                        query.setPoiId(null);
                        orderMapper.q2(query);
                    }
                }
                """);
        CheckRunner.Outcome o = runProjectWithout(false);
        ContractIssue declared = single(o, RuleId.DAL_001, "dataStatuses");
        assertEquals(Confidence.HIGH, declared.confidence());
        assertFalse(declared.remark(), declared.remark().contains("死参数"));
        ContractIssue dal010 = single(o, RuleId.DAL_010, "poiId");
        assertEquals(Confidence.HIGH, dal010.confidence());
        assertFalse(dal010.remark(), dal010.remark().contains("死参数"));
    }
}
