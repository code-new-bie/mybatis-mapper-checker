package com.mapperchecker.idea.module;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.PsiManagerEx;
import com.intellij.testFramework.JavaModuleTestCase;
import com.intellij.testFramework.PsiTestUtil;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.idea.run.CheckRunner;
import com.mapperchecker.idea.run.CheckScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Heavy Test：多 Module 可见性。方案 19.1。
 * <pre>
 * module-common   Common.xml（<sql> 片段）
 * module-order    OrderMapper.java + OrderMapper.xml，依赖 common
 * module-app      依赖 order；含 SqlSession 字符串调用
 * module-sibling  与 order 无依赖；含同 fullId statement
 * </pre>
 */
public class ModuleVisibilityHeavyTest extends JavaModuleTestCase {

    private static final String NS = "com.example.order.dao.OrderMapper";

    private VirtualFile root;
    private Module common;
    private Module order;
    private Module app;
    private Module sibling;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = getTempDir().createVirtualDir("multi");
        common = newModule("module-common");
        order = newModule("module-order");
        app = newModule("module-app");
        sibling = newModule("module-sibling");
        ModuleRootModificationUtil.addDependency(order, common);
        ModuleRootModificationUtil.addDependency(app, order);

        write("module-common/src/main/resources/mapper/Common.xml", """
                <mapper namespace="com.example.common.dao.Common">
                    <sql id="scope">merchant_id = #{merchantId}</sql>
                </mapper>
                """);
        write("module-order/src/main/resources/mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="q">SELECT * FROM t WHERE <include refid="com.example.common.dao.Common.scope"/></select>
                </mapper>
                """.formatted(NS));
        write("module-order/src/main/java/com/example/order/dao/OrderMapper.java", """
                package com.example.order.dao;
                public interface OrderMapper {
                    java.util.List<Object> q(@org.apache.ibatis.annotations.Param("merchantId") Long m,
                                             @org.apache.ibatis.annotations.Param("poiId") Long p);
                }
                """);
        write("module-order/src/main/java/org/apache/ibatis/annotations/Param.java",
                "package org.apache.ibatis.annotations; public @interface Param { String value(); }");
        write("module-order/src/main/java/org/apache/ibatis/session/SqlSession.java",
                "package org.apache.ibatis.session; public interface SqlSession { <E> java.util.List<E> selectList(String s, Object p); }");
        // sibling 里有同 fullId 的 statement，但对 order / app 不可见
        write("module-sibling/src/main/resources/mapper/OrderMapper.xml", """
                <mapper namespace="%s">
                    <select id="q">SELECT * FROM other WHERE a = #{merchantId} AND b = #{poiId}</select>
                    <select id="onlyInSibling">SELECT 1</select>
                </mapper>
                """.formatted(NS));
        write("module-app/src/main/java/com/example/app/AppDao.java", """
                package com.example.app;
                import java.util.*;
                import org.apache.ibatis.session.SqlSession;
                public class AppDao {
                    private SqlSession s;
                    public List<Object> run(Long merchantId, Long poiId) {
                        Map<String, Object> p = new HashMap<>();
                        p.put("merchantId", merchantId);
                        p.put("poiId", poiId);
                        s.selectList("%s.q", p);
                        return s.selectList("%s.onlyInSibling", p);
                    }
                }
                """.formatted(NS, NS));
        for (Module m : List.of(common, order, app, sibling)) {
            VirtualFile mDir = root.findChild(m.getName());
            VirtualFile java = WriteAction.computeAndWait(() -> VfsUtil.createDirectoryIfMissing(mDir, "src/main/java"));
            VirtualFile res = WriteAction.computeAndWait(() -> VfsUtil.createDirectoryIfMissing(mDir, "src/main/resources"));
            PsiTestUtil.addSourceRoot(m, java);
            PsiTestUtil.addSourceRoot(m, res, org.jetbrains.jps.model.java.JavaResourceRootType.RESOURCE);
        }
        PsiManagerEx.getInstanceEx(getProject()).dropPsiCaches();
    }

    private Module newModule(String name) throws IOException {
        VirtualFile dir = WriteAction.computeAndWait(() -> root.createChildDirectory(this, name));
        Module m = createModuleAt(name, getProject(), getModuleType(), dir.toNioPath());
        PsiTestUtil.addContentRoot(m, dir);
        // 没有 JDK 时 java.util.Map 解析不到，会被当成 Bean
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

    private CheckRunner.Outcome run(CheckScope scope, boolean strict) {
        CheckSettings s = new CheckSettings(List.of(), Set.of(), Set.of(), List.of(), 3, strict, Set.of(), Map.of());
        return new CheckRunner(getProject(), s).run(scope, null);
    }

    private static List<ContractIssue> ofRule(CheckRunner.Outcome o, RuleId r) {
        return o.result().issues().stream().filter(i -> i.ruleId() == r).toList();
    }

    /** 失败时的诊断信息。 */
    private static String dump(CheckRunner.Outcome o) {
        StringBuilder sb = new StringBuilder("stats: dao=").append(o.result().statistics().daoInvocations())
                .append(" resolved=").append(o.result().statistics().resolvedInvocations())
                .append(" unresolved=").append(o.result().statistics().unresolvedInvocations())
                .append(" mappers=").append(o.result().statistics().mapperInterfaces()).append('\n');
        for (ContractIssue i : o.result().issues()) {
            sb.append("  issue ").append(i.ruleId()).append(' ').append(i.statementId()).append(' ').append(i.parameterName()).append('\n');
        }
        for (var u : o.result().unresolved()) {
            sb.append("  unresolved ").append(u.statementId()).append(' ').append(u.reason()).append(' ').append(u.detail()).append('\n');
        }
        return sb.toString();
    }

    public void test接口在order模块_XML同模块_include跨依赖模块_不见sibling() {
        CheckRunner.Outcome o = run(CheckScope.module(order), true);
        // sibling 的同 fullId statement 不可见，因此不是 MMC003
        assertTrue(ofRule(o, RuleId.MMC003).isEmpty());
        // include 到 common 的片段成功展开：merchantId 被使用，poiId 未使用
        List<ContractIssue> unused = ofRule(o, RuleId.MMC001);
        assertEquals(1, unused.size());
        assertEquals("poiId", unused.get(0).parameterName());
        assertTrue(unused.get(0).secondaryLocation().filePath().contains("module-order"));
    }

    public void testApp模块通过传递依赖看到order的Mapper_看不到sibling() {
        CheckRunner.Outcome o = run(CheckScope.module(app), true);
        // q 可见（app → order），poiId 多余
        List<ContractIssue> unused = ofRule(o, RuleId.MMC001);
        assertEquals(dump(o), 1, unused.size());
        assertEquals(NS + ".q", unused.get(0).statementId());
        // onlyInSibling 在严格模式下不可见 → MMC002
        List<ContractIssue> nf = ofRule(o, RuleId.MMC002);
        assertEquals(1, nf.size());
        assertEquals(NS + ".onlyInSibling", nf.get(0).statementId());
    }

    public void test兼容模式fallback到整项目_但同fullId仍报歧义() {
        CheckRunner.Outcome o = run(CheckScope.module(app), false);
        // onlyInSibling 在兼容模式下可以找到，不再 MMC002
        assertTrue(ofRule(o, RuleId.MMC002).isEmpty());
        // q：严格层已唯一命中 order，不会因为兼容层多一个候选而报歧义
        assertTrue(ofRule(o, RuleId.MMC003).isEmpty());
    }

    public void test整项目扫描_sibling接口自身检查() {
        CheckRunner.Outcome o = run(CheckScope.project(), true);
        // 只有 order 模块声明了接口；sibling 只有 XML
        assertEquals(1, o.result().statistics().mapperInterfaces());
        PsiClass mapper = com.intellij.psi.JavaPsiFacade.getInstance(getProject())
                .findClass(NS, com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));
        assertNotNull(mapper);
        assertNotNull(PsiManager.getInstance(getProject()).findFile(mapper.getContainingFile().getVirtualFile()));
    }
}
