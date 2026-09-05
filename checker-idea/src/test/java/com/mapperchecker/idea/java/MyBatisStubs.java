package com.mapperchecker.idea.java;

import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;

/** 测试用 MyBatis / iBatis / Guava 桩类。 */
public final class MyBatisStubs {

    private MyBatisStubs() {
    }

    public static void addAll(JavaCodeInsightTestFixture fixture) {
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Mapper {}");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Select { String[] value(); }");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Insert { String[] value(); }");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Update { String[] value(); }");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface Delete { String[] value(); }");
        fixture.addClass("package org.apache.ibatis.annotations; public @interface SelectProvider { Class<?> type(); String method(); }");
        fixture.addClass("package org.apache.ibatis.session; public class RowBounds {}");
        fixture.addClass("package org.apache.ibatis.session; public interface ResultHandler<T> {}");
        fixture.addClass("""
                package org.apache.ibatis.session;
                import java.util.*;
                public interface SqlSession {
                    <E> List<E> selectList(String statement);
                    <E> List<E> selectList(String statement, Object parameter);
                    <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds);
                    <T> T selectOne(String statement, Object parameter);
                    int insert(String statement, Object parameter);
                    int update(String statement, Object parameter);
                    int delete(String statement, Object parameter);
                }
                """);
        fixture.addClass("package org.mybatis.spring; public class SqlSessionTemplate implements org.apache.ibatis.session.SqlSession {"
                + " public <E> java.util.List<E> selectList(String s) { return null; }"
                + " public <E> java.util.List<E> selectList(String s, Object p) { return null; }"
                + " public <E> java.util.List<E> selectList(String s, Object p, org.apache.ibatis.session.RowBounds r) { return null; }"
                + " public <T> T selectOne(String s, Object p) { return null; }"
                + " public int insert(String s, Object p) { return 0; }"
                + " public int update(String s, Object p) { return 0; }"
                + " public int delete(String s, Object p) { return 0; } }");
        fixture.addClass("""
                package com.ibatis.sqlmap.client;
                import java.util.*;
                public interface SqlMapClient {
                    List queryForList(String id, Object parameterObject);
                    Object queryForObject(String id, Object parameterObject);
                    Object insert(String id, Object parameterObject);
                    int update(String id, Object parameterObject);
                    int delete(String id, Object parameterObject);
                }
                """);
        fixture.addClass("""
                package org.springframework.orm.ibatis;
                import java.util.*;
                public class SqlMapClientTemplate {
                    public List queryForList(String id, Object parameterObject) { return null; }
                    public Object queryForObject(String id, Object parameterObject) { return null; }
                    public int update(String id, Object parameterObject) { return 0; }
                }
                """);
        fixture.addClass("""
                package com.google.common.collect;
                import java.util.*;
                public class ImmutableMap<K, V> implements Map<K, V> {
                    public static <K, V> ImmutableMap<K, V> of(K k1, V v1) { return null; }
                    public static <K, V> ImmutableMap<K, V> of(K k1, V v1, K k2, V v2) { return null; }
                    public static <K, V> Builder<K, V> builder() { return null; }
                    public static class Builder<K, V> {
                        public Builder<K, V> put(K k, V v) { return this; }
                        public Builder<K, V> putAll(Map<? extends K, ? extends V> m) { return this; }
                        public ImmutableMap<K, V> build() { return null; }
                    }
                    public int size() { return 0; } public boolean isEmpty() { return true; }
                    public boolean containsKey(Object k) { return false; } public boolean containsValue(Object v) { return false; }
                    public V get(Object k) { return null; } public V put(K k, V v) { return null; } public V remove(Object k) { return null; }
                    public void putAll(Map<? extends K, ? extends V> m) {} public void clear() {}
                    public Set<K> keySet() { return null; } public Collection<V> values() { return null; }
                    public Set<Map.Entry<K, V>> entrySet() { return null; }
                }
                """);
        fixture.addClass("""
                package com.google.common.collect;
                import java.util.*;
                public class Maps { public static <K, V> HashMap<K, V> newHashMap() { return new HashMap<>(); } }
                """);
    }
}
