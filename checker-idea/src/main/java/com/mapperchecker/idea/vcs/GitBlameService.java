package com.mapperchecker.idea.vcs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.annotate.AnnotationProvider;
import com.intellij.openapi.vcs.annotate.FileAnnotation;
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "谁提交的"：走平台通用 VCS API（{@link AnnotationProvider}），不认哪个具体 VCS，Git4Idea 之类插件
 * 装了就有用，没装就老老实实返回 null——不装作能看穿不存在的版本库。方案 14.5（真机反馈 2026-09-07 加入）。
 * <p>
 * 默认不启用：报告右上角"显示责任人"手动打开才会跑；单条右键"查看该处提交信息"随时可用，成本是一行的量级。
 * {@code annotate()} 可能真的起一个 git 进程，必须在后台线程调用，绝不能出现在 EDT 上。
 */
@Service(Service.Level.PROJECT)
public final class GitBlameService implements Disposable {

    /** 一行的责任人信息。 */
    public record BlameInfo(@NotNull String author, @NotNull String date, @NotNull String revision) {
        public String display() {
            return date.isEmpty() ? author : author + "  " + date;
        }
    }

    private record Cached(FileAnnotation annotation, long stamp) {
    }

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd");

    private final Project project;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public GitBlameService(@NotNull Project project) {
        this.project = project;
    }

    public static GitBlameService getInstance(@NotNull Project project) {
        return project.getService(GitBlameService.class);
    }

    /**
     * 只读缓存，不触发新的 annotate，EDT 安全——渲染树的时候用这个，宁可先什么都不显示，
     * 也不能在画一行的时候意外起个子进程把界面卡住。
     */
    public @Nullable BlameInfo blameLineCached(@NotNull VirtualFile file, int line) {
        Cached c = cache.get(file.getPath());
        if (c == null || c.stamp() != file.getModificationStamp()) {
            return null;
        }
        return extract(c.annotation(), line);
    }

    /** 后台线程调用；不在版本控制下 / 未提交 / annotate 失败都返回 null，不抛异常。 */
    public @Nullable BlameInfo blameLine(@NotNull VirtualFile file, int line, @Nullable ProgressIndicator indicator) {
        FileAnnotation ann = annotationFor(file, indicator);
        return ann == null ? null : extract(ann, line);
    }

    /** 后台任务里预热一批文件，之后 {@link #blameLineCached} 就能命中，避免渲染时现起进程。 */
    public void warm(@NotNull Collection<VirtualFile> files, @Nullable ProgressIndicator indicator) {
        for (VirtualFile f : files) {
            if (indicator != null) {
                indicator.checkCanceled();
            } else {
                ProgressManager.checkCanceled();
            }
            annotationFor(f, indicator);
        }
    }

    private @Nullable BlameInfo extract(FileAnnotation ann, int line1based) {
        int line = line1based - 1; // FileAnnotation 用 0 起始行号，与本插件其余处的 1 起始不同
        try {
            if (line < 0 || line >= ann.getLineCount()) {
                return null;
            }
            String author = authorOf(ann, line);
            if (author == null || author.isBlank()) {
                return null; // 未提交 / 本地改动，annotate 给不出作者
            }
            Date date = ann.getLineDate(line);
            VcsRevisionNumber rev = ann.getLineRevisionNumber(line);
            return new BlameInfo(author, date == null ? "" : DATE_FORMAT.format(date), rev == null ? "" : rev.asString());
        } catch (RuntimeException e) {
            // 各家 VCS 插件实现质量不一，宁可什么都不显示也不要把报告窗口炸掉
            return null;
        }
    }

    /** "作者"不是 FileAnnotation 的一等方法，要在 getAspects() 里按 id 找。 */
    private static @Nullable String authorOf(FileAnnotation ann, int line) {
        for (LineAnnotationAspect aspect : ann.getAspects()) {
            if (LineAnnotationAspect.AUTHOR.equals(aspect.getId())) {
                return aspect.getValue(line);
            }
        }
        return null;
    }

    private @Nullable FileAnnotation annotationFor(VirtualFile file, @Nullable ProgressIndicator indicator) {
        long stamp = file.getModificationStamp();
        Cached cached = cache.get(file.getPath());
        if (cached != null && cached.stamp() == stamp) {
            return cached.annotation();
        }
        if (cached != null) {
            cached.annotation().close();
            cache.remove(file.getPath());
        }
        AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(file);
        if (vcs == null) {
            return null;
        }
        AnnotationProvider provider = vcs.getAnnotationProvider();
        if (provider == null) {
            return null;
        }
        try {
            if (indicator != null) {
                indicator.checkCanceled();
            }
            FileAnnotation ann = provider.annotate(file);
            cache.put(file.getPath(), new Cached(ann, stamp));
            return ann;
        } catch (VcsException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public void dispose() {
        for (Cached c : cache.values()) {
            c.annotation().close();
        }
        cache.clear();
    }
}
