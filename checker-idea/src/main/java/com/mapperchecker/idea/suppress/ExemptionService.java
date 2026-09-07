package com.mapperchecker.idea.suppress;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.mapperchecker.core.contract.Exemption;
import com.mapperchecker.core.contract.ExemptionFileParser;
import com.mapperchecker.core.model.ContractIssue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队共享豁免：读取项目根目录与各 Module 内容根下的 {@code .binding-scan-ignore.yml}，按文件修改戳缓存。
 * 报告右键"豁免此处"时，把记录追加到问题所在 Module 的 yml（没有则建在项目根）。
 */
@Service(Service.Level.PROJECT)
public final class ExemptionService {

    private final Project project;
    /** 文件路径 → 修改戳，用于判断是否需要重读。 */
    private final Map<String, Long> stamps = new LinkedHashMap<>();
    private List<Exemption> cached = List.of();

    public ExemptionService(@NotNull Project project) {
        this.project = project;
    }

    public static ExemptionService getInstance(@NotNull Project project) {
        return project.getService(ExemptionService.class);
    }

    /** 全部豁免记录（含不完整的，调用方按 isComplete 区分）。 */
    public synchronized @NotNull List<Exemption> all() {
        List<VirtualFile> files = candidateFiles();
        boolean changed = files.size() != stamps.size();
        if (!changed) {
            for (VirtualFile f : files) {
                Long stamp = stamps.get(f.getPath());
                if (stamp == null || stamp != f.getModificationStamp()) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return cached;
        }
        stamps.clear();
        List<Exemption> out = new ArrayList<>();
        for (VirtualFile f : files) {
            stamps.put(f.getPath(), f.getModificationStamp());
            try {
                out.addAll(ExemptionFileParser.parse(VfsUtilCore.loadText(f), f.getPath()));
            } catch (IOException ignored) {
                // 读不到就当没有
            }
        }
        cached = List.copyOf(out);
        return cached;
    }

    /** 命中的第一条完整豁免；没有返回 null。 */
    public @Nullable Exemption find(@NotNull ContractIssue issue) {
        for (Exemption e : all()) {
            if (e.matches(issue)) {
                return e;
            }
        }
        return null;
    }

    /** 候选文件：项目根 + 每个 Module 的内容根。 */
    private List<VirtualFile> candidateFiles() {
        Map<String, VirtualFile> dirs = new LinkedHashMap<>();
        VirtualFile base = ProjectUtil.guessProjectDir(project);
        if (base != null) {
            dirs.put(base.getPath(), base);
        }
        for (Module m : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(m).getContentRoots()) {
                dirs.putIfAbsent(root.getPath(), root);
            }
        }
        List<VirtualFile> files = new ArrayList<>();
        for (VirtualFile dir : dirs.values()) {
            VirtualFile f = dir.findChild(ExemptionFileParser.FILE_NAME);
            if (f != null && !f.isDirectory()) {
                files.add(f);
            }
        }
        return files;
    }

    /**
     * 追加一条豁免到问题所在 Module 的内容根（找不到则项目根）。EDT 调用。
     *
     * @return 写入的文件；失败返回 null
     */
    public @Nullable VirtualFile append(@NotNull Exemption exemption, @Nullable VirtualFile issueFile) {
        VirtualFile dir = ReadAction.compute(() -> targetDir(issueFile));
        if (dir == null) {
            return null;
        }
        try {
            return WriteCommandAction.writeCommandAction(project).compute(() -> {
                VirtualFile f = dir.findChild(ExemptionFileParser.FILE_NAME);
                if (f == null) {
                    f = dir.createChildData(this, ExemptionFileParser.FILE_NAME);
                    f.setBinaryContent("# MyBatis Mapper Checker 团队豁免记录：每条必须带 reason / by / at，随代码进版本库\n"
                            .getBytes(StandardCharsets.UTF_8));
                }
                String existing = VfsUtilCore.loadText(f);
                String sep = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
                f.setBinaryContent((existing + sep + ExemptionFileParser.format(exemption)).getBytes(StandardCharsets.UTF_8));
                return f;
            });
        } catch (IOException e) {
            return null;
        }
    }

    private @Nullable VirtualFile targetDir(@Nullable VirtualFile issueFile) {
        if (issueFile != null) {
            Module m = ProjectFileIndex.getInstance(project).getModuleForFile(issueFile, false);
            if (m != null) {
                VirtualFile[] roots = ModuleRootManager.getInstance(m).getContentRoots();
                for (VirtualFile root : roots) {
                    if (VfsUtilCore.isAncestor(root, issueFile, false)) {
                        return root;
                    }
                }
                if (roots.length > 0) {
                    return roots[0];
                }
            }
        }
        return ProjectUtil.guessProjectDir(project);
    }
}
