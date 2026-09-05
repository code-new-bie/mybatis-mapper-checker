package com.mapperchecker.idea.run;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.UnresolvedReason;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 端到端：Java 接口 / DAO + Mapper XML → 报告。覆盖 MMC001 / 002 / 003、抑制、多消费者。
 */
public class CheckRunnerEndToEndTest extends LightJavaCodeInsightFixtureTestCase {

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

    private CheckRunner.Outcome runProject(CheckSettings settings) {
        return new CheckRunner(getProject(), settings).run(CheckScope.project(), null);
    }

    private CheckRunner.Outcome runFile(PsiFile file, CheckSettings settings) {
        VirtualFile vf = file.getVirtualFile();
        return new CheckRunner(getProject(), settings).run(CheckScope.file(vf), null);
    }

    private static List<ContractIssue> ofRule(CheckRunner.Outcome o, RuleId rule) {
        List<ContractIssue> out = new ArrayList<>();
        for (ContractIssue i : o.result().issues()) {
            if (i.ruleId() == rule) {
                out.add(i);
            }
        }
        return out;
    }

    private void addOrderMapperXml(String statements) {
        myFixture.addFileToProject("mapper/OrderMapper.xml", "<mapper namespace=\"" + NS + "\">" + statements + "</mapper>");
    }

    public void test声明级Param未使用() {
        addOrderMapperXml("<select id=\"queryOrder\">SELECT * FROM t WHERE m = #{merchantId} AND s = #{status}</select>");
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                import java.util.List;
                public interface OrderMapper {
                    List<Object> queryOrder(@Param("merchantId") Long m, @Param("poiId") Long p, @Param("status") String s);
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> issues = ofRule(o, RuleId.MMC001);
        assertEquals(1, issues.size());
        ContractIssue issue = issues.get(0);
        assertEquals("poiId", issue.parameterName());
        assertEquals(Confidence.HIGH, issue.confidence());
        assertEquals(NS + ".queryOrder", issue.statementId());
        assertTrue(issue.primaryLocation().filePath().endsWith("OrderMapper.java"));
        assertTrue(issue.secondaryLocation().filePath().endsWith("OrderMapper.xml"));
        assertEquals(1, o.result().statistics().mapperInterfaces());
        assertEquals(1, o.result().statistics().daoInvocations());
        assertEquals(1, o.result().statistics().resolvedInvocations());
        // 可导航
        assertEquals(1, o.reported().size());
        assertNotNull(o.reported().get(0).javaElement());
        assertNotNull(o.reported().get(0).mapperElement());
        assertEquals("select", ((com.intellij.psi.xml.XmlTag) o.reported().get(0).mapperElement()).getName());
    }

    public void testMMC002接口方法无XML无注解() {
        addOrderMapperXml("<select id=\"a\">SELECT 1</select>");
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.*;
                public interface OrderMapper {
                    int a();
                    int missing(Long id);
                    @Select("SELECT #{id}") int annotated(Long id);
                    @SelectProvider(type = Object.class, method = "m") int prov(Long id);
                    default int d() { return 0; }
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> issues = ofRule(o, RuleId.MMC002);
        assertEquals(1, issues.size());
        assertEquals(NS + ".missing", issues.get(0).statementId());
        assertEquals(Confidence.HIGH, issues.get(0).confidence());
        assertEquals(1, o.result().unresolved().size());
        assertEquals(UnresolvedReason.PROVIDER, o.result().unresolved().get(0).reason());
    }

    public void testMMC003_XML与注解同时存在_多文件() {
        myFixture.addFileToProject("mapper/mysql/OrderMapper.xml", "<mapper namespace=\"" + NS + "\"><select id=\"both\">1</select><select id=\"dup\">1</select></mapper>");
        myFixture.addFileToProject("mapper/oracle/OrderMapper.xml", "<mapper namespace=\"" + NS + "\"><select id=\"dup\">2</select></mapper>");
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.*;
                public interface OrderMapper {
                    @Select("SELECT 1") int both();
                    int dup();
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> issues = ofRule(o, RuleId.MMC003);
        assertEquals(2, issues.size());
        ContractIssue dup = issues.stream().filter(i -> i.statementId().endsWith(".dup")).findFirst().orElseThrow();
        assertEquals("可能为多数据库变体。", dup.remark());
        assertEquals(2, dup.candidates().size());

        // 忽略 oracle 目录后 dup 不再歧义
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of("**/mapper/oracle/**"), 3, true, Set.of(), Map.of());
        assertEquals(1, ofRule(runProject(s), RuleId.MMC003).size());
    }

    public void test单Map参数到调用点追踪() {
        addOrderMapperXml("<select id=\"q\">SELECT * FROM t WHERE m = #{merchantId}</select>");
        myFixture.addClass("""
                package com.example.dao;
                import java.util.*;
                public interface OrderMapper { List<Object> q(Map<String, Object> p); }
                """);
        myFixture.addClass("""
                package com.example.service;
                import java.util.*;
                import com.example.dao.OrderMapper;
                public class OrderService {
                    private OrderMapper orderMapper;
                    public void run(Long merchantId, Long poiId) {
                        Map<String, Object> params = new HashMap<>();
                        params.put("merchantId", merchantId);
                        params.put("poiId", poiId);
                        orderMapper.q(params);
                    }
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> issues = ofRule(o, RuleId.MMC001);
        assertEquals(1, issues.size());
        assertEquals("poiId", issues.get(0).parameterName());
        assertTrue(issues.get(0).primaryLocation().filePath().endsWith("OrderService.java"));
        assertEquals("参数 'poiId' 已传入 'OrderMapper.q'，但对应 Mapper SQL 未使用该参数。", issues.get(0).message());
        assertEquals("\"poiId\"", o.reported().get(0).javaElement().getText());
    }

    public void testSqlSession字符串调用与跨方法() {
        addOrderMapperXml("<select id=\"q\">SELECT * FROM t WHERE m = #{merchantId}</select>");
        myFixture.addClass("""
                package com.example.service;
                import java.util.*;
                import org.apache.ibatis.session.SqlSession;
                public class OrderDao {
                    private SqlSession sqlSession;
                    public List<Object> query(Long merchantId, Long poiId) {
                        return sqlSession.selectList("com.example.dao.OrderMapper.q", buildParams(merchantId, poiId));
                    }
                    private Map<String, Object> buildParams(Long m, Long p) {
                        Map<String, Object> x = new HashMap<>();
                        x.put("merchantId", m);
                        x.put("poiId", p);
                        return x;
                    }
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> issues = ofRule(o, RuleId.MMC001);
        assertEquals(1, issues.size());
        assertEquals("poiId", issues.get(0).parameterName());
        assertEquals(Confidence.MEDIUM, issues.get(0).confidence());
        assertEquals(List.of("OrderDao.query()", "OrderDao.buildParams()"), issues.get(0).callPath());
    }

    public void test多消费者_部分statement使用则不报() {
        addOrderMapperXml("""
                <select id="a">SELECT #{merchantId}</select>
                <select id="b">SELECT #{merchantId}, #{poiId}</select>
                """);
        myFixture.addClass("""
                package com.example.service;
                import java.util.*;
                import org.apache.ibatis.session.SqlSession;
                public class OrderDao {
                    private SqlSession sqlSession;
                    public void run(Long m, Long p) {
                        sqlSession.selectList("com.example.dao.OrderMapper.a", build(m, p));
                        sqlSession.selectList("com.example.dao.OrderMapper.b", build(m, p));
                    }
                    private Map<String, Object> build(Long m, Long p) {
                        Map<String, Object> x = new HashMap<>();
                        x.put("merchantId", m);
                        x.put("poiId", p);
                        return x;
                    }
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        assertTrue(ofRule(o, RuleId.MMC001).isEmpty());
    }

    public void test抑制三种方式() {
        addOrderMapperXml("<select id=\"q\">SELECT #{a}</select><select id=\"r\">SELECT #{a}</select><select id=\"s\">SELECT #{a}</select>");
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    int q(@Param("a") Long a, @Param("b") Long b);
                    @SuppressWarnings("MMC001")
                    int r(@Param("a") Long a, @Param("b") Long b);
                    int s(@Param("a") Long a, @Param("b") Long b); // mapper-checker: ignore
                }
                """);
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(NS + ".q#b"), List.of(), 3, true, Set.of(), Map.of());
        CheckRunner.Outcome o = runProject(s);
        assertTrue(ofRule(o, RuleId.MMC001).isEmpty());
        assertEquals(3, o.result().statistics().suppressedIssues());
    }

    public void test忽略参数与忽略statement() {
        addOrderMapperXml("<select id=\"q\">SELECT #{a}</select><select id=\"r\">SELECT #{a}</select>");
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper {
                    int q(@Param("a") Long a, @Param("tmpFlag") Long b);
                    int r(@Param("a") Long a, @Param("x") Long b);
                }
                """);
        CheckSettings s = new CheckSettings(List.of("tmp*"), Set.of(NS + ".r"), Set.of(), List.of(), 3, true, Set.of(), Map.of());
        assertTrue(ofRule(runProject(s), RuleId.MMC001).isEmpty());
        assertEquals(2, ofRule(runProject(CheckSettings.defaults()), RuleId.MMC001).size());
    }

    public void test单文件范围() {
        addOrderMapperXml("<select id=\"q\">SELECT #{a}</select>");
        PsiFile mapper = myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { int q(@Param("a") Long a, @Param("b") Long b); }
                """).getContainingFile();
        myFixture.addClass("package com.example.dao; import org.apache.ibatis.annotations.*; @Mapper public interface Other { int x(); }");
        CheckRunner.Outcome o = runFile(mapper, CheckSettings.defaults());
        assertEquals(1, o.result().issues().size());
        assertEquals(RuleId.MMC001, o.result().issues().get(0).ruleId());
        assertEquals(1, o.result().statistics().mapperInterfaces());
    }

    public void testIBatis兼容路径() {
        myFixture.addFileToProject("sqlmap/Order.xml", """
                <sqlMap namespace="Order">
                    <select id="query">SELECT * FROM t <dynamic prepend="WHERE"><isNotNull property="merchantId">m = #merchantId#</isNotNull></dynamic></select>
                </sqlMap>
                """);
        myFixture.addClass("""
                package com.example.legacy;
                import java.util.*;
                import org.springframework.orm.ibatis.SqlMapClientTemplate;
                public class OrderDao {
                    private SqlMapClientTemplate getSqlMapClientTemplate() { return null; }
                    public List query(Long merchantId, Long poiId) {
                        Map<String, Object> params = new HashMap<>();
                        params.put("merchantId", merchantId);
                        params.put("poiId", poiId);
                        return getSqlMapClientTemplate().queryForList("Order.query", params);
                    }
                    public Object missing() { return getSqlMapClientTemplate().queryForObject("Order.nope", 1L); }
                }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        assertEquals(1, ofRule(o, RuleId.MMC001).size());
        assertEquals("poiId", ofRule(o, RuleId.MMC001).get(0).parameterName());
        List<ContractIssue> nf = ofRule(o, RuleId.MMC002);
        assertEquals(1, nf.size());
        assertEquals("Order.nope", nf.get(0).statementId());
        // namespace Order 存在，所以是高置信度
        assertEquals(Confidence.HIGH, nf.get(0).confidence());
    }

    public void testMMC002_namespace完全不存在时降置信度() {
        myFixture.addClass("""
                package com.example.legacy;
                import org.apache.ibatis.session.SqlSession;
                public class Dao { private SqlSession s; public Object q() { return s.selectOne("com.other.Unknown.q", 1L); } }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        List<ContractIssue> nf = ofRule(o, RuleId.MMC002);
        assertEquals(1, nf.size());
        assertEquals(Confidence.MEDIUM, nf.get(0).confidence());
        assertEquals("可能定义在未索引的依赖中。", nf.get(0).remark());
    }

    public void test无法解析入参在报告中可见() {
        addOrderMapperXml("<select id=\"q\">SELECT #{a}</select>");
        myFixture.addClass("""
                package com.example.service;
                import java.util.*;
                import org.apache.ibatis.session.SqlSession;
                public class Dao { private SqlSession s; public Object q(Map<String, Object> incoming) { return s.selectOne("com.example.dao.OrderMapper.q", incoming); } }
                """);
        CheckRunner.Outcome o = runProject(CheckSettings.defaults());
        assertTrue(o.result().issues().isEmpty());
        assertEquals(1, o.result().unresolved().size());
        assertEquals(UnresolvedReason.METHOD_PARAM, o.result().unresolved().get(0).reason());
    }
}
