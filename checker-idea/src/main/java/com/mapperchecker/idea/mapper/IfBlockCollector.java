package com.mapperchecker.idea.mapper;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagChild;
import com.intellij.psi.xml.XmlText;
import com.mapperchecker.core.naming.OgnlIdentifierExtractor;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.core.naming.SqlParameterExtractor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从一条 statement 的 XML 标签里收集条件块：每个 {@code <if test>} / {@code <when test>}（MyBatis）
 * 或带 {@code property=} 的动态标签（iBatis 2）判断了哪些路径、块内绑定了哪些路径。供 DAL-003 使用。
 * <p>
 * Index 里的参数是拍平的，不保留嵌套关系，所以这里在检查时直接回到 XML PSI 上走一遍。
 */
public final class IfBlockCollector {

    /**
     * 一个条件块。
     *
     * @param tagName    if / when / isNotNull ...
     * @param condition  test 表达式或 property 值原文
     * @param testPaths  判断的参数路径
     * @param boundPaths 块内 #{} / ${} / #x# 绑定的参数路径（含嵌套标签里的）
     * @param range      标签在文件中的范围
     */
    public record IfBlock(String tagName, String condition, Set<String> testPaths, Set<String> boundPaths, TextRange range) {
    }

    private static final Set<String> IBATIS_CONDITION_TAGS = Set.of(
            "isNotNull", "isNull", "isNotEmpty", "isEmpty", "isEqual", "isNotEqual",
            "isGreaterThan", "isGreaterEqual", "isLessThan", "isLessEqual", "isPropertyAvailable", "isNotPropertyAvailable");

    private IfBlockCollector() {
    }

    public static @NotNull List<IfBlock> collect(@NotNull XmlTag statementTag) {
        XmlFile file = (XmlFile) statementTag.getContainingFile();
        XmlTag root = file.getDocument() == null ? null : file.getDocument().getRootTag();
        MapperDialect dialect = root == null ? MapperDialect.MYBATIS : MapperDialect.fromRootTag(root.getName());
        if (dialect == null) {
            dialect = MapperDialect.MYBATIS;
        }
        List<IfBlock> out = new ArrayList<>();
        walk(statementTag, dialect, out);
        return out;
    }

    private static void walk(XmlTag tag, MapperDialect dialect, List<IfBlock> out) {
        for (XmlTag sub : tag.getSubTags()) {
            IfBlock block = toBlock(sub, dialect);
            if (block != null) {
                out.add(block);
            }
            walk(sub, dialect, out);
        }
    }

    private static IfBlock toBlock(XmlTag tag, MapperDialect dialect) {
        String name = tag.getName();
        Set<String> testPaths = new LinkedHashSet<>();
        String condition;
        if (dialect == MapperDialect.MYBATIS) {
            if (!"if".equals(name) && !"when".equals(name)) {
                return null;
            }
            XmlAttribute test = tag.getAttribute("test");
            if (test == null || test.getValue() == null) {
                return null;
            }
            condition = test.getValue();
            testPaths.addAll(OgnlIdentifierExtractor.extractPaths(condition, Set.of()));
        } else {
            if (!IBATIS_CONDITION_TAGS.contains(name)) {
                return null;
            }
            XmlAttribute prop = tag.getAttribute("property");
            if (prop == null || prop.getValue() == null) {
                return null;
            }
            condition = prop.getValue();
            String p = ParameterNameNormalizer.normalizePath(condition);
            if (p != null) {
                testPaths.add(p);
            }
            XmlAttribute cmp = tag.getAttribute("compareProperty");
            if (cmp != null && cmp.getValue() != null) {
                String c = ParameterNameNormalizer.normalizePath(cmp.getValue());
                if (c != null) {
                    testPaths.add(c);
                }
            }
        }
        if (testPaths.isEmpty()) {
            return null;
        }
        Set<String> bound = new LinkedHashSet<>();
        collectBound(tag, dialect, bound, new LinkedHashSet<>());
        return new IfBlock(name, condition, testPaths, bound, tag.getTextRange());
    }

    /**
     * 收集块内绑定的路径。foreach 的 item / index、bind 的 name 是局部名，不算绑定；
     * foreach 的 collection、bind 的 value 里引用的路径才是真正绑定的参数。
     */
    private static void collectBound(XmlTag tag, MapperDialect dialect, Set<String> bound, Set<String> locals) {
        for (XmlTagChild child : tag.getValue().getChildren()) {
            if (child instanceof XmlText text) {
                String value = text.getValue();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                List<SqlParameterExtractor.Match> matches = dialect == MapperDialect.MYBATIS
                        ? SqlParameterExtractor.extractMyBatis(value) : SqlParameterExtractor.extractIBatis(value);
                for (SqlParameterExtractor.Match m : matches) {
                    if (!locals.contains(m.rootName())) {
                        bound.add(m.path());
                    }
                }
            } else if (child instanceof XmlTag sub) {
                String subName = sub.getName();
                if ("include".equals(subName)) {
                    continue;
                }
                Set<String> innerLocals = locals;
                if ("foreach".equals(subName)) {
                    addPath(bound, sub.getAttributeValue("collection"), locals);
                    innerLocals = new LinkedHashSet<>(locals);
                    addLocal(innerLocals, sub.getAttributeValue("item"));
                    addLocal(innerLocals, sub.getAttributeValue("index"));
                } else if ("bind".equals(subName)) {
                    String value = sub.getAttributeValue("value");
                    if (value != null) {
                        for (String p : OgnlIdentifierExtractor.extractPaths(value, locals)) {
                            bound.add(p);
                        }
                    }
                    innerLocals = new LinkedHashSet<>(locals);
                    addLocal(innerLocals, sub.getAttributeValue("name"));
                    // bind 定义的名字对后续兄弟节点也生效：直接写回当前层
                    locals = innerLocals;
                    continue;
                } else if ("iterate".equals(subName)) {
                    addPath(bound, sub.getAttributeValue("property"), locals);
                }
                collectBound(sub, dialect, bound, innerLocals);
            }
        }
    }

    private static void addPath(Set<String> bound, String raw, Set<String> locals) {
        String p = ParameterNameNormalizer.normalizePath(raw);
        String root = p == null ? null : ParameterNameNormalizer.rootName(p);
        if (root != null && !locals.contains(root)) {
            bound.add(p);
        }
    }

    private static void addLocal(Set<String> locals, String name) {
        if (name != null && !name.isBlank()) {
            locals.add(name.trim());
        }
    }
}
