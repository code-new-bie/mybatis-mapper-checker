package com.mapperchecker.idea.run;

import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** {@code <include>} 带 {@code <property>}、以及片段套片段时的参数收集。 */
public class IncludePropertyEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String NS = "com.example.dao.OrderMapper";

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        com.mapperchecker.idea.java.MyBatisStubs.addAll(myFixture);
    }

    private void mapper(String body) {
        myFixture.addFileToProject("mapper/OrderMapper.xml", "<mapper namespace=\"" + NS + "\">" + body + "</mapper>");
    }

    private void dao(String methods) {
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper { %s }
                """.formatted(methods));
    }

    private CheckRunner.Outcome runProject() {
        return new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
    }

    private static Set<String> unused(CheckRunner.Outcome o) {
        Set<String> s = new TreeSet<>();
        for (ContractIssue i : o.result().issues()) {
            if (i.ruleId() == RuleId.DAL_001) {
                s.add(i.parameterName());
            }
        }
        return s;
    }

    private static List<String> unresolved(CheckRunner.Outcome o) {
        return o.result().unresolved().stream().map(u -> u.reason() + ":" + u.statementId()).toList();
    }

    public void test自闭合include() {
        mapper("""
                <sql id="cond">AND merchant_id = #{merchantId} AND poi_id = #{poiId}</sql>
                <select id="q">SELECT * FROM t WHERE 1=1 <include refid="cond"/></select>
                """);
        dao("List<Object> q(@Param(\"merchantId\") Long merchantId, @Param(\"poiId\") Long poiId);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }

    public void testInclude带property子标签() {
        mapper("""
                <sql id="cond">
                    <if test="poiId != null">AND ${alias}.poi_id = #{poiId}</if>
                    AND ${alias}.merchant_id = #{merchantId}
                </sql>
                <select id="q">
                    SELECT * FROM t s WHERE 1=1
                    <include refid="cond">
                        <property name="alias" value="s"/>
                    </include>
                </select>
                """);
        dao("List<Object> q(@Param(\"merchantId\") Long merchantId, @Param(\"poiId\") Long poiId);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }

    public void test片段里再include片段() {
        mapper("""
                <sql id="inner">AND poi_id = #{poiId}</sql>
                <sql id="outer">
                    AND merchant_id = #{merchantId}
                    <include refid="inner">
                        <property name="alias" value="s"/>
                    </include>
                </sql>
                <select id="q">
                    SELECT * FROM t s WHERE 1=1
                    <include refid="outer">
                        <property name="alias" value="s"/>
                    </include>
                </select>
                """);
        dao("List<Object> q(@Param(\"merchantId\") Long merchantId, @Param(\"poiId\") Long poiId);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }

    public void test跨文件全限定refid() {
        myFixture.addFileToProject("mapper/CommonMapper.xml", """
                <mapper namespace="com.example.dao.CommonMapper">
                    <sql id="tenant">AND tenant_id = #{tenantId}</sql>
                </mapper>
                """);
        mapper("""
                <select id="q">
                    SELECT * FROM t WHERE 1=1
                    <include refid="com.example.dao.CommonMapper.tenant">
                        <property name="alias" value="s"/>
                    </include>
                </select>
                """);
        dao("List<Object> q(@Param(\"tenantId\") Long tenantId);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }

    public void test片段用property占位当条件名() {
        // ${alias} 只是列前缀，真正的参数还是 #{...}
        mapper("""
                <sql id="cond">
                    <where>
                        <if test="query.poiId != null">AND ${alias}.poi_id = #{query.poiId}</if>
                        <if test="query.merchantId != null">AND ${alias}.merchant_id = #{query.merchantId}</if>
                    </where>
                </sql>
                <select id="q">
                    SELECT * FROM t s
                    <include refid="cond"><property name="alias" value="s"/></include>
                </select>
                """);
        myFixture.addClass("""
                package com.example;
                public class OrderQuery {
                    private Long merchantId; private Long poiId;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                }
                """);
        dao("List<Object> q(@Param(\"query\") com.example.OrderQuery query);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }

    public void test片段里用property拼参数名() {
        mapper("""
                <sql id="cond">
                    <if test="${prefix}poiId != null">AND poi_id = #{${prefix}poiId}</if>
                    AND merchant_id = #{${prefix}merchantId}
                </sql>
                <select id="q">
                    SELECT * FROM t
                    <include refid="cond"><property name="prefix" value="query."/></include>
                </select>
                """);
        myFixture.addClass("""
                package com.example;
                public class OrderQuery {
                    private Long merchantId; private Long poiId;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                }
                """);
        dao("List<Object> q(@Param(\"query\") com.example.OrderQuery query);");
        CheckRunner.Outcome o = runProject();
        assertEquals("不该报未使用：" + unresolved(o), Set.of(), unused(o));
    }

    public void test片段里用property拼列名与别名() {
        mapper("""
                <sql id="cond">
                    <if test="poiId != null">AND ${alias}.${column} = #{poiId}</if>
                </sql>
                <select id="q">
                    SELECT * FROM t s
                    <include refid="cond">
                        <property name="alias" value="s"/>
                        <property name="column" value="poi_id"/>
                    </include>
                </select>
                """);
        dao("List<Object> q(@Param(\"poiId\") Long poiId, @Param(\"merchantId\") Long merchantId);");
        CheckRunner.Outcome o = runProject();
        // merchantId 确实没用到，应当只报它一个
        assertEquals(unresolved(o).toString(), Set.of("merchantId"), unused(o));
    }

    public void test占位符没给值时不报只登记覆盖缺口() {
        mapper("""
                <sql id="cond">AND poi_id = #{${prefix}poiId}</sql>
                <select id="q">SELECT * FROM t <include refid="cond"/></select>
                """);
        dao("List<Object> q(@Param(\"query\") com.example.OrderQuery query);");
        myFixture.addClass("package com.example; public class OrderQuery { private Long poiId; public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; } }");
        CheckRunner.Outcome o = runProject();
        assertEquals(Set.of(), unused(o));
        assertTrue(unresolved(o).toString(), unresolved(o).contains("PARTIAL_STATEMENT:" + NS + ".q"));
    }

    public void test外层include的property传给内层片段() {
        mapper("""
                <sql id="inner"><if test="${prefix}poiId != null">AND poi_id = #{${prefix}poiId}</if></sql>
                <sql id="outer">AND merchant_id = #{${prefix}merchantId} <include refid="inner"/></sql>
                <select id="q">
                    SELECT * FROM t
                    <include refid="outer"><property name="prefix" value="query."/></include>
                </select>
                """);
        myFixture.addClass("""
                package com.example;
                public class OrderQuery {
                    private Long merchantId; private Long poiId; private String remark;
                    public Long getMerchantId() { return merchantId; } public void setMerchantId(Long v) { merchantId = v; }
                    public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; }
                    public String getRemark() { return remark; } public void setRemark(String v) { remark = v; }
                }
                """);
        dao("List<Object> q(@Param(\"query\") com.example.OrderQuery query);");
        CheckRunner.Outcome o = runProject();
        // 两层片段的参数都算数，只有确实没用到的 remark 该报
        assertEquals(unresolved(o).toString(), Set.of("query.remark"), unused(o));
    }

    public void test声明的参数与片段里的对象路径同名时给出提示() {
        // 真机反馈：<sql> 里写的是 query.dataStatuses，方法上却单独声明了 @Param("dataStatuses")
        mapper("""
                <sql id="assignDataStatus">
                    <if test="query.dataStatuses != null">
                        and ${alias}.status in
                        <foreach collection="query.dataStatuses" item="dataStatus" open="(" close=")" separator=",">
                            #{dataStatus.type}
                        </foreach>
                    </if>
                </sql>
                <select id="getTop">
                    select * from t s where s.param_type = #{query.paramType}
                    <include refid="assignDataStatus"><property name="alias" value="s"/></include>
                </select>
                """);
        myFixture.addClass("""
                package com.example;
                public class ParamQuery {
                    private String paramType; private java.util.List<Object> dataStatuses;
                    public String getParamType() { return paramType; } public void setParamType(String v) { paramType = v; }
                    public java.util.List<Object> getDataStatuses() { return dataStatuses; }
                    public void setDataStatuses(java.util.List<Object> v) { dataStatuses = v; }
                }
                """);
        dao("List<Object> getTop(@Param(\"query\") com.example.ParamQuery query, @Param(\"dataStatuses\") java.util.List<Object> dataStatuses);");
        CheckRunner.Outcome o = runProject();
        // query 与 query.dataStatuses 都用到了，只有多出来的 dataStatuses 该报
        assertEquals(unresolved(o).toString(), Set.of("dataStatuses"), unused(o));
        ContractIssue issue = o.result().issues().stream().filter(i -> i.ruleId() == RuleId.DAL_001).findFirst().orElseThrow();
        assertTrue(issue.remark(), issue.remark().contains("query.dataStatuses"));
    }

    public void test片段里的foreach局部名不算参数() {
        mapper("""
                <sql id="cond">
                    <foreach collection="ids" item="dataStatus" open="(" close=")" separator=",">#{dataStatus.type}</foreach>
                </sql>
                <select id="q">select * from t where status in <include refid="cond"/></select>
                """);
        dao("List<Object> q(@Param(\"ids\") java.util.List<Object> ids);");
        CheckRunner.Outcome o = runProject();
        assertEquals(unresolved(o).toString(), Set.of(), unused(o));
    }
}
