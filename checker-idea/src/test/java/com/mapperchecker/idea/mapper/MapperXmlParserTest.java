package com.mapperchecker.idea.mapper;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.StatementType;
import com.mapperchecker.idea.index.IndexedElement;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mapper XML 解析测试：MyBatis 与 iBatis 2 两种方言、动态标签、CDATA、include、parameterMap。
 */
public class MapperXmlParserTest extends BasePlatformTestCase {

    private ParsedMapperFile parse(String name, String xml) {
        PsiFile file = myFixture.configureByText(name, xml);
        return MapperXmlParser.parse(file);
    }

    private static IndexedElement byId(ParsedMapperFile f, String id) {
        return f.elements().stream().filter(e -> e.id.equals(id)).findFirst().orElseThrow();
    }

    private static Set<String> names(IndexedElement e) {
        Set<String> s = new TreeSet<>();
        for (IndexedElement.Param p : e.params) {
            s.add(p.name());
        }
        return s;
    }

    public void test非Mapper文件返回null() {
        assertNull(parse("pom.xml", "<project><modelVersion>4.0.0</modelVersion></project>"));
    }

    public void testMyBatis基本与动态标签() {
        ParsedMapperFile f = parse("OrderMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.order.dao.OrderMapper">
                    <sql id="cols">id, merchant_id, ${extraCol}</sql>
                    <select id="queryOrder" resultType="Order">
                        SELECT <include refid="cols"/> FROM orders
                        <where>
                            merchant_id = #{merchantId}
                            <if test="query.poiId != null and status == 'ON'">AND poi_id = #{query.poiId, jdbcType=BIGINT}</if>
                            <choose>
                                <when test="ids != null and ids.size() > 0">
                                    AND id IN <foreach collection="ids" item="it" index="i" open="(" separator="," close=")">#{it}</foreach>
                                </when>
                                <otherwise>AND 1 = 1</otherwise>
                            </choose>
                            <bind name="pattern" value="'%' + keyword + '%'"/>
                            AND name LIKE #{pattern}
                        </where>
                        ORDER BY ${orderBy}
                    </select>
                    <insert id="insertOrder" databaseId="mysql">
                        <selectKey keyProperty="id" resultType="long" order="BEFORE">SELECT #{seqName}</selectKey>
                        INSERT INTO orders (a) VALUES (#{a})
                    </insert>
                    <resultMap id="rm" type="Order"><result property="poiId" column="poi_id"/></resultMap>
                </mapper>
                """);
        assertNotNull(f);
        assertEquals(MapperDialect.MYBATIS, f.dialect());
        assertEquals("com.example.order.dao.OrderMapper", f.namespace());
        assertEquals(3, f.elements().size()); // cols, queryOrder, insertOrder；resultMap 不算

        IndexedElement cols = byId(f, "cols");
        assertEquals(IndexedElement.Kind.FRAGMENT, cols.kind);
        assertEquals(Set.of("extraCol"), names(cols));

        IndexedElement q = byId(f, "queryOrder");
        assertEquals(IndexedElement.Kind.STATEMENT, q.kind);
        assertEquals(StatementType.SELECT, q.type);
        assertEquals(List.of("cols"), q.includeRefs);
        // it / i 是 foreach 局部名，pattern 是 bind 局部名，都不计入
        assertEquals(Set.of("merchantId", "query", "status", "ids", "keyword", "orderBy"), names(q));

        IndexedElement ins = byId(f, "insertOrder");
        assertEquals("mysql", ins.databaseId);
        assertEquals(Set.of("seqName", "a"), names(ins)); // selectKey 内计入
    }

    public void testMyBatis参数来源与偏移() {
        String xml = """
                <mapper namespace="A">
                    <select id="q">x = #{poiId} AND <if test="s != null">y = ${col}</if></select>
                </mapper>
                """;
        ParsedMapperFile f = parse("A.xml", xml);
        IndexedElement q = byId(f, "q");
        for (IndexedElement.Param p : q.params) {
            String text = xml.substring(p.startOffset(), p.endOffset());
            switch (p.name()) {
                case "poiId" -> {
                    assertEquals(ParameterSourceType.XML_INLINE, p.sourceType());
                    assertEquals("#{poiId}", text);
                }
                case "col" -> {
                    assertEquals(ParameterSourceType.XML_SUBSTITUTION, p.sourceType());
                    assertEquals("${col}", text);
                }
                case "s" -> {
                    assertEquals(ParameterSourceType.XML_TEST_EXPR, p.sourceType());
                    assertEquals("s != null", text);
                }
                default -> fail("意外参数 " + p.name());
            }
        }
        assertEquals(3, q.params.size());
    }

    public void testCDATA内提取且偏移正确() {
        String xml = """
                <mapper namespace="A">
                    <select id="q"><![CDATA[ SELECT * FROM t WHERE a < #{limit} AND b = #{x} ]]></select>
                </mapper>
                """;
        ParsedMapperFile f = parse("A.xml", xml);
        IndexedElement q = byId(f, "q");
        assertEquals(Set.of("limit", "x"), names(q));
        for (IndexedElement.Param p : q.params) {
            assertEquals("#{" + p.name() + "}", xml.substring(p.startOffset(), p.endOffset()));
        }
    }

    public void testInclude内property不递归() {
        ParsedMapperFile f = parse("A.xml", """
                <mapper namespace="A">
                    <select id="q">SELECT <include refid="B.cols"><property name="alias" value="${a}"/></include> FROM t</select>
                </mapper>
                """);
        IndexedElement q = byId(f, "q");
        assertEquals(List.of("B.cols"), q.includeRefs);
        assertTrue(names(q).isEmpty());
    }

    public void testIBatis基本() {
        ParsedMapperFile f = parse("Order.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE sqlMap PUBLIC "-//ibatis.apache.org//DTD SQL Map 2.0//EN" "http://ibatis.apache.org/dtd/sql-map-2.dtd">
                <sqlMap namespace="Order">
                    <parameterMap id="pm" class="map">
                        <parameter property="merchantId"/>
                        <parameter property="query.poiId"/>
                    </parameterMap>
                    <sql id="scope">merchant_id = #merchantId#</sql>
                    <select id="queryOrder" parameterClass="map" resultClass="Order">
                        SELECT * FROM orders
                        <dynamic prepend="WHERE">
                            <include refid="scope"/>
                            <isNotNull property="poiId" prepend="AND">poi_id = #poiId:BIGINT#</isNotNull>
                            <isEqual property="status" compareProperty="expected" prepend="AND">status = #status#</isEqual>
                            <iterate property="ids" prepend="AND" open="(" close=")" conjunction=",">#ids[]#</iterate>
                        </dynamic>
                        ORDER BY $orderBy$
                    </select>
                    <update id="upd" parameterMap="pm">UPDATE orders SET a = ? WHERE b = ?</update>
                    <procedure id="proc">{call sp(#a#)}</procedure>
                </sqlMap>
                """);
        assertNotNull(f);
        assertEquals(MapperDialect.IBATIS, f.dialect());
        assertEquals("Order", f.namespace());

        IndexedElement pm = byId(f, "pm");
        assertEquals(IndexedElement.Kind.PARAMETER_MAP, pm.kind);
        assertEquals(List.of("merchantId", "query"), pm.properties);

        IndexedElement q = byId(f, "queryOrder");
        assertEquals(List.of("scope"), q.includeRefs);
        assertEquals(Set.of("poiId", "status", "expected", "ids", "orderBy"), names(q));

        IndexedElement upd = byId(f, "upd");
        assertEquals("pm", upd.parameterMapRef);
        assertEquals(StatementType.UPDATE, upd.type);
        assertTrue(names(upd).isEmpty());

        assertEquals(StatementType.PROCEDURE, byId(f, "proc").type);
        assertEquals(Set.of("a"), names(byId(f, "proc")));
    }

    public void test无id的statement跳过() {
        ParsedMapperFile f = parse("A.xml", "<mapper namespace=\"A\"><select>x</select><select id=\"ok\">y</select></mapper>");
        assertEquals(1, f.elements().size());
        assertEquals("ok", f.elements().get(0).id);
    }
}
