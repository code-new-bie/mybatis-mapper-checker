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

    /** 运行产物：core 结果 + 可导航问题。 */
    public record Outcome(CheckResult result, List<ReportedIssue> reported) {
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
                return discovery.discover(ds);
            }
            return discovery.discoverInFiles(scope.files);
        });

        GlobalSearchScope callSiteScope = scope.callSiteScope(project);
        int total = found.mapperInterfaces().size() + found.stringCalls().size();
        int done = 0;

        for (PsiClass mapper : found.mapperInterfaces()) {
            progress(indicator, ++done, total);
            read(indicator, () -> {
                if (mapper.isValid()) {
                    engine.checkMapperInterface(mapper, callSiteScope);
                }
                return null;
            });
        }
        for (PsiMethodCallExpression call : found.stringCalls()) {
            progress(indicator, ++done, total);
            read(indicator, () -> {
                if (call.isValid()) {
                    engine.checkStringCall(call);
                }
                return null;
            });
        }
        for (VirtualFile f : scope.files) {
            ctx.statistics.incScannedFiles();
        }

        List<ReportedIssue> reported = new ArrayList<>();
        CheckResult result = read(indicator, () -> ctx.finish(scope.displayName(), reported));
        return new Outcome(result, reported);
    }

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
