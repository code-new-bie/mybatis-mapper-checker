package com.mapperchecker.idea.index;

import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.idea.mapper.MapperRepository;

import java.util.List;
import java.util.Set;

/**
 * 索引与仓库测试：statement 定位、include 跨文件展开、databaseId 合并、多文件多候选、忽略路径。
 */
public class MapperIndexTest extends BasePlatformTestCase {

    private static final String NS = "com.example.order.dao.OrderMapper";

    private MapperRepository repo(List<String> ignoredPaths) {
        return new MapperRepository(getProject(), ignoredPaths);
    }

    public void test索引namespace与statement() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="queryOrder">SELECT 1 FROM t WHERE a = #{merchantId}</select>
                </mapper>
                """.formatted(NS));
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());
        assertTrue(MapperNamespaceIndex.hasNamespace(getProject(), NS, scope));
        assertFalse(MapperNamespaceIndex.hasNamespace(getProject(), "com.example.Nope", scope));

        List<ResolvedStatement> found = repo(List.of()).findStatements(NS + ".queryOrder", scope);
        assertEquals(1, found.size());
        assertEquals(Set.of("merchantId"), found.get(0).parameterNames());
        assertFalse(found.get(0).partiallyParsed());
        assertTrue(found.get(0).primaryLocation().filePath().endsWith("OrderMapper.xml"));
    }

    public void test跨文件include展开() {
        myFixture.addFileToProject("mapper/Common.xml", """
                <mapper namespace="com.example.common.dao.Common">
                    <sql id="scope">merchant_id = #{merchantId} <include refid="poiScope"/></sql>
                    <sql id="poiScope">AND poi_id = #{poiId}</sql>
                </mapper>
                """);
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="q">SELECT 1 FROM t WHERE <include refid="com.example.common.dao.Common.scope"/> AND s = #{status}</select>
                </mapper>
                """.formatted(NS));
        List<ResolvedStatement> found = repo(List.of()).findStatements(NS + ".q", GlobalSearchScope.projectScope(getProject()));
        assertEquals(1, found.size());
        assertEquals(Set.of("merchantId", "poiId", "status"), found.get(0).parameterNames());
        assertFalse(found.get(0).partiallyParsed());
    }

    public void test同文件databaseId变体合并() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="q" databaseId="mysql">SELECT #{a}</select>
                    <select id="q" databaseId="oracle">SELECT #{b}</select>
                </mapper>
                """.formatted(NS));
        List<ResolvedStatement> found = repo(List.of()).findStatements(NS + ".q", GlobalSearchScope.projectScope(getProject()));
        assertEquals(1, found.size());
        assertEquals(Set.of("a", "b"), found.get(0).parameterNames());
        assertEquals(2, found.get(0).definitions().size());
    }

    public void test多文件同fullId为多候选() {
        myFixture.addFileToProject("mapper/mysql/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT #{a}</select></mapper>".formatted(NS));
        myFixture.addFileToProject("mapper/oracle/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT #{b}</select></mapper>".formatted(NS));
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());
        assertEquals(2, repo(List.of()).findStatements(NS + ".q", scope).size());
        // 忽略 oracle 目录后只剩一个
        assertEquals(1, repo(List.of("**/mapper/oracle/**")).findStatements(NS + ".q", scope).size());
    }

    public void test同namespace多文件合并() {
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"a\">SELECT #{x}</select></mapper>".formatted(NS));
        myFixture.addFileToProject("mapper/OrderMapperExt.xml",
                "<mapper namespace=\"%s\"><select id=\"b\">SELECT #{y}</select></mapper>".formatted(NS));
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());
        MapperRepository repo = repo(List.of());
        assertEquals(1, repo.findStatements(NS + ".a", scope).size());
        assertEquals(1, repo.findStatements(NS + ".b", scope).size());
        assertTrue(repo.findStatements(NS + ".c", scope).isEmpty());
    }

    public void testIBatis_parameterMap与include短id() {
        myFixture.addFileToProject("sqlmap/Order.xml", """
                <sqlMap namespace="Order">
                    <parameterMap id="pm" class="map"><parameter property="merchantId"/><parameter property="poiId"/></parameterMap>
                    <sql id="scope">status = #status#</sql>
                    <update id="upd" parameterMap="pm">UPDATE t SET a = ? WHERE <include refid="scope"/></update>
                </sqlMap>
                """);
        List<ResolvedStatement> found = repo(List.of()).findStatements("Order.upd", GlobalSearchScope.projectScope(getProject()));
        assertEquals(1, found.size());
        assertEquals(Set.of("merchantId", "poiId", "status"), found.get(0).parameterNames());
    }

    public void testInclude循环标部分解析() {
        myFixture.addFileToProject("mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <sql id="a">#{x} <include refid="b"/></sql>
                    <sql id="b">#{y} <include refid="a"/></sql>
                    <select id="q"><include refid="a"/></select>
                </mapper>
                """.formatted(NS));
        List<ResolvedStatement> found = repo(List.of()).findStatements(NS + ".q", GlobalSearchScope.projectScope(getProject()));
        assertTrue(found.get(0).partiallyParsed());
        assertEquals(Set.of("x", "y"), found.get(0).parameterNames());
    }

    public void test非Mapper的XML不进索引() {
        myFixture.addFileToProject("pom.xml", "<project><artifactId>a</artifactId></project>");
        myFixture.addFileToProject("mapper/OrderMapper.xml",
                "<mapper namespace=\"%s\"><select id=\"q\">SELECT 1</select></mapper>".formatted(NS));
        GlobalSearchScope scope = GlobalSearchScope.projectScope(getProject());
        // getAllKeys 是全局超集（含其他测试的残留 key），只能按 scope 查文件
        var files = MapperNamespaceIndex.filesFor(getProject(), NS, scope);
        assertEquals(1, files.size());
        assertEquals("OrderMapper.xml", files.iterator().next().getName());
        // pom.xml 没有 namespace，任何 key 都不会指向它
        for (String ns : MapperNamespaceIndex.allNamespaces(getProject())) {
            for (var f : MapperNamespaceIndex.filesFor(getProject(), ns, scope)) {
                assertFalse("pom.xml".equals(f.getName()));
            }
        }
    }
}
