package com.mapperchecker.idea.run;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.JavaModuleTestCase;
import com.intellij.testFramework.PsiTestUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Heavy Test：真机反馈的真实场景——调用者跨模块，且是经接口类型引用调用的（典型 Spring
 * ServiceImpl 场景）。单 Module 测试（{@link CallChainFinderTest}）只验证了接口调用点这一层，
 * 这里再验证跨 Module 时 {@code GlobalSearchScope.projectScope} 确实能摸到调用者。
 * <pre>
 * module-service   Svc（接口）+ SvcImpl implements Svc
 * module-web       依赖 service；Controller 持有 Svc 类型字段，经接口调用 SvcImpl 的方法
 * </pre>
 */
public class CallChainFinderHeavyTest extends JavaModuleTestCase {

    private VirtualFile root;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = getTempDir().createVirtualDir("callchain");
        Module service = newModule("module-service");
        Module web = newModule("module-web");
        ModuleRootModificationUtil.addDependency(web, service);

        write("module-service/src/main/java/com/example/service/Svc.java", """
                package com.example.service;
                public interface Svc {
                    void refund();
                }
                """);
        write("module-service/src/main/java/com/example/service/SvcImpl.java", """
                package com.example.service;
                public class SvcImpl implements Svc {
                    public void refund() {}
                }
                """);
        write("module-web/src/main/java/com/example/web/Controller.java", """
                package com.example.web;
                import com.example.service.Svc;
                public class Controller {
                    private Svc svc;
                    public void handle() { svc.refund(); }
                }
                """);

        for (Module m : List.of(service, web)) {
            VirtualFile mDir = root.findChild(m.getName());
            VirtualFile java = WriteAction.computeAndWait(() -> VfsUtil.createDirectoryIfMissing(mDir, "src/main/java"));
            PsiTestUtil.addSourceRoot(m, java);
        }
        PsiManagerEx.getInstanceEx(getProject()).dropPsiCaches();
    }

    private Module newModule(String name) throws IOException {
        VirtualFile dir = WriteAction.computeAndWait(() -> root.createChildDirectory(this, name));
        Module m = createModuleAt(name, getProject(), getModuleType(), dir.toNioPath());
        PsiTestUtil.addContentRoot(m, dir);
        ModuleRootModificationUtil.setModuleSdk(m, getTestProjectJdk());
        return m;
    }

    private void write(String relative, String content) throws IOException {
        WriteAction.runAndWait(() -> {
            int slash = relative.lastIndexOf('/');
            VirtualFile dir = VfsUtil.createDirectoryIfMissing(root, relative.substring(0, slash));
            VirtualFile f = dir.createChildData(this, relative.substring(slash + 1));
            f.setBinaryContent(content.getBytes(StandardCharsets.UTF_8));
        });
    }

    public void test跨模块经接口调用的实现方法也能找到调用者() {
        PsiClass impl = JavaPsiFacade.getInstance(getProject())
                .findClass("com.example.service.SvcImpl", GlobalSearchScope.projectScope(getProject()));
        assertNotNull(impl);
        PsiMethod refund = impl.findMethodsByName("refund", false)[0];

        CallChainFinder.Node root = new CallChainFinder(getProject()).build(refund, null);
        assertFalse("跨模块的调用者不该被漏掉", root.callers.isEmpty());
        assertEquals("Controller.handle()", describeFirstCaller(root));
    }

    private static String describeFirstCaller(CallChainFinder.Node root) {
        PsiMethod m = root.callers.get(0).method;
        return m.getContainingClass().getName() + "." + m.getName() + "()";
    }
}
