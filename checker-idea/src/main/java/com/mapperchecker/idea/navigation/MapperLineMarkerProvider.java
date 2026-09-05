package com.mapperchecker.idea.navigation;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.java.MapperInterfaceDetector;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * gutter 图标：Mapper 接口方法 → XML statement。默认关闭，设置中开启。方案第 16 节。
 */
public final class MapperLineMarkerProvider implements LineMarkerProvider {

    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public void collectSlowLineMarkers(@NotNull List<? extends PsiElement> elements,
                                       @NotNull Collection<? super LineMarkerInfo<?>> result) {
        if (elements.isEmpty()) {
            return;
        }
        var project = elements.get(0).getProject();
        if (!MapperCheckerSettings.getInstance(project).state().showGutterIcon || DumbService.isDumb(project)
                || MapperNavigator.mainstreamMyBatisPluginInstalled()) {
            return;
        }
        for (PsiElement e : elements) {
            if (!(e instanceof PsiIdentifier) || !(e.getParent() instanceof PsiMethod method) || method.getNameIdentifier() != e) {
                continue;
            }
            PsiClass owner = method.getContainingClass();
            if (owner == null || owner.getQualifiedName() == null || !MapperInterfaceDetector.isMapperInterface(owner)) {
                continue;
            }
            String fullId = owner.getQualifiedName() + "." + method.getName();
            VirtualFile context = e.getContainingFile() == null ? null : e.getContainingFile().getVirtualFile();
            List<PsiElement> targets = MapperNavigator.findStatementTags(project, fullId, context);
            if (targets.isEmpty()) {
                continue;
            }
            RelatedItemLineMarkerInfo<PsiElement> info = NavigationGutterIconBuilder.create(AllIcons.FileTypes.Xml)
                    .setTargets(targets)
                    .setTooltipText(MapperCheckerBundle.message("navigation.mapper.statement", fullId))
                    .createLineMarkerInfo(e);
            result.add(info);
        }
    }
}
