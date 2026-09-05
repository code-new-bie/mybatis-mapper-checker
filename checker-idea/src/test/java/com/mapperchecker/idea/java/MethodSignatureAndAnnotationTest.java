package com.mapperchecker.idea.java;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.StatementType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Mapper 接口判定、签名参数解析、注解 SQL 解析。 */
public class MethodSignatureAndAnnotationTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MyBatisStubs.addAll(myFixture);
        myFixture.addClass("package com.example; public class OrderQuery { private Long poiId; public void setPoiId(Long v) { poiId = v; } }");
    }

    private int counter;

    private PsiClass mapper(String body) {
        return myFixture.addClass("""
                package com.example.dao%d;
                import org.apache.ibatis.annotations.*;
                import org.apache.ibatis.session.*;
                import java.util.*;
                import com.example.OrderQuery;
                public interface OrderMapper {
                %s
                }
                """.formatted(++counter, body));
    }

    private static PsiMethod method(PsiClass c, String name) {
        return c.findMethodsByName(name, false)[0];
    }

    private static Set<String> names(List<ParameterReference> refs) {
        Set<String> s = new TreeSet<>();
        for (ParameterReference r : refs) {
            s.add(r.rootName());
        }
        return s;
    }

    public void test接口判定() {
        PsiClass byAnno = myFixture.addClass("package a; @org.apache.ibatis.annotations.Mapper public interface A { }");
        assertTrue(MapperInterfaceDetector.isMapperInterface(byAnno));

        PsiClass bySql = myFixture.addClass("package a; public interface B { @org.apache.ibatis.annotations.Select(\"x\") int q(); }");
        assertTrue(MapperInterfaceDetector.isMapperInterface(bySql));

        myFixture.addFileToProject("mapper/C.xml", "<mapper namespace=\"a.C\"><select id=\"q\">1</select></mapper>");
        PsiClass byXml = myFixture.addClass("package a; public interface C { int q(); }");
        assertTrue(MapperInterfaceDetector.isMapperInterface(byXml));

        PsiClass plain = myFixture.addClass("package a; public interface D { int q(); }");
        assertFalse(MapperInterfaceDetector.isMapperInterface(plain));
        PsiClass cls = myFixture.addClass("package a; @org.apache.ibatis.annotations.Mapper public class E { }");
        assertFalse(MapperInterfaceDetector.isMapperInterface(cls));
    }

    public void test可检查方法() {
        PsiClass c = mapper("""
                    int a(Long id);
                    default int b() { return 0; }
                    static int c() { return 0; }
                """);
        assertTrue(MapperInterfaceDetector.isCheckableMethod(method(c, "a")));
        assertFalse(MapperInterfaceDetector.isCheckableMethod(method(c, "b")));
        assertFalse(MapperInterfaceDetector.isCheckableMethod(method(c, "c")));
    }

    public void testParam注解() {
        PsiClass c = mapper("List<Object> q(@Param(\"merchantId\") Long m, @Param(\"poiId\") Long p);");
        var r = MethodSignatureParameterResolver.resolve(method(c, "q"));
        assertEquals(MethodSignatureParameterResolver.Mode.COMPARABLE, r.mode());
        assertEquals(Set.of("merchantId", "poiId"), names(r.parameters()));
        assertEquals(ParameterSourceType.PARAM_ANNOTATION, r.parameters().get(0).sourceType());
        assertTrue(r.parameters().get(0).aliases().isEmpty());
        assertTrue(r.parameters().get(0).location().filePath().endsWith("OrderMapper.java"));
    }

    public void test无Param多参数别名组() {
        PsiClass c = mapper("List<Object> q(Long a, String b);");
        var r = MethodSignatureParameterResolver.resolve(method(c, "q"));
        assertEquals(MethodSignatureParameterResolver.Mode.COMPARABLE, r.mode());
        assertEquals(Set.of("a", "arg0", "param1"), r.parameters().get(0).candidateNames());
        assertEquals(Set.of("b", "arg1", "param2"), r.parameters().get(1).candidateNames());
        assertEquals(ParameterSourceType.METHOD_PARAM, r.parameters().get(0).sourceType());
    }

    public void test混合Param与无Param() {
        PsiClass c = mapper("List<Object> q(@Param(\"a\") Long a, String b);");
        var r = MethodSignatureParameterResolver.resolve(method(c, "q"));
        assertEquals(Set.of("a"), r.parameters().get(0).candidateNames());
        assertEquals(Set.of("b", "arg1", "param2"), r.parameters().get(1).candidateNames());
    }

    public void test单Bean展开属性_单标量不检查() {
        PsiClass c = mapper("""
                    List<Object> byBean(OrderQuery q);
                    Object byId(Long id);
                    Object byStr(String s);
                    int none();
                """);
        // 单 Bean 展开实体属性（低置信度）
        var bean = MethodSignatureParameterResolver.resolve(method(c, "byBean"));
        assertEquals(MethodSignatureParameterResolver.Mode.COMPARABLE, bean.mode());
        assertEquals(Set.of("poiId"), names(bean.parameters()));
        assertEquals(ParameterSourceType.BEAN_PROPERTY, bean.parameters().get(0).sourceType());
        assertEquals("OrderQuery", bean.parameters().get(0).ownerName());
        assertTrue(bean.parameters().get(0).location().filePath().endsWith("OrderQuery.java"));
        assertEquals(MethodSignatureParameterResolver.Mode.NOT_COMPARABLE, MethodSignatureParameterResolver.resolve(method(c, "byId")).mode());
        assertEquals(MethodSignatureParameterResolver.Mode.NOT_COMPARABLE, MethodSignatureParameterResolver.resolve(method(c, "byStr")).mode());
        assertEquals(MethodSignatureParameterResolver.Mode.NOT_COMPARABLE, MethodSignatureParameterResolver.resolve(method(c, "none")).mode());
    }

    public void test单Map转调用点() {
        PsiClass c = mapper("List<Object> q(Map<String, Object> params);");
        var r = MethodSignatureParameterResolver.resolve(method(c, "q"));
        assertEquals(MethodSignatureParameterResolver.Mode.SINGLE_MAP, r.mode());
        assertEquals("params", r.mapParameter().getName());
    }

    public void test单集合与数组别名组() {
        PsiClass c = mapper("""
                    List<Object> byList(List<Long> ids);
                    List<Object> bySet(Set<Long> ids);
                    List<Object> byArray(Long[] ids);
                """);
        assertEquals(Set.of("collection", "list", "ids", "arg0", "param1"),
                MethodSignatureParameterResolver.resolve(method(c, "byList")).parameters().get(0).candidateNames());
        assertEquals(Set.of("collection", "ids", "arg0", "param1"),
                MethodSignatureParameterResolver.resolve(method(c, "bySet")).parameters().get(0).candidateNames());
        assertEquals(Set.of("array", "ids", "arg0", "param1"),
                MethodSignatureParameterResolver.resolve(method(c, "byArray")).parameters().get(0).candidateNames());
    }

    public void testRowBounds排除() {
        PsiClass c = mapper("List<Object> q(@Param(\"a\") Long a, RowBounds rb, ResultHandler<Object> h);");
        var r = MethodSignatureParameterResolver.resolve(method(c, "q"));
        assertEquals(1, r.parameters().size());
        assertEquals("a", r.parameters().get(0).rootName());

        PsiClass c2 = mapper("List<Object> q(Map<String, Object> m, RowBounds rb);");
        assertEquals(MethodSignatureParameterResolver.Mode.SINGLE_MAP, MethodSignatureParameterResolver.resolve(method(c2, "q")).mode());
    }

    public void test注解SQL基本() {
        PsiClass c = mapper("""
                    @Select("SELECT * FROM orders WHERE merchant_id = #{merchantId} AND status = #{status}")
                    List<Object> q(@Param("merchantId") Long m, @Param("poiId") Long p, @Param("status") String s);
                """);
        MapperStatement st = AnnotationSqlParser.parse(method(c, "q"));
        assertNotNull(st);
        assertEquals(StatementType.SELECT, st.type());
        assertEquals(c.getQualifiedName(), st.namespace());
        assertEquals("q", st.id());
        assertEquals(Set.of("merchantId", "status"), names(st.directParameters()));
        assertFalse(st.partiallyParsed());
    }

    public void test注解SQL数组与常量拼接() {
        myFixture.addClass("package com.example; public class Sql { public static final String TABLE = \"orders\"; }");
        PsiClass c = mapper("""
                    @Update({"UPDATE " + com.example.Sql.TABLE, " SET a = #{a}", " WHERE id = #{id}"})
                    int u(@Param("a") String a, @Param("id") Long id);
                """);
        MapperStatement st = AnnotationSqlParser.parse(method(c, "u"));
        assertEquals(StatementType.UPDATE, st.type());
        assertEquals(Set.of("a", "id"), names(st.directParameters()));
        assertFalse(st.partiallyParsed());
    }

    public void test注解SQL不可求值片段标部分解析() {
        PsiClass c = mapper("""
                    @Select({"SELECT 1 WHERE a = #{a}", Helper.dyn()})
                    List<Object> q(@Param("a") Long a);
                    class Helper { static String dyn() { return ""; } }
                """);
        MapperStatement st = AnnotationSqlParser.parse(method(c, "q"));
        assertNotNull(st);
        assertTrue(st.partiallyParsed());
    }

    public void test注解SQL_script动态标签() {
        PsiClass c = mapper("""
                    @Select("<script>SELECT * FROM t <where><if test='poiId != null'>AND poi_id = #{poiId}</if>"
                          + "<foreach collection='ids' item='it' open='(' close=')'>#{it}</foreach></where></script>")
                    List<Object> q(@Param("poiId") Long p, @Param("ids") List<Long> ids, @Param("x") Long x);
                """);
        MapperStatement st = AnnotationSqlParser.parse(method(c, "q"));
        assertEquals(Set.of("poiId", "ids"), names(st.directParameters()));
        assertFalse(st.partiallyParsed());
    }

    public void test无注解返回null_Provider识别() {
        PsiClass c = mapper("""
                    List<Object> plain(Long id);
                    @SelectProvider(type = Object.class, method = "m") List<Object> prov(Long id);
                """);
        assertNull(AnnotationSqlParser.parse(method(c, "plain")));
        assertNull(AnnotationSqlParser.parse(method(c, "prov")));
        assertTrue(MapperInterfaceDetector.hasProviderAnnotation(method(c, "prov")));
        assertFalse(MapperInterfaceDetector.hasProviderAnnotation(method(c, "plain")));
    }

    public void test声明级提取() {
        PsiClass c = mapper("List<Object> q(@Param(\"a\") Long a, @Param(\"b\") Long b); List<Object> m(Map<String,Object> p);");
        var e = MapperMethodInvocationExtractor.extract(method(c, "q"));
        assertNotNull(e);
        assertEquals(c.getQualifiedName() + ".q", e.invocation().statementId());
        assertEquals(2, e.invocation().parameters().size());
        assertNull(e.mapParameter());
        assertTrue(e.invocation().location().filePath().endsWith("OrderMapper.java"));
        assertTrue(e.invocation().location().line() > 0);

        var m = MapperMethodInvocationExtractor.extract(method(c, "m"));
        assertNull(m.invocation().parameters());
        assertNotNull(m.mapParameter());
    }
}
