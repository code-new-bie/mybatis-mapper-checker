package com.mapperchecker.idea.run;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/** "查看调用链"：从一个方法反向找调用者，直到入口点、循环或分支 / 深度上限。 */
public class CallChainFinderTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    private static PsiMethod method(PsiClass c, String name) {
        return c.findMethodsByName(name, false)[0];
    }

    public void test简单链_一路追到入口点() {
        PsiClass c = myFixture.addClass("""
                package com.example;
                public class Svc {
                    public void dao() {}
                    public void service() { dao(); }
                    public void controller() { service(); }
                }
                """);
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "dao"), null);
        String rendered = CallChainFinder.render(root);
        assertTrue(rendered, rendered.contains("Svc.dao()"));
        assertTrue(rendered, rendered.contains("Svc.service()"));
        assertTrue(rendered, rendered.contains("Svc.controller()"));
        // controller() 没有调用者，是入口点
        assertTrue(rendered, rendered.contains("未找到调用者"));
        // 顺序：dao 在最上面，service 缩进一级，controller 缩进两级
        int daoIdx = rendered.indexOf("Svc.dao()");
        int serviceIdx = rendered.indexOf("Svc.service()");
        int controllerIdx = rendered.indexOf("Svc.controller()");
        assertTrue(daoIdx < serviceIdx);
        assertTrue(serviceIdx < controllerIdx);
    }

    public void test循环调用不会死循环() {
        PsiClass c = myFixture.addClass("""
                package com.example;
                public class Svc {
                    public void a() { b(); }
                    public void b() { a(); }
                }
                """);
        // a 的调用者是 b，b 的调用者又是 a——必须在有限步内结束，不能死循环 / 栈溢出
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "a"), null);
        String rendered = CallChainFinder.render(root);
        assertTrue(rendered, rendered.contains("Svc.a()"));
        assertTrue(rendered, rendered.contains("Svc.b()"));
        assertTrue(rendered, rendered.contains("循环调用"));
    }

    public void test单方法自己递归也不死循环() {
        PsiClass c = myFixture.addClass("""
                package com.example;
                public class Svc {
                    public void loop(int n) { if (n > 0) loop(n - 1); }
                }
                """);
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "loop"), null);
        String rendered = CallChainFinder.render(root);
        assertTrue(rendered, rendered.contains("循环调用"));
    }

    public void test多个调用者分叉展示() {
        PsiClass c = myFixture.addClass("""
                package com.example;
                public class Svc {
                    public void dao() {}
                    public void fromA() { dao(); }
                    public void fromB() { dao(); }
                }
                """);
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "dao"), null);
        assertEquals(2, root.callers.size());
        String rendered = CallChainFinder.render(root);
        assertTrue(rendered, rendered.contains("Svc.fromA()"));
        assertTrue(rendered, rendered.contains("Svc.fromB()"));
    }

    public void test没有调用者时根节点直接标未找到() {
        PsiClass c = myFixture.addClass("package com.example; public class Svc { public void entry() {} }");
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "entry"), null);
        assertTrue(root.callers.isEmpty());
        assertTrue(CallChainFinder.render(root).contains("未找到调用者"));
    }

    public void test调用点经接口引用也能找到覆写方法的调用者() {
        // 真机反馈的真实场景：XxxServiceImpl 实现 XxxService，真正的调用点是
        // "@Autowired XxxService svc; svc.method();"——resolve() 落在接口方法上，不落在
        // Impl 的覆写方法本身，strictSignatureSearch 必须是 false 才能找到
        myFixture.addClass("""
                package com.example;
                public interface Svc {
                    void refund();
                }
                """);
        PsiClass impl = myFixture.addClass("""
                package com.example;
                public class SvcImpl implements Svc {
                    public void refund() {}
                }
                """);
        myFixture.addClass("""
                package com.example;
                public class Controller {
                    private Svc svc;
                    public void handle() { svc.refund(); }
                }
                """);
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(impl, "refund"), null);
        assertFalse("不该判定为找不到调用者", root.callers.isEmpty());
        assertEquals(1, root.callers.size());
        assertEquals("handle", root.callers.get(0).method.getName());
        String rendered = CallChainFinder.render(root);
        assertTrue(rendered, rendered.contains("Controller.handle()"));
        // 覆写方法且列出了调用点：应该提示"可能经接口调用"
        assertTrue(rendered, rendered.contains("覆写方法"));
    }

    public void test调用者过多会截断并说明() {
        StringBuilder callers = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            callers.append("public void c").append(i).append("() { dao(); }\n");
        }
        PsiClass c = myFixture.addClass("""
                package com.example;
                public class Svc {
                    public void dao() {}
                    %s
                }
                """.formatted(callers));
        CallChainFinder.Node root = new CallChainFinder(getProject()).build(method(c, "dao"), null);
        // 8 个调用者，每层上限 5 个
        assertEquals(5, root.callers.size());
        assertTrue(root.truncatedCallers);
        assertTrue(CallChainFinder.render(root).contains("还有更多调用者未展开"));
    }
}
