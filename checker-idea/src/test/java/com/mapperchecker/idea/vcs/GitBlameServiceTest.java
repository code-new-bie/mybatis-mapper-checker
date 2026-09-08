package com.mapperchecker.idea.vcs;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/**
 * "谁提交的"分析的安全网：光测试夹具里的文件不在任何版本控制之下，
 * 必须老老实实返回 null，绝不能抛异常把报告窗口炸掉。
 */
public class GitBlameServiceTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    public void test不在版本控制下时优雅返回null不抛异常() {
        var file = myFixture.addClass("package com.example; public class Foo { public void bar() {} }");
        VirtualFile vf = file.getContainingFile().getVirtualFile();
        GitBlameService service = GitBlameService.getInstance(getProject());

        assertNull(service.blameLineCached(vf, 1));
        assertNull(service.blameLine(vf, 1, null));

        // 预热一批文件也不该抛异常
        service.warm(java.util.List.of(vf), null);
        assertNull(service.blameLineCached(vf, 1));
    }

    public void test行号越界返回null() {
        var file = myFixture.addClass("package com.example; public class Foo {}");
        VirtualFile vf = file.getContainingFile().getVirtualFile();
        GitBlameService service = GitBlameService.getInstance(getProject());

        assertNull(service.blameLine(vf, 0, null));
        assertNull(service.blameLine(vf, 100000, null));
    }

    public void test服务销毁不抛异常() {
        GitBlameService service = GitBlameService.getInstance(getProject());
        service.dispose();
        service.dispose(); // 允许重复调用
    }
}
