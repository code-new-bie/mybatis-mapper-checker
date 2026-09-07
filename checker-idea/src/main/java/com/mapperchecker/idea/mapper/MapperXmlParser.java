package com.mapperchecker.idea.mapper;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlTagChild;
import com.intellij.psi.xml.XmlText;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.StatementType;
import com.mapperchecker.core.naming.OgnlIdentifierExtractor;
import com.mapperchecker.core.contract.ParameterTemplate;
import com.mapperchecker.core.model.IncludeRef;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.core.naming.SqlParameterExtractor;
import com.mapperchecker.idea.index.IndexedElement;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mapper XML 解析器。只依赖当前文件内容，供 FileBasedIndex 与直接解析共用。方案 8.1 / 8.3 / 8.5。
 * <p>
 * 只产出"单文件事实"：直接参数引用、include 引用、parameterMap 引用。跨文件展开在 {@link MapperRepository}。
 */
public final class MapperXmlParser {

    private MapperXmlParser() {
    }

    /** 不是 Mapper 文件返回 null。 */
    public static @Nullable ParsedMapperFile parse(PsiFile file) {
        if (!(file instanceof XmlFile xmlFile)) {
            return null;
        }
        XmlDocument document = xmlFile.getDocument();
        if (document == null) {
            return null;
        }
        XmlTag root = document.getRootTag();
        if (root == null) {
            return null;
        }
        MapperDialect dialect = MapperDialect.fromRootTag(root.getName());
        if (dialect == null) {
            return null;
        }
        String namespace = attr(root, "namespace");
        List<IndexedElement> elements = new ArrayList<>();
        for (XmlTag tag : root.getSubTags()) {
            String tagName = tag.getName();
            if ("parameterMap".equals(tagName)) {
                IndexedElement pm = parseParameterMap(dialect, namespace, tag);
                if (pm != null) {
                    elements.add(pm);
                }
                continue;
            }
            StatementType type = StatementType.fromTagName(tagName);
            if (type == null) {
                continue;
            }
            String id = attr(tag, "id");
            if (id.isEmpty()) {
                continue;
            }
            Collector collector = new Collector(dialect);
            collector.walk(tag, new HashSet<>());
            TextRange range = tag.getTextRange();
            elements.add(new IndexedElement(
                    type == StatementType.SQL_FRAGMENT ? IndexedElement.Kind.FRAGMENT : IndexedElement.Kind.STATEMENT,
                    dialect.source(), namespace, id, type,
                    attr(tag, "databaseId"),
                    attr(tag, "parameterMap"),
                    collector.includeRefs, collector.params, List.of(), collector.templates,
                    range.getStartOffset(), range.getEndOffset()));
        }
        return new ParsedMapperFile(dialect, namespace, elements);
    }

    private static @Nullable IndexedElement parseParameterMap(MapperDialect dialect, String namespace, XmlTag tag) {
        String id = attr(tag, "id");
        if (id.isEmpty()) {
            return null;
        }
        List<String> props = new ArrayList<>();
        for (XmlTag p : tag.findSubTags("parameter")) {
            String prop = attr(p, "property");
            String root = ParameterNameNormalizer.rootName(prop);
            if (root != null && ParameterNameNormalizer.isIdentifier(root)) {
                props.add(root);
            }
        }
        TextRange range = tag.getTextRange();
        return new IndexedElement(IndexedElement.Kind.PARAMETER_MAP, dialect.source(), namespace, id,
                StatementType.UNKNOWN, "", "", List.of(), List.of(), props,
                range.getStartOffset(), range.getEndOffset());
    }

    static String attr(XmlTag tag, String name) {
        String v = tag.getAttributeValue(name);
        return v == null ? "" : v.trim();
    }

    /** 遍历一个 statement / 片段子树，收集参数引用与 include。 */
    private static final class Collector {
        final MapperDialect dialect;
        final List<IndexedElement.Param> params = new ArrayList<>();
        final List<String> includeRefs = new ArrayList<>();
        /** 参数名里嵌了 ${} 的原文，等 include 的 property 替换后再提取。 */
        final List<String> templates = new ArrayList<>();
        /** bind 定义的名字对整个 statement 生效（简化处理）。 */
        final Set<String> bindNames = new LinkedHashSet<>();

        Collector(MapperDialect dialect) {
            this.dialect = dialect;
        }

        void walk(XmlTag tag, Set<String> locals) {
            for (XmlTagChild child : tag.getValue().getChildren()) {
                if (child instanceof XmlText text) {
                    collectText(text, locals);
                } else if (child instanceof XmlTag sub) {
                    walkTag(sub, locals);
                }
            }
        }

        private void walkTag(XmlTag tag, Set<String> inheritedLocals) {
            String name = tag.getName();
            Set<String> locals = inheritedLocals;
            switch (name) {
                case "include" -> {
                    String refid = attr(tag, "refid");
                    if (!refid.isEmpty()) {
                        // <property> 是给片段里 ${} 用的替换值，跟着 refid 一起存，展开时才用得上
                        Map<String, String> props = new LinkedHashMap<>();
                        for (XmlTag p : tag.findSubTags("property")) {
                            String pn = attr(p, "name");
                            if (!pn.isEmpty()) {
                                props.put(pn, attr(p, "value"));
                            }
                        }
                        includeRefs.add(IncludeRef.encode(refid, props));
                    }
                    // <property> 本身不是参数引用，不递归
                    return;
                }
                case "foreach" -> {
                    addAttrParam(tag, "collection", ParameterSourceType.XML_DYNAMIC_ATTR, locals);
                    locals = new HashSet<>(inheritedLocals);
                    addLocal(locals, attr(tag, "item"));
                    addLocal(locals, attr(tag, "index"));
                }
                case "bind" -> {
                    String bindName = attr(tag, "name");
                    if (!bindName.isEmpty()) {
                        bindNames.add(bindName);
                    }
                    addOgnlParams(tag, "value", locals);
                    return;
                }
                case "if", "when" -> addOgnlParams(tag, "test", locals);
                case "iterate" -> {
                    // iBatis 2：property 是集合参数；体内 #x[]# 由文本提取处理
                    addAttrParam(tag, "property", ParameterSourceType.XML_DYNAMIC_ATTR, locals);
                }
                default -> {
                    if (dialect == MapperDialect.IBATIS) {
                        // isNotNull / isEqual / isEmpty ... 所有带 property / compareProperty 的动态标签
                        addAttrParam(tag, "property", ParameterSourceType.XML_DYNAMIC_ATTR, locals);
                        addAttrParam(tag, "compareProperty", ParameterSourceType.XML_DYNAMIC_ATTR, locals);
                    }
                }
            }
            walk(tag, locals);
        }

        private void collectText(XmlText text, Set<String> locals) {
            String value = text.getValue();
            if (value == null || value.isEmpty()) {
                return;
            }
            // #{${prefix}poiId} 这种：参数名要等 include 的 property 替换后才知道，先存原文
            if (dialect == MapperDialect.MYBATIS && ParameterTemplate.hasPlaceholderInsideParam(value)) {
                templates.add(ParameterTemplate.encode(ParameterTemplate.TEXT, value));
                return;
            }
            List<SqlParameterExtractor.Match> matches = dialect == MapperDialect.MYBATIS
                    ? SqlParameterExtractor.extractMyBatis(value)
                    : SqlParameterExtractor.extractIBatis(value);
            if (matches.isEmpty()) {
                return;
            }
            int base = text.getTextRange().getStartOffset();
            for (SqlParameterExtractor.Match m : matches) {
                // foreach item/index、bind name 是局部名，不计入
                if (bindNames.contains(m.rootName()) || locals.contains(m.rootName())) {
                    continue;
                }
                int start = base + safeDisplayToPhysical(text, m.offset());
                int end = base + safeDisplayToPhysical(text, m.offset() + m.length());
                params.add(new IndexedElement.Param(m.rootName(), m.path(),
                        m.substitution() ? ParameterSourceType.XML_SUBSTITUTION : ParameterSourceType.XML_INLINE,
                        start, Math.max(end, start + 1)));
            }
        }

        private static int safeDisplayToPhysical(XmlText text, int displayOffset) {
            try {
                return text.displayToPhysical(displayOffset);
            } catch (RuntimeException e) {
                return Math.min(displayOffset, text.getTextLength());
            }
        }

        private void addAttrParam(XmlTag tag, String attrName, ParameterSourceType type, Set<String> locals) {
            XmlAttribute a = tag.getAttribute(attrName);
            if (a == null || a.getValue() == null) {
                return;
            }
            String root = ParameterNameNormalizer.rootName(a.getValue());
            if (root == null || !ParameterNameNormalizer.isIdentifier(root) || locals.contains(root) || bindNames.contains(root)) {
                return;
            }
            String path = ParameterNameNormalizer.normalizePath(a.getValue());
            TextRange r = absoluteValueRange(a);
            params.add(new IndexedElement.Param(root, path == null ? root : path, type, r.getStartOffset(), r.getEndOffset()));
        }

        /** 属性值在文件中的绝对范围（不含引号）。 */
        private static TextRange absoluteValueRange(XmlAttribute a) {
            XmlAttributeValue ve = a.getValueElement();
            if (ve != null) {
                return ve.getValueTextRange();
            }
            return a.getTextRange();
        }

        private void addOgnlParams(XmlTag tag, String attrName, Set<String> locals) {
            XmlAttribute a = tag.getAttribute(attrName);
            if (a == null || a.getValue() == null) {
                return;
            }
            // <if test="${prefix}poiId != null">：同上，替换后才能当 OGNL 解析
            if (dialect == MapperDialect.MYBATIS && a.getValue().contains("${")) {
                templates.add(ParameterTemplate.encode(ParameterTemplate.TEST, a.getValue()));
                return;
            }
            Set<String> excluded = new HashSet<>(locals);
            excluded.addAll(bindNames);
            TextRange r = absoluteValueRange(a);
            for (String path : OgnlIdentifierExtractor.extractPaths(a.getValue(), excluded)) {
                String root = ParameterNameNormalizer.rootName(path);
                if (root == null) {
                    continue;
                }
                params.add(new IndexedElement.Param(root, path, ParameterSourceType.XML_TEST_EXPR,
                        r.getStartOffset(), r.getEndOffset()));
            }
        }

        private static void addLocal(Set<String> locals, String name) {
            if (!name.isEmpty()) {
                locals.add(name);
            }
        }
    }
}
