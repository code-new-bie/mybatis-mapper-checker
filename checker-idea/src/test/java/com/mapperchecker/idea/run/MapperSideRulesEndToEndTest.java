package com.mapperchecker.idea.run;

import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** DAL-002（Mapper 引用实体不存在的属性）与 DAL-003（模板语句 if 与绑定不一致）。 */
public class MapperSideRulesEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

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
                    private Long merchantId; private Long poiId; private Address address; private java.util.List<Long> ids;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                    public Address getAddress() { return address; } public void setAddress(Address v) { address = v; }
                    public java.util.List<Long> getIds() { return ids; } public void setIds(java.util.List<Long> v) { ids = v; }
                }
                """);
        myFixture.addClass("package com.example; public class Address { private String city; public String getCity() { return city; } public void setCity(String v) { city = v; } }");
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

    public void testDAL002_Param前缀与单Bean() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="byQuery">
                        SELECT * FROM t WHERE m = #{query.merchantId}
                        <if test="query.poild != null">AND p = #{query.poild}</if>
                        <if test="query.address.cty != null">AND c = #{query.address.city}</if>
                        <foreach collection="query.ids" item="it">#{it}</foreach>
                    </select>
                    <select id="single">SELECT * FROM t WHERE m = #{merchantId} AND s = #{status}</select>
                    <select id="mapParam">SELECT * FROM t WHERE x = #{anything}</select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    java.util.List<Object> byQuery(@Param("query") OrderQuery query);
                    java.util.List<Object> single(OrderQuery q);
                    java.util.List<Object> mapParam(java.util.Map<String, Object> m);
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_002);
        // query.poild（近似 poiId）、query.address.cty（近似 city）、single 里的 status；Map 参数不查
        assertEquals(Set.of("query.poild", "query.address.cty", "status"), params(o, RuleId.DAL_002));
        ContractIssue poild = issues.stream().filter(i -> i.parameterName().equals("query.poild")).findFirst().orElseThrow();
        assertTrue(poild.message(), poild.message().contains("不存在的属性 'query.poild'"));
        assertEquals("实体里有近似属性 'poiId'，多为改名漏改。", poild.remark());
        assertTrue(poild.primaryLocation().filePath().endsWith("OrderMapper.xml"));
        ContractIssue cty = issues.stream().filter(i -> i.parameterName().equals("query.address.cty")).findFirst().orElseThrow();
        assertEquals("实体里有近似属性 'city'，多为改名漏改。", cty.remark());
        ContractIssue status = issues.stream().filter(i -> i.parameterName().equals("status")).findFirst().orElseThrow();
        assertEquals("", status.remark());
        // 可导航到 XML
        assertTrue(o.reported().stream().filter(x -> x.issue().ruleId() == RuleId.DAL_002)
                .allMatch(x -> x.javaElement() != null && x.javaElement().getContainingFile().getName().equals("OrderMapper.xml")));
    }

    public void testDAL002_多参数无Param不查_部分解析不查() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="two">SELECT #{param1.nope}, #{param2}</select>
                    <sql id="a"><include refid="b"/></sql><sql id="b"><include refid="a"/></sql>
                    <select id="partial">SELECT #{nope} <include refid="a"/></select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                public interface OrderMapper {
                    int two(OrderQuery q, Long x);
                    int partial(OrderQuery q);
                }
                """);
        assertTrue(ofRule(runProject(), RuleId.DAL_002).isEmpty());
    }

    public void testDAL003_模板语句() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="listByQuery">
                        SELECT * FROM t
                        <where>
                            <if test="query.merchantId != null">AND merchant_id = #{query.merchantId}</if>
                            <if test="query.poiId != null">AND coupon_batch_id = #{query.merchantId}</if>
                            <if test="query.ids != null and query.ids.size() > 0">AND id IN <foreach collection="query.ids" item="it">#{it}</foreach></if>
                            <if test="query.address != null">AND deleted = 0</if>
                        </where>
                    </select>
                    <select id="custom">
                        SELECT * FROM t <if test="query.poiId != null">AND x = #{query.merchantId}</if>
                    </select>
                </mapper>
                """.formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import com.example.OrderQuery;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    java.util.List<Object> listByQuery(@Param("query") OrderQuery query);
                    java.util.List<Object> custom(@Param("query") OrderQuery query);
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_003);
        // listByQuery 里只有第二个 if 不一致；foreach 那个 it 是局部名、块内无其他绑定但 collection 与 test 一致；
        // "AND deleted = 0" 没有绑定不查；custom 不是模板 id 不查
        assertEquals(1, issues.size());
        ContractIssue i = issues.get(0);
        assertTrue(i.message(), i.message().contains("判断的是 'query.poiId'") && i.message().contains("绑定的却是 'query.merchantId'"));
        assertEquals(NS + ".listByQuery", i.statementId());
        assertTrue(i.primaryLocation().filePath().endsWith("OrderMapper.xml"));
        assertTrue(i.primaryLocation().line() > 0);
        var anchor = o.reported().stream().filter(x -> x.issue().ruleId() == RuleId.DAL_003).findFirst().orElseThrow().javaElement();
        assertTrue(anchor instanceof XmlTag && ((XmlTag) anchor).getName().equals("if"));
    }

    public void testDAL003_iBatis与字符串调用() {
        myFixture.addFileToProject("sqlmap/Order.xml", """
                <sqlMap namespace="Order">
                    <update id="update">
                        UPDATE t SET
                        <isNotNull property="status" prepend=",">status = #status#</isNotNull>
                        <isNotNull property="couponBatchId" prepend=",">coupon_batch_id = #gmtCreate#</isNotNull>
                        WHERE id = #id#
                    </update>
                </sqlMap>
                """);
        myFixture.addClass("""
                package com.example.legacy;
                import com.ibatis.sqlmap.client.SqlMapClient;
                public class Dao {
                    private SqlMapClient c;
                    public void run(Object p) { c.update("Order.update", p); c.update("Order.update", p); }
                }
                """);
        CheckRunner.Outcome o = runProject();
        List<ContractIssue> issues = ofRule(o, RuleId.DAL_003);
        // 同一语句两次调用只报一次
        assertEquals(1, issues.size());
        assertTrue(issues.get(0).message().contains("couponBatchId") && issues.get(0).message().contains("gmtCreate"));
    }
}
