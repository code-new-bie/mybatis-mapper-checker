package com.mapperchecker.idea.report;

import com.mapperchecker.core.model.CheckResult;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.Statistics;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.core.model.UnresolvedReason;
import junit.framework.TestCase;

import java.util.List;

/** 导出内容与窗口一致：字段齐全、转义正确。 */
public class ReportExporterTest extends TestCase {

    private static CheckResult sample() {
        ContractIssue i = new ContractIssue(RuleId.MMC001, Severity.WARNING, Confidence.MEDIUM,
                "参数 'poiId' 已传入 'OrderMapper.q'，但对应 Mapper SQL 未使用该参数。",
                "备注|含竖线", "poiId", "com.example.dao.OrderMapper.q",
                List.of("OrderDao.query()", "OrderDao.build()"),
                SourceLocation.of("src/OrderDao.java", 10, 20, 42), SourceLocation.of("mapper/OrderMapper.xml", 1, 2, 3), List.of());
        UnresolvedInvocation u = new UnresolvedInvocation("com.example.dao.OrderMapper.r", UnresolvedReason.METHOD_PARAM,
                "incoming", SourceLocation.of("src/OrderDao.java", 30, 40, 50));
        Statistics s = new Statistics();
        s.countIssue(i);
        s.incDaoInvocations();
        s.incUnresolvedInvocations();
        return new CheckResult("整个项目", List.of(i), List.of(u), s);
    }

    public void testMarkdown() {
        String md = ReportExporter.toMarkdown(sample());
        assertTrue(md.contains("# MyBatis Mapper Checker 报告"));
        assertTrue(md.contains("范围：整个项目"));
        assertTrue(md.contains("| MMC001 | poiId | com.example.dao.OrderMapper.q | OrderDao.java:42 | 中 |"));
        // 详情含完整文案、完整路径与行号、备注、调用路径
        assertTrue(md.contains("### com.example.dao.OrderMapper.q"));
        assertTrue(md.contains("**MMC001** 参数 'poiId' 已传入 'OrderMapper.q'，但对应 Mapper SQL 未使用该参数。"));
        assertTrue(md.contains("Java 位置：src/OrderDao.java:42"));
        assertTrue(md.contains("Mapper 位置：mapper/OrderMapper.xml:3"));
        assertTrue(md.contains("备注：备注|含竖线"));
        assertTrue(md.contains("调用路径：OrderDao.query() → OrderDao.build()"));
        assertTrue(md.contains("参数对象是方法入参"));
        assertTrue(md.contains("src/OrderDao.java:50"));
    }

    public void testCsv() {
        String csv = ReportExporter.toCsv(sample());
        String[] lines = csv.split("\n");
        assertEquals("规则,参数或属性,statement,说明,Java 文件,Java 行,Mapper 文件,Mapper 行,置信度,备注,调用路径", lines[0]);
        // 中文逗号不触发 CSV 引号
        assertTrue(lines[1], lines[1].startsWith("MMC001,poiId,com.example.dao.OrderMapper.q,参数 'poiId' 已传入 'OrderMapper.q'，但对应 Mapper SQL 未使用该参数。,src/OrderDao.java,42,mapper/OrderMapper.xml,3,中,备注|含竖线,"));
        assertTrue(lines[1].endsWith("OrderDao.query() -> OrderDao.build()"));
    }

    public void test空报告() {
        String md = ReportExporter.toMarkdown(CheckResult.empty("x"));
        assertTrue(md.contains("未发现问题。"));
        assertTrue(md.contains("无"));
    }
}
