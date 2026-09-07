package com.mapperchecker.idea.run;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.CheckResult;
import com.mapperchecker.idea.MapperCheckerBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次完整检查：发现 → 逐项检查 → 收尾。可在后台任务或测试中直接调用。
 * <p>
 * 发现阶段与每个接口 / 调用点的检查分别放在独立的非阻塞读操作里，及时让出读锁，可取消。
 */
public final class CheckRunner {

    /** 运行产物：core 结果 + 可导航问题 + 可导航的已豁免记录。 */
    public record Outcome(CheckResult result, List<ReportedIssue> reported, List<ReportedExemption> exempted) {
        public Outcome(CheckResult result, List<ReportedIssue> reported) {
            this(result, reported, List.of());
        }
    }

    private final Project project;
    private final CheckSettings settings;

    public CheckRunner(@NotNull Project project, @NotNull CheckSettings settings) {
        this.project = project;
        this.settings = settings;
    }

    /**
     * @param scope     范围
     * @param indicator 进度，可为 null（测试）
     */
    public @NotNull Outcome run(@NotNull CheckScope scope, @Nullable ProgressIndicator indicator) {
        CheckRunContext ctx = new CheckRunContext(project, settings);
        ContractCheckEngine engine = new ContractCheckEngine(ctx);
        InvocationDiscovery discovery = new InvocationDiscovery(project);

        InvocationDiscovery.Found found = read(indicator, () -> {
            GlobalSearchScope ds = scope.discoveryScope(project);
            if (ds != null) {
                return discovery.discover(ds, indicator);
            }
            return discovery.discoverInFiles(scope.files);
        });

        // 纯 Java 规则的候选文件：Module / 项目范围用词索引找，文件范围就是所选文件
        GlobalSearchScope discoveryScope = scope.discoveryScope(project);
        List<PsiFile> javaFiles = read(indicator, () -> {
            if (!needsJavaFileScan()) {
                return List.<PsiFile>of();
            }
            if (discoveryScope != null) {
                return discovery.findJavaFilesForRules(discoveryScope, engine.javaRules().queryClassIndex(), indicator);
            }
            List<PsiFile> out = new ArrayList<>();
            PsiManager pm = PsiManager.getInstance(project);
            for (VirtualFile vf : scope.files) {
                PsiFile f = pm.findFile(vf);
                if (f != null) {
                    out.add(f);
                }
            }
            return out;
        });

        GlobalSearchScope callSiteScope = scope.callSiteScope(project);
        int total = found.mapperInterfaces().size() + found.stringCalls().size() + javaFiles.size()
                + (discoveryScope != null ? 2 : 0);
        int done = 0;
        if (indicator != null) {
            indicator.setText2("");
            indicator.setText(MapperCheckerBundle.message("task.discovery.done",
                    found.mapperInterfaces().size(), found.stringCalls().size()));
        }

        done = runBatched(found.mapperInterfaces(), done, total, indicator, mapper -> {
            if (mapper.isValid()) {
                engine.checkMapperInterface(mapper, callSiteScope);
            }
        });
        done = runBatched(found.stringCalls(), done, total, indicator, call -> {
            if (call.isValid()) {
                engine.checkStringCall(call);
            }
        });
        done = runBatched(javaFiles, done, total, indicator, file -> {
            if (file.isValid()) {
                engine.javaRules().checkJavaFile(file);
            }
        });
        if (discoveryScope != null) {
            // 全局规则：Query 类级聚合（DAL-010 / 011），要等所有 Mapper 与 Java 文件都看完
            progress(indicator, ++done, total);
            read(indicator, () -> {
                engine.checkQueryClasses(indicator);
                return null;
            });
            // 全局规则：跨模块同名 Query 类（DAL-021）
            progress(indicator, ++done, total);
            read(indicator, () -> {
                engine.javaRules().checkDuplicateQueryClasses(discoveryScope);
                return null;
            });
        }
        for (VirtualFile f : scope.files) {
            ctx.statistics.incScannedFiles();
        }

        List<ReportedIssue> reported = new ArrayList<>();
        List<ReportedExemption> exempted = new ArrayList<>();
        CheckResult result = read(indicator, () -> ctx.finish(scope.displayName(), reported, exempted));
        return new Outcome(result, reported, exempted);
    }

    /** 纯 Java 规则与 DAL-010/011 都关掉时，整个 Java 文件遍历可以省掉。 */
    private boolean needsJavaFileScan() {
        for (com.mapperchecker.core.model.RuleId r : List.of(
                com.mapperchecker.core.model.RuleId.DAL_004, com.mapperchecker.core.model.RuleId.DAL_020,
                com.mapperchecker.core.model.RuleId.DAL_030, com.mapperchecker.core.model.RuleId.DAL_010,
                com.mapperchecker.core.model.RuleId.DAL_011)) {
            if (settings.isEnabled(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一次读操作里连续处理多个条目，最多 {@link #BATCH_NANOS}。逐条提交非阻塞读操作的开销在几千个条目上很可观，
     * 但一次全做完又会长时间占着读锁、被写操作打断后整批重来，所以按时间切片。
     *
     * @return 新的已完成计数
     */
    private <T> int runBatched(@NotNull List<T> items, int done, int total, @Nullable ProgressIndicator indicator,
                               @NotNull java.util.function.Consumer<T> action) {
        int index = 0;
        while (index < items.size()) {
            final int from = index;
            int[] processed = new int[1];
            read(indicator, () -> {
                processed[0] = 0; // 被写操作打断重跑时从头再来，不能沿用上一次的计数
                long deadline = System.nanoTime() + BATCH_NANOS;
                for (int i = from; i < items.size(); i++) {
                    action.accept(items.get(i));
                    processed[0] = i - from + 1;
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                }
                return null;
            });
            index = from + Math.max(1, processed[0]);
            progress(indicator, done + Math.min(index, items.size()), total);
        }
        return done + items.size();
    }

    private static final long BATCH_NANOS = 50L * 1000 * 1000;

    private <T> T read(@Nullable ProgressIndicator indicator, @NotNull java.util.concurrent.Callable<T> action) {
        // 已在读操作内（Inspect Code 批量模式、测试）：直接执行，避免嵌套非阻塞读操作
        if (com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed()) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        var builder = ReadAction.nonBlocking(action).inSmartMode(project);
        if (indicator != null) {
            builder = builder.wrapProgress(indicator);
        }
        return builder.executeSynchronously();
    }

    private static void progress(@Nullable ProgressIndicator indicator, int done, int total) {
        if (indicator == null) {
            ProgressManager.checkCanceled();
            return;
        }
        indicator.checkCanceled();
        indicator.setText(MapperCheckerBundle.message("task.progress", done, total));
        indicator.setFraction(total == 0 ? 1.0 : (double) done / total);
    }

    /** 便捷：只检查一个 PsiFile（GlobalInspectionTool 用）。 */
    public @NotNull Outcome runOnFile(@NotNull PsiFile file) {
        VirtualFile vf = file.getVirtualFile();
        if (vf == null) {
            return new Outcome(CheckResult.empty(file.getName()), List.of());
        }
        PsiManager.getInstance(project); // 确保 PSI 服务已初始化
        return run(CheckScope.file(vf), null);
    }
}
