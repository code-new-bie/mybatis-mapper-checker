package com.mapperchecker.idea.run;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.index.MapperNamespaceIndex;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.java.MyBatisNames;
import com.mapperchecker.idea.java.StringCallInvocationExtractor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 发现阶段：定位 Mapper 接口与字符串调用点。方案 13.4。
 * 扫描量与 Mapper 数量成正比，而不是遍历所有 Java 文件。
 */
public final class InvocationDiscovery {

    /** 发现结果。 */
    public record Found(List<PsiClass> mapperInterfaces, List<PsiMethodCallExpression> stringCalls) {
    }

    /** 不适合当作文件种子词的方法名：太常见，索引命中等于全项目。 */
    private static final Set<String> GENERIC_CALL_NAMES = Set.of("insert", "update", "delete", "select");

    private final Project project;

    public InvocationDiscovery(@NotNull Project project) {
        this.project = project;
    }

    /** 范围内（Module / 整项目）发现，不带进度反馈（测试 / 兼容旧调用方用）。 */
    public @NotNull Found discover(@NotNull GlobalSearchScope scope) {
        return discover(scope, null);
    }

    /**
     * 范围内（Module / 整项目）发现。这一步在大项目上可能耗时较长（尤其第 4 步的项目级引用搜索），
     * 全程往 indicator 写阶段文字和可取消检查，避免界面上"进度条转圈但看不出在干什么"。
     */
    public @NotNull Found discover(@NotNull GlobalSearchScope scope, @Nullable ProgressIndicator indicator) {
        Set<PsiClass> interfaces = new LinkedHashSet<>();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope sourceScope = scope.intersectWith(GlobalSearchScope.projectScope(project));

        // 1. XML namespace → 接口
        checkCanceledAndSetText(indicator, "task.discovery.namespace");
        for (String ns : MapperNamespaceIndex.allNamespaces(project)) {
            PsiClass cls = facade.findClass(ns, sourceScope);
            if (cls != null && cls.isInterface() && isSource(cls)) {
                interfaces.add(cls);
            }
        }
        // 2. @Mapper
        checkCanceledAndSetText(indicator, "task.discovery.annotation");
        PsiClass mapperAnno = facade.findClass(MyBatisNames.MAPPER, GlobalSearchScope.allScope(project));
        if (mapperAnno != null) {
            for (PsiClass cls : AnnotatedElementsSearch.searchPsiClasses(mapperAnno, sourceScope).findAll()) {
                if (cls.isInterface() && isSource(cls)) {
                    interfaces.add(cls);
                }
            }
        }
        // 3. 带 SQL / Provider 注解的方法所在接口
        checkCanceledAndSetText(indicator, "task.discovery.sqlAnnotation");
        List<String> methodAnnos = new ArrayList<>(MyBatisNames.SQL_ANNOTATIONS);
        methodAnnos.addAll(MyBatisNames.PROVIDER_ANNOTATIONS);
        for (String fqn : methodAnnos) {
            PsiClass anno = facade.findClass(fqn, GlobalSearchScope.allScope(project));
            if (anno == null) {
                continue;
            }
            for (PsiMethod m : AnnotatedElementsSearch.searchPsiMethods(anno, sourceScope).findAll()) {
                PsiClass owner = m.getContainingClass();
                if (owner != null && owner.isInterface() && isSource(owner)) {
                    interfaces.add(owner);
                }
            }
        }

        // 4. 字符串调用：先用词索引把可能的文件筛出来（纯索引查询，不解引用），再在这些文件上按 AST 匹配。
        // 原来是给 SqlSession / SqlSessionTemplate / SqlMapClient(Template) 的每个重载各做一次全项目引用搜索：
        // 四种类型的同名重载加起来几十个，而 update / insert / delete 这种词满项目都是，平台要把每一处都解引用，
        // 是整个扫描最慢的一步。现在同一个词只查一次索引，每个候选文件只解析一次，命中判定仍走 extract。
        Set<String> methodNames = new LinkedHashSet<>();
        Set<String> seedWords = new LinkedHashSet<>();
        Set<String> receiverTypes = new LinkedHashSet<>(MyBatisNames.SQL_SESSION_TYPES);
        receiverTypes.addAll(MyBatisNames.SQLMAP_CLIENT_TYPES);
        for (String type : receiverTypes) {
            PsiClass cls = facade.findClass(type, GlobalSearchScope.allScope(project));
            if (cls == null) {
                continue;
            }
            seedWords.add(type.substring(type.lastIndexOf('.') + 1));
            for (PsiMethod m : cls.getAllMethods()) {
                String name = m.getName();
                if (MyBatisNames.SQL_SESSION_METHODS.contains(name) || MyBatisNames.SQLMAP_CLIENT_METHODS.contains(name)) {
                    methodNames.add(name);
                }
            }
        }
        // insert / update / delete / select 这类词不适合当种子（满项目都是），但候选文件里仍然照常识别：
        // 用到它们的 DAO 几乎都会同时出现 receiver 类型名或某个 selectXxx / queryForXxx
        for (String name : methodNames) {
            if (!GENERIC_CALL_NAMES.contains(name)) {
                seedWords.add(name);
            }
        }
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        if (!methodNames.isEmpty()) {
            PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
            Set<PsiFile> candidates = new LinkedHashSet<>();
            int done = 0;
            for (String word : seedWords) {
                done++;
                if (indicator != null) {
                    indicator.checkCanceled();
                    indicator.setText2(MapperCheckerBundle.message("task.discovery.stringCallWord", done, seedWords.size(), word));
                } else {
                    ProgressManager.checkCanceled();
                }
                helper.processAllFilesWithWord(word, sourceScope, f -> {
                    if (f instanceof PsiJavaFile) {
                        candidates.add(f);
                    }
                    return true;
                }, true);
            }
            int scanned = 0;
            for (PsiFile f : candidates) {
                scanned++;
                if (indicator != null) {
                    indicator.checkCanceled();
                    indicator.setText2(MapperCheckerBundle.message("task.discovery.stringCallScan", scanned, candidates.size(), f.getName()));
                } else {
                    ProgressManager.checkCanceled();
                }
                for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(f, PsiMethodCallExpression.class)) {
                    if (StringCallInvocationExtractor.extract(call) != null) {
                        calls.add(call);
                    }
                }
            }
        }
        if (indicator != null) {
            indicator.setText2("");
        }
        return new Found(new ArrayList<>(interfaces), calls);
    }

    private static void checkCanceledAndSetText(@Nullable ProgressIndicator indicator, String messageKey) {
        if (indicator != null) {
            indicator.checkCanceled();
            indicator.setText(MapperCheckerBundle.message(messageKey));
        } else {
            ProgressManager.checkCanceled();
        }
    }

    /**
     * 纯 Java 规则（DAL-004 / 020 / 030）的候选文件：提到任一 Query 类简单名、或提到 copyProperties 的 Java 文件。
     * 用词索引定位，不遍历全部 Java 文件。
     */
    public @NotNull List<PsiFile> findJavaFilesForRules(@NotNull GlobalSearchScope scope,
                                                        @NotNull com.mapperchecker.idea.java.QueryClassIndex queryClasses,
                                                        @Nullable ProgressIndicator indicator) {
        GlobalSearchScope sourceScope = scope.intersectWith(GlobalSearchScope.projectScope(project));
        // 只搜项目源码里真实存在的 Query 类名：jar 里的 CriteriaQuery / NativeQuery 之类命中后缀但毫无意义，
        // 拿它们做全项目词搜索是纯浪费
        Set<String> words = new LinkedHashSet<>(queryClasses.sourceQueryClasses(scope).keySet());
        words.add("copyProperties");
        Set<PsiFile> files = new LinkedHashSet<>();
        PsiSearchHelper helper = PsiSearchHelper.getInstance(project);
        int done = 0;
        for (String word : words) {
            done++;
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText2(MapperCheckerBundle.message("task.discovery.javaRules", done, words.size(), word));
            } else {
                ProgressManager.checkCanceled();
            }
            helper.processAllFilesWithWord(word, sourceScope, f -> {
                if (f instanceof PsiJavaFile) {
                    files.add(f);
                }
                return true;
            }, true);
        }
        if (indicator != null) {
            indicator.setText2("");
        }
        return new ArrayList<>(files);
    }

    /** 单个文件内发现：直接遍历 PSI。 */
    public @NotNull Found discoverInFile(@NotNull PsiFile file) {
        List<PsiClass> interfaces = new ArrayList<>();
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        if (!(file instanceof PsiJavaFile)) {
            return new Found(interfaces, calls);
        }
        for (PsiClass cls : PsiTreeUtil.findChildrenOfType(file, PsiClass.class)) {
            if (cls.isInterface() && MapperInterfaceDetector.isMapperInterface(cls)) {
                interfaces.add(cls);
            }
        }
        for (PsiMethodCallExpression call : PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)) {
            if (StringCallInvocationExtractor.extract(call) != null) {
                calls.add(call);
            }
        }
        return new Found(interfaces, calls);
    }

    /** 多个文件。 */
    public @NotNull Found discoverInFiles(@NotNull Collection<VirtualFile> files) {
        Set<PsiClass> interfaces = new LinkedHashSet<>();
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        PsiManager pm = PsiManager.getInstance(project);
        for (VirtualFile vf : files) {
            PsiFile psi = pm.findFile(vf);
            if (psi == null) {
                continue;
            }
            Found f = discoverInFile(psi);
            interfaces.addAll(f.mapperInterfaces());
            calls.addAll(f.stringCalls());
        }
        return new Found(new ArrayList<>(interfaces), calls);
    }

    private boolean isSource(PsiClass cls) {
        PsiFile f = cls.getContainingFile();
        VirtualFile vf = f == null ? null : f.getVirtualFile();
        return vf != null && ProjectFileIndex.getInstance(project).isInSourceContent(vf);
    }
}
