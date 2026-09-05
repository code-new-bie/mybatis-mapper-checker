package com.mapperchecker.idea.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.mapperchecker.idea.run.CheckScope;
import com.mapperchecker.idea.run.ContractCheckTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 三个触发入口。方案 13.1。 */
public final class CheckActions {

    private CheckActions() {
    }

    /** 检查当前文件（编辑器右键 / 文件上下文）。 */
    public static final class CheckCurrentFileAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            if (project == null || file == null || file.isDirectory()) {
                return;
            }
            ContractCheckTask.start(project, CheckScope.file(file));
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
            boolean ok = e.getProject() != null && file != null && !file.isDirectory()
                    && "java".equalsIgnoreCase(file.getExtension());
            e.getPresentation().setEnabledAndVisible(ok);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** 检查所选文件 / 目录 / Module（Project 视图右键）。 */
    public static final class CheckSelectionAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project == null) {
                return;
            }
            Module module = e.getData(LangDataKeys.MODULE_CONTEXT);
            if (module != null) {
                ContractCheckTask.start(project, CheckScope.module(module));
                return;
            }
            VirtualFile[] selected = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (selected == null || selected.length == 0) {
                return;
            }
            List<VirtualFile> javaFiles = new ArrayList<>();
            for (VirtualFile vf : selected) {
                VfsUtilCore.visitChildrenRecursively(vf, new VirtualFileVisitor<Void>() {
                    @Override
                    public boolean visitFile(@NotNull VirtualFile f) {
                        if (!f.isDirectory() && "java".equalsIgnoreCase(f.getExtension())) {
                            javaFiles.add(f);
                        }
                        return true;
                    }
                });
            }
            if (javaFiles.size() == 1) {
                ContractCheckTask.start(project, CheckScope.file(javaFiles.get(0)));
            } else {
                ContractCheckTask.start(project, CheckScope.files(javaFiles));
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            boolean ok = e.getProject() != null
                    && (e.getData(LangDataKeys.MODULE_CONTEXT) != null
                    || e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) != null
                    || e.getData(PlatformCoreDataKeys.SELECTED_ITEMS) != null);
            e.getPresentation().setEnabledAndVisible(ok);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }

    /** 检查整个项目（Tools 菜单）。 */
    public static final class CheckProjectAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Project project = e.getProject();
            if (project != null) {
                ContractCheckTask.start(project, CheckScope.project());
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabledAndVisible(e.getProject() != null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }
    }
}
