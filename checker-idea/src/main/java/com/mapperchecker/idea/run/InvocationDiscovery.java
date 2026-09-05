package com.mapperchecker.idea.run;

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
import com.mapperchecker.idea.index.MapperNamespaceIndex;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.java.MyBatisNames;
import com.mapperchecker.idea.java.StringCallInvocationExtractor;
import org.jetbrains.annotations.NotNull;

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

    /** 范围内（Module / 整项目）发现。 */
    public @NotNull Found discover(@NotNull GlobalSearchScope scope) {
        Set<PsiClass> interfaces = new LinkedHashSet<>();
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        GlobalSearchScope sourceScope = scope.intersectWith(GlobalSearchScope.projectScope(project));

        // 1. XML namespace → 接口
        for (String ns : MapperNamespaceIndex.allNamespaces(project)) {
            PsiClass cls = facade.findClass(ns, sourceScope);
            if (cls != null && cls.isInterface() && isSource(cls)) {
                interfaces.add(cls);
            }
        }
        // 2. @Mapper
        PsiClass mapperAnno = facade.findClass(MyBatisNames.MAPPER, GlobalSearchScope.allScope(project));
        if (mapperAnno != null) {
            for (PsiClass cls : AnnotatedElementsSearch.searchPsiClasses(mapperAnno, sourceScope).findAll()) {
                if (cls.isInterface() && isSource(cls)) {
                    interfaces.add(cls);
                }
            }
        }
        // 3. 带 SQL / Provider 注解的方法所在接口
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

        // 4. 字符串调用：对目标方法做引用搜索
        List<PsiMethodCallExpression> calls = new ArrayList<>();
        Set<String> receiverTypes = new LinkedHashSet<>(MyBatisNames.SQL_SESSION_TYPES);
        receiverTypes.addAll(MyBatisNames.SQLMAP_CLIENT_TYPES);
        Set<PsiMethodCallExpression> seen = new LinkedHashSet<>();
        for (String type : receiverTypes) {
            PsiClass cls = facade.findClass(type, GlobalSearchScope.allScope(project));
            if (cls == null) {
                continue;
            }
            for (PsiMethod m : cls.getAllMethods()) {
                String name = m.getName();
                if (!MyBatisNames.SQL_SESSION_METHODS.contains(name) && !MyBatisNames.SQLMAP_CLIENT_METHODS.contains(name)) {
                    continue;
                }
                for (PsiReference ref : MethodReferencesSearch.search(m, sourceScope, true).findAll()) {
                    PsiElement e = ref.getElement();
                    PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(e, PsiMethodCallExpression.class, false);
                    if (call != null && seen.add(call) && StringCallInvocationExtractor.extract(call) != null) {
                        calls.add(call);
                    }
                }
            }
        }
        return new Found(new ArrayList<>(interfaces), calls);
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
