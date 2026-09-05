package com.mapperchecker.idea.navigation;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlTag;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.idea.mapper.MapperRepository;
import com.mapperchecker.idea.module.ModuleVisibilityResolver;
import com.mapperchecker.idea.run.CheckRunContext;
import com.mapperchecker.idea.run.StatementLocator;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 由 statementId 找到 XML 标签，供 Ctrl+Click 与 gutter 使用。 */
public final class MapperNavigator {

    private static final String[] MAINSTREAM_PLUGINS = {
            "com.baomidou.plugin.idea.mybatisx",   // MyBatisX
            "com.seventh7.plugin.mybatis",         // MyBatis plugin
            "brucege.com.mybatiscodehelper.pro",   // MyBatisCodeHelperPro
    };

    private MapperNavigator() {
    }

    /** 已安装主流 MyBatis 插件时，接口方法导航让位。方案第 16 节。 */
    public static boolean mainstreamMyBatisPluginInstalled() {
        for (String id : MAINSTREAM_PLUGINS) {
            if (PluginManagerCore.isPluginInstalled(PluginId.getId(id))) {
                return true;
            }
        }
        return false;
    }

    /** Dumb Mode 静默返回空。 */
    public static @NotNull List<PsiElement> findStatementTags(@NotNull Project project, @NotNull String fullId,
                                                              @Nullable VirtualFile contextFile) {
        if (DumbService.isDumb(project)) {
            return List.of();
        }
        var settings = MapperCheckerSettings.getInstance(project).toCheckSettings();
        MapperRepository repo = new MapperRepository(project, settings.ignoredPathPatterns());
        // 导航用兼容模式：找得到就跳
        ModuleVisibilityResolver visibility = new ModuleVisibilityResolver(project, false);
        StatementLocator locator = new StatementLocator(repo, visibility);
        var lookup = locator.locate(fullId, contextFile, null);
        List<PsiElement> out = new ArrayList<>();
        for (ResolvedStatement st : lookup.candidates()) {
            for (SourceLocation loc : st.definitions()) {
                PsiElement tag = tagAt(project, loc);
                if (tag != null) {
                    out.add(tag);
                }
            }
        }
        return out;
    }

    static @Nullable PsiElement tagAt(Project project, SourceLocation loc) {
        if (!loc.isKnown()) {
            return null;
        }
        VirtualFile vf = CheckRunContext.findFileForNavigation(loc.filePath());
        if (vf == null) {
            return null;
        }
        PsiFile file = PsiManager.getInstance(project).findFile(vf);
        if (file == null) {
            return null;
        }
        PsiElement at = file.findElementAt(Math.min(loc.startOffset(), Math.max(0, file.getTextLength() - 1)));
        XmlTag tag = at == null ? null : PsiTreeUtil.getParentOfType(at, XmlTag.class, false);
        return tag != null ? tag : at;
    }
}
