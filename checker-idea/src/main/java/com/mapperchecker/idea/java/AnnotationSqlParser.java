package com.mapperchecker.idea.java;

import com.intellij.lang.xml.XMLLanguage;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiMethod;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementSource;
import com.mapperchecker.core.model.StatementType;
import com.mapperchecker.core.naming.SqlParameterExtractor;
import com.mapperchecker.idea.index.IndexedElement;
import com.mapperchecker.idea.mapper.MapperXmlParser;
import com.mapperchecker.idea.mapper.ParsedMapperFile;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 Mapper 接口方法上的 @Select / @Insert / @Update / @Delete。方案 8.2。
 * <ul>
 *   <li>value 为字符串或字符串数组，逐段常量求值后拼接；求不出的片段跳过并标 partiallyParsed</li>
 *   <li>含 {@code <script>} 时按 XML 动态标签规则解析</li>
 *   <li>Provider 注解不在此处理，由调用方标 UNRESOLVED</li>
 * </ul>
 */
public final class AnnotationSqlParser {

    private AnnotationSqlParser() {
    }

    /** 方法没有 SQL 注解时返回 null。 */
    public static @Nullable MapperStatement parse(@NotNull PsiMethod method) {
        PsiAnnotation annotation = null;
        StatementType type = StatementType.UNKNOWN;
        for (String fqn : MyBatisNames.SQL_ANNOTATIONS) {
            PsiAnnotation a = method.getAnnotation(fqn);
            if (a != null) {
                annotation = a;
                type = switch (fqn) {
                    case MyBatisNames.SELECT -> StatementType.SELECT;
                    case MyBatisNames.INSERT -> StatementType.INSERT;
                    case MyBatisNames.UPDATE -> StatementType.UPDATE;
                    case MyBatisNames.DELETE -> StatementType.DELETE;
                    default -> StatementType.UNKNOWN;
                };
                break;
            }
        }
        if (annotation == null) {
            return null;
        }
        String namespace = MapperInterfaceDetector.namespaceOf(method);
        SourceLocation location = Locations.of(annotation);

        StringBuilder sql = new StringBuilder();
        boolean partial = false;
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value instanceof PsiArrayInitializerMemberValue arr) {
            for (PsiAnnotationMemberValue item : arr.getInitializers()) {
                partial |= !append(sql, item);
                sql.append(' ');
            }
        } else if (value != null) {
            partial |= !append(sql, value);
        } else {
            partial = true;
        }

        String text = sql.toString();
        List<ParameterReference> refs = new ArrayList<>();
        List<String> includes = new ArrayList<>();
        if (text.contains("<script>")) {
            partial |= !parseScript(method, namespace, text, location, refs, includes);
        } else {
            for (SqlParameterExtractor.Match m : SqlParameterExtractor.extractMyBatis(text)) {
                refs.add(ParameterReference.of(m.rootName(), m.path(), ParameterSourceType.ANNOTATION_SQL, location));
            }
        }
        return new MapperStatement(StatementSource.MYBATIS_ANNOTATION, namespace == null ? "" : namespace,
                method.getName(), type, "", refs, includes, "", partial, location, Locations.moduleNameOf(method));
    }

    /** 常量求值并追加；求不出返回 false。 */
    private static boolean append(StringBuilder sb, PsiAnnotationMemberValue v) {
        if (!(v instanceof PsiExpression expr)) {
            return false;
        }
        Object constant = JavaPsiFacade.getInstance(v.getProject()).getConstantEvaluationHelper()
                .computeConstantExpression(expr, false);
        if (constant instanceof String s) {
            sb.append(s);
            return true;
        }
        return false;
    }

    /**
     * 把 {@code <script>...</script>} 包成一个临时 Mapper XML 交给 {@link MapperXmlParser}。
     * 参数位置统一落在注解上。
     */
    private static boolean parseScript(PsiMethod method, String namespace, String text, SourceLocation location,
                                       List<ParameterReference> refs, List<String> includes) {
        int start = text.indexOf("<script>");
        int end = text.lastIndexOf("</script>");
        if (start < 0 || end < 0 || end < start) {
            return false;
        }
        String body = text.substring(start + "<script>".length(), end);
        String xml = "<mapper namespace=\"" + (namespace == null ? "" : namespace) + "\"><select id=\""
                + method.getName() + "\">" + body + "</select></mapper>";
        PsiFile file = PsiFileFactory.getInstance(method.getProject())
                .createFileFromText("annotation.xml", XMLLanguage.INSTANCE, xml);
        ParsedMapperFile parsed = MapperXmlParser.parse(file);
        if (parsed == null || parsed.elements().isEmpty()) {
            return false;
        }
        IndexedElement e = parsed.elements().get(0);
        for (IndexedElement.Param p : e.params) {
            refs.add(ParameterReference.of(p.name(), p.path(), ParameterSourceType.ANNOTATION_SQL, location));
        }
        includes.addAll(e.includeRefs);
        return true;
    }
}
