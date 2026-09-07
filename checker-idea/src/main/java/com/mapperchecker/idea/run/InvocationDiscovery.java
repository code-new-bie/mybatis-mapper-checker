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
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.MethodReferencesSearch;
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

        // 4. 字符串调用：对目标方法做引用搜索。这是整个发现阶段最慢的一步（项目级引用搜索，
        // 逐个方法做），先收集齐目标方法算出总数，再按方法逐个报进度，让用户看到具体搜到哪个方法了。
        List<PsiMethod> targetMethods = new ArrayList<>();
        Set<String> receiverTypes = new LinkedHashSet<>(MyBatisNames.SQL_SESSION_TYPES);
        receiverTypes.addAll(MyBatisNames.SQLMAP_CLIENT_TYPES);
        for (String type : receiverTypes) {
            PsiClass cls = facade.findClass(type, GlobalSearchScope.allScope(project));
            if (cls == null) {
                continue;
            }
            for (PsiMethod m : cls.getAllMethods()) {
                String name = m.getName();
                if (MyBatisNames.SQL_SESSION_METHODS.contains(name) || MyBatisNames.SQLMAP_CLIENT_METHODS.contains(name)) {
                    targetMethods.add(m);
                }
            }
        }
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        Set<PsiMethodCallExpression> seen = new LinkedHashSet<>();
        int total = targetMethods.size();
        int done = 0;
        for (PsiMethod m : targetMethods) {
            done++;
            if (indicator != null) {
                indicator.checkCanceled();
                indicator.setText2(MapperCheckerBundle.message("task.discovery.methodRef", done, total, describe(m)));
            } else {
                ProgressManager.checkCanceled();
            }
            for (PsiReference ref : MethodReferencesSearch.search(m, sourceScope, true).findAll()) {
                PsiElement e = ref.getElement();
                PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(e, PsiMethodCallExpression.class, false);
                if (call != null && seen.add(call) && StringCallInvocationExtractor.extract(call) != null) {
                    calls.add(call);
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

    private static String describe(PsiMethod m) {
        PsiClass owner = m.getContainingClass();
        return (owner == null || owner.getName() == null ? "" : owner.getName() + ".") + m.getName();
    }

    /**
     * 纯 Java 规则（DAL-004 / 020 / 030）的候选文件：提到任一 Query 类简单名、或提到 copyProperties 的 Java 文件。
     * 用词索引定位，不遍历全部 Java 文件。
     */
    public @NotNull List<PsiFile> findJavaFilesForRules(@NotNull GlobalSearchScope scope,
                                                        @NotNull com.mapperchecker.core.contract.RuleOptions rules,
                                                        @Nullable ProgressIndicator indicator) {
        GlobalSearchScope sourceScope = scope.intersectWith(GlobalSearchScope.projectScope(project));
        Set<String> words = new LinkedHashSet<>();
        for (String name : com.intellij.psi.search.PsiShortNamesCache.getInstance(project).getAllClassNames()) {
            if (rules.isQueryClassName(name)) {
                words.add(name);
            }
        }
        words.add("copyProperties");
        Set<PsiFile> files = new LinkedHashSet<>();
        com.intellij.psi.search.PsiSearchHelper helper = com.intellij.psi.search.PsiSearchHelper.getInstance(project);
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
