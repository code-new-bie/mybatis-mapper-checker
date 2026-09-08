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

    /** 断言这条 DAL-001 已经被判定无害、排除出问题列表（而不是照常报告）。 */
    private static void assertExcluded(CheckRunner.Outcome o, String parameterName) {
        List<ContractIssue> matched = o.result().issues().stream()
                .filter(i -> i.ruleId() == RuleId.DAL_001 && i.parameterName().equals(parameterName)).toList();
        assertTrue("不该再出现在问题列表：" + matched, matched.isEmpty());
        assertEquals(1, o.result().statistics().autoExcludedIssues());
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

    public void test声明参数_上游全是null_判定无害不计入问题() {
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
        // 确认从未真正赋值（全是字面量 null），SQL 也没用——完全自洽，不算问题
        assertExcluded(runProject(), "dataStatuses");
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

    public void test实体属性_上游只赋null_判定无害不计入问题() {
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
        assertExcluded(runProject(), "poiId");
    }

    public void test反射拷贝目标查不到直接赋值证据时给出更具体的提示() {
        // 真机反馈：查不出证据时报告只留一句笼统的"该实体可能被多个 statement 共用，请人工确认"，
        // 用户指出赋值也可能是反射拷贝来的（如 BeanUtils.copyProperties）——这条锁住这个提示
        myFixture.addClass("package org.springframework.beans; public class BeanUtils { public static void copyProperties(Object source, Object target) {} }");
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
                    public void run(Object source) {
                        OrderQuery query = new OrderQuery();
                        org.springframework.beans.BeanUtils.copyProperties(source, query); // poiId 可能是这么来的
                        orderMapper.q(query);
                    }
                }
                """);
        // poiId 没在任何地方被直接 setPoiId 过，也没在 SQL 里用到——按调用点追踪时因为 query 被
        // 传给了 copyProperties 而追不出来（不装作能看穿反射），项目级证据表也没有记录，
        // 但 query 的类型已知是反射拷贝的目标，值不变（仍是低置信度），备注给出更具体的解释
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "poiId");
        assertEquals(Confidence.LOW, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("反射拷贝"));
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

    // ---------------------------------------------------------------- Query 类被多方法共用（按调用点精确追踪）

    /**
     * 场景：SharedQuery 被 methodA、methodB 共用。methodA 的 SQL 不用 remark，methodA 的调用方
     * 也从来不碰 remark——但 methodB 的调用方会给 remark 赋真值。项目级证据表（setterUsages）
     * 不区分调用链，会把 methodB 的真赋值当成 remark 的"全局证据"误伤 methodA；
     * {@code analyzePropertyPerCallSite} 按"这个方法自己的调用点"精确追踪（局部变量
     * new Bean() + 顺序 setX(...) 这种最常见写法），优先于项目级证据表，能正确识别出
     * methodA 自己的调用链里 remark 从未被真正赋值，判定为无害、不计入问题。
     * <p>
     * 命名注意：这个类的测试方法名必须以 {@code test} 开头（JUnit3 风格发现规则），之前误写成
     * {@code diag_xxx} 导致这个方法从未被真正执行过、看似"通过"其实根本没跑。
     */
    public void test共享Query_按调用点精确追踪_另一方法的赋值不再误伤本方法() {
        myFixture.addClass("""
                package com.example;
                public class SharedQuery {
                    private Long merchantId; private Long remark;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getRemark() { return remark; } public void setRemark(Long v) { remark = v; }
                }
                """);
        myFixture.addFileToProject("mapper/SharedMapper.xml", """
                <mapper namespace="com.example.dao.SharedMapper">
                    <select id="methodA">SELECT * FROM t WHERE merchant_id = #{merchantId}</select>
                    <select id="methodB">SELECT * FROM t WHERE merchant_id = #{merchantId} AND remark = #{remark}</select>
                </mapper>
                """);
        myFixture.addClass("""
                package com.example.dao;
                import com.example.SharedQuery;
                public interface SharedMapper {
                    java.util.List<Object> methodA(SharedQuery q);
                    java.util.List<Object> methodB(SharedQuery q);
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.SharedQuery;
                import com.example.dao.SharedMapper;
                public class Svc {
                    private SharedMapper mapper;
                    public void callA(Long merchantId) {
                        SharedQuery q = new SharedQuery();
                        q.setMerchantId(merchantId);
                        mapper.methodA(q); // 全程不碰 remark，methodA 的 SQL 也不用它——本该完全无害
                    }
                    public void callB(Long merchantId, Long remark) {
                        SharedQuery q = new SharedQuery();
                        q.setMerchantId(merchantId);
                        q.setRemark(remark); // methodB 真的用到了 remark，这条赋值合理且必要
                        mapper.methodB(q);
                    }
                }
                """);
        // methodA 自己的调用链里 remark 从未被真正赋值，且 methodA 的 SQL 也不用它——按调用点
        // 精确追踪后应判定为无害，不再被 methodB 的赋值误伤，直接从问题列表里排除
        assertExcluded(runProject(), "remark");
    }

    /** 反过来：methodB 的调用方真的给 remark 赋了值，且 methodB 的 SQL 确实用了它——不该报 DAL-001。 */
    public void test共享Query_按调用点精确追踪_真正用到的方法不受影响() {
        myFixture.addClass("""
                package com.example;
                public class SharedQuery2 {
                    private Long merchantId; private Long remark;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getRemark() { return remark; } public void setRemark(Long v) { remark = v; }
                }
                """);
        myFixture.addFileToProject("mapper/SharedMapper2.xml", """
                <mapper namespace="com.example.dao.SharedMapper2">
                    <select id="methodA">SELECT * FROM t WHERE merchant_id = #{merchantId}</select>
                    <select id="methodB">SELECT * FROM t WHERE merchant_id = #{merchantId} AND remark = #{remark}</select>
                </mapper>
                """);
        myFixture.addClass("""
                package com.example.dao;
                import com.example.SharedQuery2;
                public interface SharedMapper2 {
                    java.util.List<Object> methodA(SharedQuery2 q);
                    java.util.List<Object> methodB(SharedQuery2 q);
                }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.SharedQuery2;
                import com.example.dao.SharedMapper2;
                public class Svc2 {
                    private SharedMapper2 mapper;
                    public void callA(Long merchantId) {
                        SharedQuery2 q = new SharedQuery2();
                        q.setMerchantId(merchantId);
                        mapper.methodA(q);
                    }
                    public void callB(Long merchantId, Long remark) {
                        SharedQuery2 q = new SharedQuery2();
                        q.setMerchantId(merchantId);
                        q.setRemark(remark);
                        mapper.methodB(q);
                    }
                }
                """);
        CheckRunner.Outcome o = runProject();
        // methodB 的 SQL 用了 remark，本来就不该产生 DAL-001；methodA 那条按上一条测试的逻辑应被排除
        assertTrue(o.result().issues().stream().noneMatch(i -> i.ruleId() == RuleId.DAL_001
                && i.statementId().endsWith("SharedMapper2.methodB") && i.parameterName().equals("remark")));
        assertExcluded(o, "remark");
    }

    // ---------------------------------------------------------------- 沿调用链往上追（分层代码）

    private void 分层场景(String controllerBody) {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                public interface OrderMapper { java.util.List<Object> q(com.example.OrderQuery query); }
                """);
        // DAO 的直接调用者拿到的是它自己的形参——早先的实现在这里就判 UNKNOWN 了
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                import com.example.dao.OrderMapper;
                public class DalService {
                    private OrderMapper orderMapper;
                    public java.util.List<Object> query(OrderQuery query) { return orderMapper.q(query); }
                }
                """);
        myFixture.addClass("""
                package com.example.web;
                import com.example.OrderQuery;
                import com.example.service.DalService;
                public class Controller {
                    private DalService dal;
                    public void handle(Long merchantId, Long poiId) {
                %s
                    }
                }
                """.formatted(controllerBody));
    }

    /**
     * 用户反馈的核心场景：Spring 分层里 Query 在 Service / Controller 层构造，一层层当参数传到 DAO。
     * 早先只看 DAO 的直接调用点、只认"局部变量 new Bean() + setX"，直接调用者传的是自己的形参就
     * 一照面判 UNKNOWN——最典型的代码恰恰追不动，报告里留下一片笼统的"请人工确认"。
     * 现在会顺着形参位置继续往上追到真正的构造点。
     */
    public void test分层调用链_实体在上两层构造且全程没赋值_判定无害不计入问题() {
        分层场景("""
                        OrderQuery query = new OrderQuery();
                        query.setMerchantId(merchantId);
                        dal.query(query); // 全程没人碰 poiId，SQL 也不用它
                """);
        assertExcluded(runProject(), "poiId");
    }

    /** 同样的两层链条，构造点确实给 poiId 赋了真值：值被静默丢弃，照常报且升置信度。 */
    public void test分层调用链_实体在上两层构造且赋了真值_置信度上调() {
        分层场景("""
                        OrderQuery query = new OrderQuery();
                        query.setMerchantId(merchantId);
                        query.setPoiId(poiId);
                        dal.query(query);
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "poiId");
        assertEquals(Confidence.MEDIUM, issue.confidence());
        assertTrue(issue.remark(), issue.remark().contains("静默丢弃"));
    }

    /**
     * 中间层经接口分派：Controller 拿到的是接口引用，调用点 resolve() 落在接口方法上而不是 Impl 上。
     * 只搜 Impl 方法本身会找不到任何调用者、链条断在这里——调用链功能上线时真机踩过同一个坑
     * （见 TASKS.md），这里锁住上游分析复用 {@code CallChainFinder.searchTargets} 之后不会重蹈覆辙。
     */
    public void test分层调用链_中间层经接口调用也能追到构造点() {
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
                public interface DalService { java.util.List<Object> query(OrderQuery query); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import com.example.OrderQuery;
                import com.example.dao.OrderMapper;
                public class DalServiceImpl implements DalService {
                    private OrderMapper orderMapper;
                    @Override public java.util.List<Object> query(OrderQuery query) { return orderMapper.q(query); }
                }
                """);
        myFixture.addClass("""
                package com.example.web;
                import com.example.OrderQuery;
                import com.example.service.DalService;
                public class Controller {
                    private DalService dal; // 接口类型引用，调用点不落在 DalServiceImpl.query 上
                    public void handle(Long merchantId) {
                        OrderQuery query = new OrderQuery();
                        query.setMerchantId(merchantId);
                        dal.query(query);
                    }
                }
                """);
        assertExcluded(runProject(), "poiId");
    }

    /**
     * 追不出结论的那批：对象是别处 build 出来的，看不到构造过程。既不能判"确认没赋值"（会静默
     * 掉真问题），也不该混进已确认的问题里——单独标记成"证据不足"，报告与导出分组展示。
     */
    public void test实体属性_对象由Builder返回追不出构造点_标记为证据不足单独分组() {
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
                    private OrderQuery build() { return new OrderQuery(); }
                    public void run() {
                        OrderQuery query = build(); // 不是 new 出来的，构造过程看不见
                        orderMapper.q(query);
                    }
                }
                """);
        CheckRunner.Outcome o = runProject();
        ContractIssue issue = single(o, RuleId.DAL_001, "poiId");
        assertTrue("追不出结论的应进证据不足分组", issue.isEvidenceInsufficient());
        assertEquals("置信度不该因为追不出来而变动", Confidence.LOW, issue.confidence());
        assertEquals("不确定的不能算进已排除", 0, o.result().statistics().autoExcludedIssues());
        String md = com.mapperchecker.idea.report.ReportExporter.toMarkdown(o.result());
        assertTrue(md, md.contains("## 证据不足，待人工确认"));
        assertTrue(md, md.contains("- 其中证据不足待人工确认：1"));
    }

    /**
     * 声明参数（{@code @Param} 标量 / 集合）追不出调用点时<b>不算</b>"证据不足"：它是代码自身就能
     * 证实的契约不符（签名声明了、SQL 没用），成立与否不依赖调用链证据，混进待确认组是误伤。
     */
    public void test声明参数找不到调用点不算证据不足() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT * FROM t WHERE merchant_id = #{merchantId}</select></mapper>"
                        .formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper { int q(@Param("merchantId") Long merchantId, @Param("dataStatuses") List<Object> dataStatuses); }
                """);
        ContractIssue issue = single(runProject(), RuleId.DAL_001, "dataStatuses");
        assertFalse("声明参数不该被打上证据不足的标签", issue.isEvidenceInsufficient());
        assertEquals(Confidence.HIGH, issue.confidence());
    }
}
