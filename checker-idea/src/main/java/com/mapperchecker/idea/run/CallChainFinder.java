package com.mapperchecker.idea.run;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.MethodReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.idea.util.Locations;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "这个方法是从哪儿被调用进来的"：从一个方法开始反向找调用者，一直找到没有调用者的方法（入口点：
 * Controller、定时任务、测试……）或达到深度上限。真机反馈：报告只看得到 DAO 方法本身，看不出参数
 * 最初是从哪个入口一路传下来的，想要这条链。
 * <p>
 * 只在用户主动展开时按需计算（报告右键"查看调用链"），不进批量扫描——引用搜索一层层往上摊开，
 * 代价和"扫一遍报告"完全不是一个量级，全量做没有意义。
 * <p>
 * 一个方法可能被很多处调用（扇入），会分叉出多条链；为避免组合爆炸：每层最多展开
 * {@link #MAX_CALLERS_PER_LEVEL} 个不同的调用方法（同一方法多处调用只算一次），深度上限
 * {@link #MAX_DEPTH}，整棵树节点数上限 {@link #MAX_TOTAL_NODES}——超限的地方明确标出来，
 * 不静默截断（方案一贯的原则：可疑就说明白，不要看起来像"查完了"）。
 * <p>
 * 必须在 ReadAction 内调用（PSI 引用搜索）。
 */
public final class CallChainFinder {

    private static final int MAX_DEPTH = 6;
    private static final int MAX_CALLERS_PER_LEVEL = 5;
    private static final int MAX_TOTAL_NODES = 200;
    /** 单个方法的引用搜索本身也设个上限，避免超大扇入（如 toString）在这一步就卡死。 */
    private static final int MAX_REFS_PER_METHOD = 300;

    /** 树节点：一个方法 + 它在调用者里的调用点（根节点为 null）+ 再往上的调用者。 */
    public static final class Node {
        public final PsiMethod method;
        public final @Nullable PsiElement callSite;
        public final List<Node> callers = new ArrayList<>();
        /** 调用者数量超过 {@link #MAX_CALLERS_PER_LEVEL}，只展开了一部分。 */
        public boolean truncatedCallers;
        /** 这条链上已经出现过该方法，判定为循环调用，不再往上展开。 */
        public boolean cycle;

        Node(PsiMethod method, @Nullable PsiElement callSite) {
            this.method = method;
            this.callSite = callSite;
        }
    }

    private final GlobalSearchScope scope;
    private int nodeBudget;

    public CallChainFinder(@NotNull Project project) {
        // 只看项目源码：调用者只可能是用户自己的代码，进库搜索既没意义又拖慢速度
        this.scope = GlobalSearchScope.projectScope(project);
    }

    /** 以 target 为根构建向上追调用者的树。indicator 可为 null（测试）。必须在 ReadAction 内调用。 */
    public @NotNull Node build(@NotNull PsiMethod target, @Nullable ProgressIndicator indicator) {
        nodeBudget = MAX_TOTAL_NODES;
        Node root = new Node(target, null);
        expand(root, new HashSet<>(List.of(target)), 0, indicator);
        return root;
    }

    private void expand(Node node, Set<PsiMethod> visiting, int depth, @Nullable ProgressIndicator indicator) {
        if (indicator != null) {
            indicator.checkCanceled();
        } else {
            ProgressManager.checkCanceled();
        }
        if (depth >= MAX_DEPTH || nodeBudget <= 0) {
            return;
        }
        // 同一方法可能被同一个调用方多处调用（循环体里、多个分支……），归并成一个调用者节点
        Map<PsiMethod, PsiElement> callerMethods = new LinkedHashMap<>();
        int[] seen = {0};
        MethodReferencesSearch.search(node.method, scope, true).forEach(ref -> {
            if (indicator != null) {
                indicator.checkCanceled();
            }
            seen[0]++;
            PsiMethod caller = PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethod.class);
            if (caller != null) {
                callerMethods.putIfAbsent(caller, ref.getElement());
            }
            return seen[0] < MAX_REFS_PER_METHOD;
        });

        int shown = 0;
        for (Map.Entry<PsiMethod, PsiElement> e : callerMethods.entrySet()) {
            if (shown >= MAX_CALLERS_PER_LEVEL || nodeBudget <= 0) {
                node.truncatedCallers = true;
                break;
            }
            PsiMethod caller = e.getKey();
            Node child = new Node(caller, e.getValue());
            node.callers.add(child);
            nodeBudget--;
            shown++;
            if (visiting.contains(caller)) {
                child.cycle = true;
                continue;
            }
            Set<PsiMethod> nextVisiting = new HashSet<>(visiting);
            nextVisiting.add(caller);
            expand(child, nextVisiting, depth + 1, indicator);
        }
    }

    /** 渲染成缩进树文本，方法在上、调用者依次缩进在下——和 IDE 自带的 Call Hierarchy 读法一致。 */
    public static String render(Node root) {
        StringBuilder sb = new StringBuilder();
        sb.append(describe(root.method)).append('\n');
        renderChildren(root, "", sb);
        return sb.toString();
    }

    private static void renderChildren(Node node, String prefix, StringBuilder sb) {
        if (node.cycle) {
            return; // 循环节点本身已经在上一层打印过，不再重复展开
        }
        if (node.callers.isEmpty()) {
            sb.append(prefix).append("└─ （未找到调用者：可能是入口方法 / 测试 / 反射调用 / 未被使用）\n");
            return;
        }
        for (int i = 0; i < node.callers.size(); i++) {
            Node child = node.callers.get(i);
            boolean last = i == node.callers.size() - 1 && !node.truncatedCallers;
            String branch = last ? "└─ " : "├─ ";
            String childPrefix = prefix + (last ? "   " : "│  ");
            sb.append(prefix).append(branch).append(describe(child.method));
            if (child.callSite != null) {
                sb.append("  ").append(Locations.of(child.callSite).display());
            }
            sb.append(child.cycle ? "  ↺ 循环调用，不再展开" : "").append('\n');
            renderChildren(child, childPrefix, sb);
        }
        if (node.truncatedCallers) {
            sb.append(prefix).append("└─ … 还有更多调用者未展开（超过上限）\n");
        }
    }

    private static String describe(PsiMethod m) {
        PsiClass c = m.getContainingClass();
        String owner = c == null ? null : c.getQualifiedName();
        return (owner == null ? "" : owner + ".") + m.getName() + "()";
    }
}
