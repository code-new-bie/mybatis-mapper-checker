package com.mapperchecker.idea.run;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.idea.inspection.JavaRulesLocalInspection;
import com.mapperchecker.idea.report.ReportExporter;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** 团队豁免文件与纯 Java 规则实时开关。 */
public class ExemptionAndRealtimeTest extends LightJavaCodeInsightFixtureTestCase {

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
                public class OrderQuery { private Long poiId; public Long getPoiId() { return poiId; } public void setPoiId(Long v) { poiId = v; } }
                """);
    }

    private static List<ContractIssue> ofRule(CheckRunner.Outcome o, RuleId r) {
        return o.result().issues().stream().filter(i -> i.ruleId() == r).toList();
    }

    public void test豁免文件命中后进入已豁免分组并可导出() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", "<mapper namespace=\"%s\"><select id=\"q\">SELECT #{a}</select></mapper>".formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { int q(@Param("a") Long a, @Param("b") Long b, @Param("c") Long c); }
                """);
        myFixture.addFileToProject(".binding-scan-ignore.yml", """
                # 团队豁免
                - rule: DAL-001
                  target: com.example.dao.OrderMapper.q#b
                  reason: b 由拦截器注入
                  by: li
                  at: 2026-09-07
                - target: com.example.dao.OrderMapper.q#c
                  reason: 缺 by 与 at，不生效
                """);
        CheckRunner.Outcome o = new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
        // b 被豁免，c 的豁免不完整仍报
        assertEquals(List.of("c"), ofRule(o, RuleId.DAL_001).stream().map(ContractIssue::parameterName).toList());
        assertEquals(1, o.exempted().size());
        assertEquals("b", o.exempted().get(0).reported().issue().parameterName());
        assertEquals("b 由拦截器注入", o.exempted().get(0).exemption().reason());
        assertEquals("li", o.exempted().get(0).exemption().by());
        assertNotNull(o.exempted().get(0).reported().javaElement());
        assertEquals(1, o.result().statistics().exemptedIssues());
        assertEquals(1, o.result().statistics().invalidExemptions());

        String md = ReportExporter.toMarkdown(o.result());
        assertTrue(md, md.contains("## 已豁免"));
        assertTrue(md, md.contains("| DAL-001 | b |") && md.contains("| li | 2026-09-07 | b 由拦截器注入 |"));
        assertTrue(md, md.contains("1 条豁免记录缺 reason / by / at 未生效"));
        String csv = ReportExporter.toCsv(o.result());
        assertTrue(csv, csv.contains("已豁免,DAL-001,b,") && csv.contains(",li,2026-09-07,b 由拦截器注入"));
        assertTrue(csv, csv.contains("问题,DAL-001,c,"));
    }

    public void test豁免target只写statement时匹配任意参数与规则() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", "<mapper namespace=\"%s\"><select id=\"legacy\">SELECT 1</select></mapper>".formatted(NS));
        myFixture.addClass("""
                package com.example.dao;
                import org.apache.ibatis.annotations.Param;
                public interface OrderMapper { int legacy(@Param("a") Long a, @Param("b") Long b); int missing(); }
                """);
        myFixture.addFileToProject(".binding-scan-ignore.yml", """
                - target: com.example.dao.OrderMapper.legacy
                  reason: 历史语句
                  by: li
                  at: 2026-01-01
                """);
        CheckRunner.Outcome o = new CheckRunner(getProject(), CheckSettings.defaults()).run(CheckScope.project(), null);
        assertTrue(ofRule(o, RuleId.DAL_001).isEmpty());
        assertEquals(2, o.exempted().size());
        // missing 不在豁免范围
        assertEquals(1, ofRule(o, RuleId.DAL_005).size());
    }

    private static final String SVC = """
            package com.example.service;
            import com.example.OrderQuery;
            public class Svc {
                public OrderQuery t(OrderQuery src) {
                    OrderQuery q = new OrderQuery();
                    org.springframework.beans.BeanUtils.copyProperties(q, src);
                    return q;
                }
            }
            """;

    public void test实时提示默认关闭() {
        myFixture.enableInspections(new JavaRulesLocalInspection());
        myFixture.configureByText("Svc.java", SVC);
        assertFalse(MapperCheckerSettings.getInstance(getProject()).state().realtimeJavaRules);
        assertTrue(myFixture.doHighlighting().stream().noneMatch(h -> h.getDescription() != null && h.getDescription().contains("[DAL-")));
    }

    public void test实时提示开启后高亮纯Java规则() {
        myFixture.enableInspections(new JavaRulesLocalInspection());
        myFixture.configureByText("Svc.java", SVC);
        MapperCheckerSettings settings = MapperCheckerSettings.getInstance(getProject());
        settings.state().realtimeJavaRules = true;
        try {
            List<HighlightInfo> infos = myFixture.doHighlighting();
            String dump = infos.stream().map(h -> h.getSeverity() + " " + h.getDescription()).toList().toString();
            assertTrue(dump, infos.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().startsWith("[DAL-004]")));
            assertTrue(dump, infos.stream().anyMatch(h -> h.getDescription() != null && h.getDescription().startsWith("[DAL-020]")));
        } finally {
            settings.state().realtimeJavaRules = false;
        }
    }
}
