package com.mapperchecker.idea.mapper;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.naming.NameSimilarity;
import com.mapperchecker.core.naming.ParameterNameNormalizer;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.java.BeanPropertyCollector;
import com.mapperchecker.idea.java.JavaRules;
import com.mapperchecker.idea.run.CheckRunContext;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mapper 侧的规范规则。
 * <ul>
 *   <li>DAL-002 改字段名未同步 XML：Mapper 引用了实体类里不存在的属性。只对参数类型是项目实体类的语句生效，
 *       Map 参数没有属性列表，不查。能在实体里找到近似名的，备注"多为改名漏改"。</li>
 *   <li>DAL-003 标准模板语句里 {@code <if test="a">} 块内绑定的却是 b。只查设置里列出的模板 id。</li>
 * </ul>
 */
public final class MapperSideRules {

    private final Project project;
    private final CheckSettings settings;
    private final JavaRules.Reporter reporter;
    /** 同一 statement 只做一次 DAL-003（多个调用点会重复命中同一条语句）。 */
    private final Set<String> templateChecked = new HashSet<>();

    public MapperSideRules(@NotNull Project project, @NotNull CheckSettings settings, @NotNull JavaRules.Reporter reporter) {
        this.project = project;
        this.settings = settings;
        this.reporter = reporter;
    }

    // ---------------------------------------------------------------- DAL-002

    /**
     * @param bean      参数实体类
     * @param prefix    @Param 值（Mapper 里写 prefix.prop），单 Bean 无 @Param 时为 null
     * @param statement 已解析的 statement（含 include 展开后的全部引用与位置）
     */
    public void checkMissingProperties(@NotNull PsiClass bean, @Nullable String prefix,
                                       @NotNull String statementId, @NotNull ResolvedStatement statement) {
        if (!settings.isEnabled(RuleId.DAL_002)) {
            return;
        }
        Map<String, PsiElement> props = BeanPropertyCollector.collect(bean);
        if (props.isEmpty()) {
            return;
        }
        String entity = bean.getName() == null ? "" : bean.getName();
        Set<String> reported = new HashSet<>();
        for (ParameterReference ref : statement.parameters()) {
            String path = ref.propertyPath();
            if (prefix != null) {
                if (!ParameterNameNormalizer.pathCovers(path, prefix) || path.equals(prefix)) {
                    continue;
                }
                path = path.substring(prefix.length() + 1);
            }
            String missing = firstMissingSegment(bean, props, path);
            if (missing == null || !reported.add(missing)) {
                continue;
            }
            String shownPath = prefix == null ? missing : prefix + "." + missing;
            String message = MapperCheckerBundle.message("issue.DAL-002", shortStatement(statementId), entity, shownPath);
            // 近似名：只对最后一段找
            String lastSeg = missing.substring(missing.lastIndexOf('.') + 1);
            Map<String, PsiElement> ownerProps = propsOfOwner(bean, props, missing);
            String similar = ownerProps == null ? null : NameSimilarity.closest(lastSeg, ownerProps.keySet());
            String remark = similar == null ? "" : MapperCheckerBundle.message("remark.DAL-002.similar", similar);
            ContractIssue issue = new ContractIssue(RuleId.DAL_002, settings.severityOf(RuleId.DAL_002), Confidence.HIGH,
                    message, remark, shownPath, statementId, List.of(), ref.location(), statement.primaryLocation(), List.of());
            reporter.accept(issue, elementAt(ref.location()));
        }
    }

    /**
     * 沿 a.b.c 逐段核对实体属性；返回第一段不存在的路径（如 a.b），全部存在返回 null。
     * 段的类型不是项目实体（库类型、Map、集合）时停止核对。
     */
    private static @Nullable String firstMissingSegment(PsiClass bean, Map<String, PsiElement> props, String path) {
        String[] segs = path.split("\\.");
        PsiClass owner = bean;
        Map<String, PsiElement> ownerProps = props;
        StringBuilder walked = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            if (walked.length() > 0) {
                walked.append('.');
            }
            walked.append(seg);
            if (!ownerProps.containsKey(seg)) {
                return walked.toString();
            }
            if (i == segs.length - 1) {
                return null;
            }
            PsiClass next = BeanPropertyCollector.expandableBeanClass(propertyType(ownerProps.get(seg)));
            if (next == null || next == owner) {
                return null; // 库类型 / Map / 集合 / 自引用：不再往下核对
            }
            owner = next;
            ownerProps = BeanPropertyCollector.collect(next);
        }
        return null;
    }

    /** 缺失路径所属那一层实体的属性表（用于找近似名）。 */
    private static @Nullable Map<String, PsiElement> propsOfOwner(PsiClass bean, Map<String, PsiElement> props, String missing) {
        String[] segs = missing.split("\\.");
        Map<String, PsiElement> current = props;
        for (int i = 0; i < segs.length - 1; i++) {
            PsiElement anchor = current.get(segs[i]);
            PsiClass next = anchor == null ? null : BeanPropertyCollector.expandableBeanClass(propertyType(anchor));
            if (next == null) {
                return null;
            }
            current = BeanPropertyCollector.collect(next);
        }
        return current;
    }

    private static @Nullable PsiType propertyType(@Nullable PsiElement anchor) {
        if (anchor instanceof PsiField f) {
            return f.getType();
        }
        PsiMethod m = anchor instanceof PsiMethod pm ? pm : PsiTreeUtil.getParentOfType(anchor, PsiMethod.class, false);
        if (m == null) {
            return null;
        }
        if (m.getParameterList().getParametersCount() == 1) {
            return m.getParameterList().getParameters()[0].getType();
        }
        return m.getReturnType();
    }

    // ---------------------------------------------------------------- DAL-003

    /** 模板语句里每个条件块：判断的路径与块内绑定的路径必须有交集。 */
    public void checkTemplateBinding(@NotNull String statementId, @NotNull ResolvedStatement statement) {
        if (!settings.isEnabled(RuleId.DAL_003) || !templateChecked.add(statementId)) {
            return;
        }
        String shortId = statementId.substring(statementId.lastIndexOf('.') + 1);
        if (!settings.rules().isTemplateStatement(shortId)) {
            return;
        }
        for (SourceLocation def : statement.definitions()) {
            PsiElement at = elementAt(def);
            XmlTag tag = at == null ? null : PsiTreeUtil.getParentOfType(at, XmlTag.class, false);
            if (tag == null) {
                continue;
            }
            for (IfBlockCollector.IfBlock block : IfBlockCollector.collect(tag)) {
                if (block.boundPaths().isEmpty() || intersects(block.testPaths(), block.boundPaths())) {
                    continue;
                }
                String message = MapperCheckerBundle.message("issue.DAL-003", shortStatement(statementId),
                        block.tagName(), block.condition(), String.join(", ", block.testPaths()),
                        String.join(", ", block.boundPaths()));
                SourceLocation loc = SourceLocation.of(def.filePath(), block.range().getStartOffset(),
                        block.range().getEndOffset(), -1);
                PsiElement anchor = elementAt(loc);
                ContractIssue issue = new ContractIssue(RuleId.DAL_003, settings.severityOf(RuleId.DAL_003), Confidence.HIGH,
                        message, "", String.join(",", block.boundPaths()), statementId, List.of(),
                        Locations.withLine(loc, fileOf(def)), def, List.of());
                reporter.accept(issue, anchor);
            }
        }
    }

    /** 任一绑定路径与任一判断路径相同、或互为前缀，即视为一致。 */
    private static boolean intersects(Set<String> testPaths, Set<String> boundPaths) {
        for (String b : boundPaths) {
            for (String t : testPaths) {
                if (ParameterNameNormalizer.pathCovers(b, t) || ParameterNameNormalizer.pathCovers(t, b)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 工具

    private @Nullable VirtualFile fileOf(SourceLocation loc) {
        return loc.isKnown() ? CheckRunContext.findFileForNavigation(loc.filePath()) : null;
    }

    private @Nullable PsiElement elementAt(SourceLocation loc) {
        VirtualFile vf = fileOf(loc);
        PsiFile file = vf == null ? null : PsiManager.getInstance(project).findFile(vf);
        if (file == null) {
            return null;
        }
        PsiElement at = file.findElementAt(Math.min(Math.max(0, loc.startOffset()), Math.max(0, file.getTextLength() - 1)));
        if (at == null) {
            return file;
        }
        XmlTag tag = PsiTreeUtil.getParentOfType(at, XmlTag.class, false);
        // 内联引用位于文本里，取最近的标签；条件块位置本身就是标签
        return tag != null && tag.getTextRange().getStartOffset() == loc.startOffset() ? tag : at;
    }

    private static String shortStatement(String fullId) {
        int dot = fullId.lastIndexOf('.');
        if (dot <= 0) {
            return fullId;
        }
        int prev = fullId.lastIndexOf('.', dot - 1);
        return prev < 0 ? fullId : fullId.substring(prev + 1);
    }
}
