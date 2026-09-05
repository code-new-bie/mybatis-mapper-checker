package com.mapperchecker.idea.java;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.model.InvocationKind;
import com.mapperchecker.core.model.Operation;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** SqlSession / SqlMapClient 字符串调用识别与 statementId 解析。 */
public class StringCallInvocationExtractorTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MyBatisStubs.addAll(myFixture);
    }

    private List<StringCallInvocationExtractor.Candidate> extractAll(String body) {
        PsiFile file = myFixture.addFileToProject("com/example/Dao.java", """
                package com.example;
                import java.util.*;
                import org.apache.ibatis.session.SqlSession;
                import org.mybatis.spring.SqlSessionTemplate;
                import com.ibatis.sqlmap.client.SqlMapClient;
                import org.springframework.orm.ibatis.SqlMapClientTemplate;
                public class Dao {
                    private static final String NS = "com.example.dao.OrderMapper.";
                    private static final String QUERY = NS + "query";
                    private SqlSession sqlSession;
                    private SqlSessionTemplate template;
                    private SqlMapClient sqlMapClient;
                    private SqlMapClientTemplate getSqlMapClientTemplate() { return null; }
                    private String dyn() { return ""; }
                    private List<String> other;
                    public void run(Map<String, Object> p, String s) {
                %s
                    }
                }
                """.formatted(body));
        List<StringCallInvocationExtractor.Candidate> out = new ArrayList<>();
        for (PsiMethodCallExpression c : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            var cand = StringCallInvocationExtractor.extract(c);
            if (cand != null) {
                out.add(cand);
            }
        }
        return out;
    }

    public void testSqlSession与Template() {
        var list = extractAll("""
                sqlSession.selectList("com.example.dao.OrderMapper.query", p);
                template.selectOne(QUERY, p);
                sqlSession.insert(NS + "insert", p);
                sqlSession.selectList("noParam");
                sqlSession.selectList("withRb", p, null);
                """);
        assertEquals(5, list.size());
        assertEquals(InvocationKind.SQL_SESSION_CALL, list.get(0).kind());
        assertEquals("com.example.dao.OrderMapper.query", StatementIdResolver.resolve(list.get(0).statementIdExpr()));
        assertEquals(Operation.SELECT, list.get(0).operation());
        assertEquals("p", list.get(0).parameterExpr().getText());

        assertEquals("com.example.dao.OrderMapper.query", StatementIdResolver.resolve(list.get(1).statementIdExpr()));
        assertEquals("com.example.dao.OrderMapper.insert", StatementIdResolver.resolve(list.get(2).statementIdExpr()));
        assertEquals(Operation.INSERT, list.get(2).operation());
        assertNull(list.get(3).parameterExpr());
        assertEquals("p", list.get(4).parameterExpr().getText());
    }

    public void testIBatis_SqlMapClient() {
        var list = extractAll("""
                sqlMapClient.queryForList("Order.query", p);
                getSqlMapClientTemplate().queryForObject("Order.get", p);
                getSqlMapClientTemplate().update("Order.upd", p);
                """);
        assertEquals(3, list.size());
        for (var c : list) {
            assertEquals(InvocationKind.SQLMAP_CLIENT_CALL, c.kind());
        }
        assertEquals("Order.query", StatementIdResolver.resolve(list.get(0).statementIdExpr()));
        assertEquals(Operation.SELECT, list.get(1).operation());
        assertEquals(Operation.UPDATE, list.get(2).operation());
    }

    public void test非目标调用不识别() {
        var list = extractAll("""
                other.add("x");
                String t = s.trim();
                sqlSession.selectList(dyn(), p);
                """);
        // dyn() 是 String 类型，识别为候选，但 statementId 解析不出
        assertEquals(1, list.size());
        assertNull(StatementIdResolver.resolve(list.get(0).statementIdExpr()));
    }

    public void test第一参数非String不识别() {
        var list = extractAll("sqlSession.selectList(p == null ? null : \"x\", p);");
        // 三目表达式类型为 String，识别为候选但不可求值
        assertEquals(1, list.size());
        assertNull(StatementIdResolver.resolve(list.get(0).statementIdExpr()));
    }
}
