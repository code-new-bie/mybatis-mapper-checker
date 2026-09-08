package com.mapperchecker.idea.report;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.util.PsiTreeUtil;
import com.mapperchecker.idea.vcs.GitBlameService;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
import com.mapperchecker.core.model.CheckResult;
import com.mapperchecker.core.model.Confidence;
import com.mapperchecker.core.model.ContractIssue;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.Statistics;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.run.CallChainFinder;
import com.mapperchecker.idea.run.CheckRunContext;
import com.mapperchecker.idea.run.CheckRunner;
import com.mapperchecker.idea.run.CheckScope;
import com.mapperchecker.idea.run.ContractCheckTask;
import com.mapperchecker.idea.run.ReportedExemption;
import com.mapperchecker.idea.run.ReportedIssue;
import com.mapperchecker.idea.settings.MapperCheckerConfigurable;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 报告窗口：统计条 + 树形列表（Module → 文件 → 问题）+ 详情。方案 14.1。
 */
public final class CheckReportPanel extends JPanel {

    private final Project project;
    private final Tree tree = new Tree();
    private final JBTextArea details = new JBTextArea();
    private final JBLabel stats = new JBLabel();
    private final JComboBox<String> ruleFilter = new JComboBox<>();
    private final JComboBox<String> confidenceFilter = new JComboBox<>();
    private final Set<ReportedIssue> confirmed = new HashSet<>();
    private boolean showBlame = false;

    private @Nullable CheckRunner.Outcome outcome;
    private @Nullable CheckScope scope;

    public CheckReportPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty());

        ruleFilter.addItem(MapperCheckerBundle.message("report.filter.rule.all"));
        for (RuleId r : RuleId.values()) {
            ruleFilter.addItem(r.code());
        }
        confidenceFilter.addItem(MapperCheckerBundle.message("report.filter.confidence.all"));
        for (Confidence c : Confidence.values()) {
            confidenceFilter.addItem(MapperCheckerBundle.message("confidence." + c.name().toLowerCase(Locale.ROOT)));
        }
        ruleFilter.addActionListener(e -> rebuildTree());
        confidenceFilter.addActionListener(e -> rebuildTree());

        JPanel top = new JPanel(new BorderLayout());
        top.add(toolbar(), BorderLayout.WEST);
        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filters.add(ruleFilter);
        filters.add(confidenceFilter);
        top.add(filters, BorderLayout.CENTER);
        JPanel header = new JPanel(new BorderLayout());
        header.add(top, BorderLayout.NORTH);
        stats.setBorder(JBUI.Borders.empty(4, 8));
        header.add(stats, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(e -> {
            ReportedExemption re = selectedExemption();
            showDetails(re != null ? re.reported() : selectedIssue(), selectedUnresolved(), re);
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ReportedExemption re = selectedExemption();
                    navigateToJava(re != null ? re.reported() : selectedIssue(), selectedUnresolved());
                }
            }
        });
        PopupHandler.installPopupMenu(tree, popupActions(), "MapperCheckerReportPopup");

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setBorder(JBUI.Borders.empty(6));

        JBSplitter splitter = new JBSplitter(false, 0.65f);
        splitter.setFirstComponent(new JBScrollPane(tree));
        splitter.setSecondComponent(new JBScrollPane(details));
        add(splitter, BorderLayout.CENTER);

        stats.setText(MapperCheckerBundle.message("report.empty.no.mapper"));
    }

    // ---------------------------------------------------------------- 渲染

    public void render(@NotNull CheckRunner.Outcome newOutcome, @Nullable CheckScope newScope) {
        this.outcome = newOutcome;
        this.scope = newScope;
        this.confirmed.clear();
        CheckResult r = newOutcome.result();
        Statistics s = r.statistics();
        stats.setText(MapperCheckerBundle.message("report.stats",
                r.scopeName(), s.mapperInterfaces(), s.daoInvocations(), s.resolvedInvocations(),
                s.unresolvedInvocations(), s.suppressedIssues(), s.totalIssues(),
                s.highIssues(), s.mediumIssues(), s.lowIssues(), s.exemptedIssues()));
        rebuildTree();
    }

    private void rebuildTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        if (outcome != null) {
            List<ReportedIssue> visible = filtered(outcome.reported());
            DefaultMutableTreeNode issuesNode = new DefaultMutableTreeNode(
                    MapperCheckerBundle.message("report.node.issues", visible.size()));
            Map<String, Map<String, List<ReportedIssue>>> grouped = new LinkedHashMap<>();
            for (ReportedIssue ri : visible) {
                String module = moduleOf(ri);
                String file = ri.issue().primaryLocation().fileName();
                grouped.computeIfAbsent(module, k -> new LinkedHashMap<>())
                        .computeIfAbsent(file, k -> new ArrayList<>()).add(ri);
            }
            for (var m : grouped.entrySet()) {
                DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(
                        MapperCheckerBundle.message("report.node.module", m.getKey().isEmpty() ? "-" : m.getKey()));
                for (var f : m.getValue().entrySet()) {
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(f.getKey());
                    for (ReportedIssue ri : f.getValue()) {
                        fileNode.add(new DefaultMutableTreeNode(ri));
                    }
                    moduleNode.add(fileNode);
                }
                issuesNode.add(moduleNode);
            }
            root.add(issuesNode);

            List<UnresolvedInvocation> unresolved = outcome.result().unresolved();
            DefaultMutableTreeNode unresolvedNode = new DefaultMutableTreeNode(
                    MapperCheckerBundle.message("report.node.unresolved", unresolved.size()));
            for (UnresolvedInvocation u : unresolved) {
                unresolvedNode.add(new DefaultMutableTreeNode(u));
            }
            root.add(unresolvedNode);

            // 已豁免：不是问题，但必须可见（规范第六节）
            List<ReportedExemption> exempted = outcome.exempted();
            DefaultMutableTreeNode exemptedNode = new DefaultMutableTreeNode(
                    MapperCheckerBundle.message("report.node.exempted", exempted.size()));
            for (ReportedExemption re : exempted) {
                exemptedNode.add(new DefaultMutableTreeNode(re));
            }
            root.add(exemptedNode);
        }
        tree.setModel(new DefaultTreeModel(root));
        TreeUtil.expand(tree, 3);
        details.setText("");
    }

    private List<ReportedIssue> filtered(List<ReportedIssue> all) {
        int ruleIdx = ruleFilter.getSelectedIndex();
        int confIdx = confidenceFilter.getSelectedIndex();
        List<ReportedIssue> out = new ArrayList<>();
        for (ReportedIssue ri : all) {
            if (ruleIdx > 0 && ri.issue().ruleId().ordinal() != ruleIdx - 1) {
                continue;
            }
            if (confIdx > 0 && ri.issue().confidence().ordinal() != confIdx - 1) {
                continue;
            }
            out.add(ri);
        }
        return out;
    }

    private String moduleOf(ReportedIssue ri) {
        // 模块名在检查运行期间（read action 内）就算好存进了 ReportedIssue，这里不碰 PSI。
        return ri.moduleName();
    }

    // ---------------------------------------------------------------- 选中与详情

    private @Nullable ReportedIssue selectedIssue() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof ReportedIssue ri ? ri : null;
    }

    private @Nullable ReportedExemption selectedExemption() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof ReportedExemption re ? re : null;
    }

    private @Nullable UnresolvedInvocation selectedUnresolved() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof UnresolvedInvocation u ? u : null;
    }

    private void showDetails(@Nullable ReportedIssue ri, @Nullable UnresolvedInvocation u,
                             @Nullable ReportedExemption re) {
        if (ri != null) {
            ContractIssue i = ri.issue();
            StringBuilder sb = new StringBuilder(i.message()).append("\n\n");
            if (re != null) {
                var ex = re.exemption();
                sb.append(MapperCheckerBundle.message("report.detail.exemption",
                        ex.reason(), ex.by(), ex.at(), ex.sourcePath() + ":" + ex.line())).append("\n\n");
            }
            sb.append(MapperCheckerBundle.message("report.detail.rule")).append("：").append(i.ruleId().code()).append('\n');
            if (i.secondaryLocation().isKnown()) {
                sb.append(MapperCheckerBundle.message("report.detail.mapper")).append("：")
                        .append(i.secondaryLocation().filePath()).append('\n');
            }
            sb.append(MapperCheckerBundle.message("report.detail.statement")).append("：").append(i.statementId()).append('\n');
            sb.append(MapperCheckerBundle.message("report.detail.confidence")).append("：")
                    .append(ReportExporter.confidence(i)).append('\n');
            if (!i.callPath().isEmpty()) {
                sb.append(MapperCheckerBundle.message("remark.call.path", String.join(" → ", i.callPath()))).append('\n');
            }
            if (!i.remark().isEmpty()) {
                sb.append(MapperCheckerBundle.message("report.detail.remark")).append("：").append(i.remark()).append('\n');
            }
            if (!i.candidates().isEmpty()) {
                sb.append('\n');
                for (var c : i.candidates()) {
                    sb.append("  - ").append(c.filePath()).append('\n');
                }
            }
            details.setText(sb.toString());
        } else if (u != null) {
            details.setText(u.statementId() + "\n\n"
                    + MapperCheckerBundle.message("report.detail.reason") + "："
                    + MapperCheckerBundle.message("unresolved." + u.reason().name())
                    + (u.detail().isEmpty() ? "" : "（" + u.detail() + "）") + "\n"
                    + u.location().filePath());
        } else {
            details.setText("");
        }
    }

    // ---------------------------------------------------------------- 导航

    private void navigateToJava(@Nullable ReportedIssue ri, @Nullable UnresolvedInvocation u) {
        if (ri != null) {
            navigateToPointer(ri.javaAnchor());
        } else if (u != null && u.location().isKnown()) {
            VirtualFile vf = com.mapperchecker.idea.run.CheckRunContext.findFileForNavigation(u.location().filePath());
            if (vf != null) {
                new OpenFileDescriptor(project, vf, Math.max(0, u.location().startOffset())).navigate(true);
            }
        }
    }

    /** 选中的问题，豁免行取它包着的那条。跳转、责任人查询都按这个统一处理。 */
    private @Nullable ReportedIssue selectedReportedIssue() {
        ReportedExemption re = selectedExemption();
        return re != null ? re.reported() : selectedIssue();
    }

    /** 打开"显示责任人"时，把当前树上看得到的文件先在后台预热一遍，避免渲染时现起 git 进程。 */
    private void warmBlame() {
        if (outcome == null) {
            return;
        }
        Set<VirtualFile> files = new HashSet<>();
        for (ReportedIssue ri : outcome.reported()) {
            addFileOf(ri, files);
        }
        for (ReportedExemption re : outcome.exempted()) {
            addFileOf(re.reported(), files);
        }
        if (files.isEmpty()) {
            return;
        }
        new Task.Backgroundable(project, MapperCheckerBundle.message("action.show.blame"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                GitBlameService.getInstance(project).warm(files, indicator);
            }

            @Override
            public void onFinished() {
                if (showBlame) {
                    tree.repaint();
                }
            }
        }.queue();
    }

    /**
     * 拿文件走 {@link ContractIssue#primaryLocation()}（纯数据，扫描时已经在 read action 里算好），
     * 不走 {@link ReportedIssue#javaElement()}——那是 PSI，渲染 / 预热都可能发生在 EDT 上还没进
     * read action 的地方，直接摸 PSI 会被 2024.2+ 的线程模型断言拦下来（真机崩过一次）。
     */
    private static @Nullable VirtualFile fileOf(ReportedIssue ri) {
        SourceLocation loc = ri.issue().primaryLocation();
        return loc.isKnown() ? CheckRunContext.findFileForNavigation(loc.filePath()) : null;
    }

    private static void addFileOf(ReportedIssue ri, Set<VirtualFile> out) {
        VirtualFile vf = fileOf(ri);
        if (vf != null) {
            out.add(vf);
        }
    }

    /** 单条查责任人：后台起个小进度条，比整批预热轻，随时可用。 */
    private void viewCommit(ReportedIssue ri) {
        VirtualFile vf = fileOf(ri);
        int line = ri.issue().primaryLocation().line();
        if (vf == null || line <= 0) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("report.blame.unavailable"),
                    MapperCheckerBundle.message("action.view.commit"));
            return;
        }
        GitBlameService.BlameInfo[] result = new GitBlameService.BlameInfo[1];
        com.intellij.openapi.progress.ProgressManager.getInstance().runProcessWithProgressSynchronously(
                () -> result[0] = GitBlameService.getInstance(project).blameLine(vf, line, null),
                MapperCheckerBundle.message("action.view.commit"), true, project);
        GitBlameService.BlameInfo info = result[0];
        if (info == null) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("report.blame.unavailable"),
                    MapperCheckerBundle.message("action.view.commit"));
            return;
        }
        Messages.showInfoMessage(project,
                MapperCheckerBundle.message("report.blame.info", info.author(), info.date(), info.revision()),
                MapperCheckerBundle.message("action.view.commit"));
    }

    /**
     * 查看调用链：从这条问题所在的方法开始，反向找调用者一直到入口点。真机反馈想知道一个参数
     * 最初是从哪个入口一路传下来的。只对当前选中的一条按需计算，不进批量扫描——引用搜索这东西
     * 一层层摊开，代价和扫一遍报告完全不是一个量级。
     */
    private void viewCallChain(ReportedIssue ri) {
        if (ri.javaAnchor() == null) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("report.callchain.unavailable"),
                    MapperCheckerBundle.message("action.view.call.chain"));
            return;
        }
        String[] rendered = new String[1];
        // resolve(javaElement) / isValid 都是 PSI 操作，必须整段包进 ReadAction，不能在进入这个
        // lambda 之前就先调 ri.javaElement()——那样等于在 EDT 上裸调，真机崩过一次同类的
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> ApplicationManager.getApplication().runReadAction(() -> {
            PsiElement anchor = ri.javaElement();
            if (anchor == null || !anchor.isValid()) {
                return;
            }
            PsiMethod method = PsiTreeUtil.getParentOfType(anchor, PsiMethod.class, false);
            if (method == null) {
                return;
            }
            CallChainFinder.Node root = new CallChainFinder(project)
                    .build(method, ProgressManager.getInstance().getProgressIndicator());
            rendered[0] = CallChainFinder.render(root);
        }), MapperCheckerBundle.message("action.view.call.chain"), true, project);
        if (rendered[0] == null) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("report.callchain.unavailable"),
                    MapperCheckerBundle.message("action.view.call.chain"));
            return;
        }
        new CallChainDialog(project, rendered[0]).show();
    }

    /** 调用链弹窗：内容可能有好几层缩进，用等宽字体的只读文本区域，比 Messages 弹窗合适。 */
    private static final class CallChainDialog extends DialogWrapper {
        private final String text;

        CallChainDialog(Project project, String text) {
            super(project, false);
            this.text = text;
            setTitle(MapperCheckerBundle.message("report.callchain.dialog.title"));
            setModal(false); // 不阻塞，方便对照报告和源码看
            init();
        }

        @Override
        protected @NotNull JComponent createCenterPanel() {
            JBTextArea area = new JBTextArea(text);
            area.setEditable(false);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setBorder(JBUI.Borders.empty(6));
            JBScrollPane scroll = new JBScrollPane(area);
            scroll.setPreferredSize(new Dimension(640, 420));
            return scroll;
        }

        @Override
        protected Action @NotNull [] createActions() {
            return new Action[]{getOKAction()};
        }
    }

    private void navigateToMapper(@Nullable ReportedIssue ri) {
        if (ri != null) {
            navigateToPointer(ri.mapperAnchor());
        }
    }

    /**
     * 解析 SmartPsiElementPointer 并跳转。真机崩过一次：resolve / isValid / getContainingFile /
     * getTextRange 全都是 PSI 操作，双击 / 右键触发时是在 EDT 上、不在 read action 里，直接摸会被
     * 2024.2+ 的线程模型断言拦下来。把这几步全包进一个 ReadAction，只把结果（VirtualFile + 偏移，
     * 纯数据）带到 read action 外面，再执行真正的 {@code navigate}——跳转本身是 UI 操作，不需要
     * （也不该）继续持有读锁。
     */
    private void navigateToPointer(@Nullable SmartPsiElementPointer<PsiElement> pointer) {
        if (pointer == null) {
            return;
        }
        VirtualFile[] vf = new VirtualFile[1];
        int[] offset = new int[1];
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiElement e = pointer.getElement();
            if (e == null || !e.isValid()) {
                return;
            }
            PsiFile f = e.getContainingFile();
            vf[0] = f == null ? null : f.getVirtualFile();
            offset[0] = e.getTextRange().getStartOffset();
        });
        if (vf[0] != null) {
            new OpenFileDescriptor(project, vf[0], offset[0]).navigate(true);
        }
    }

    // ---------------------------------------------------------------- 工具栏与右键

    private JComponent toolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new DumbAwareAction(MapperCheckerBundle.message("action.recheck"), null, AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                CheckScope s = scope == null ? CheckScope.project() : scope;
                ContractCheckTask.start(project, s);
            }
        });
        group.add(new DumbAwareAction(MapperCheckerBundle.message("action.check.project"), null, AllIcons.Actions.ProjectDirectory) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ContractCheckTask.start(project, CheckScope.project());
            }
        });
        group.add(new DumbAwareAction(MapperCheckerBundle.message("action.export"), null, AllIcons.ToolbarDecorator.Export) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                export();
            }
        });
        group.add(new DumbAwareAction(MapperCheckerBundle.message("action.open.settings"), null, AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MapperCheckerConfigurable.class);
            }
        });
        group.addSeparator();
        group.add(new ToggleAction(MapperCheckerBundle.message("action.show.blame"), null, AllIcons.Vcs.History) {
            @Override
            public boolean isSelected(@NotNull AnActionEvent e) {
                return showBlame;
            }

            @Override
            public void setSelected(@NotNull AnActionEvent e, boolean state) {
                showBlame = state;
                if (state) {
                    warmBlame();
                } else {
                    tree.repaint();
                }
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        ActionToolbar tb = ActionManager.getInstance().createActionToolbar("MapperCheckerReportToolbar", group, true);
        tb.setTargetComponent(this);
        return tb.getComponent();
    }

    private DefaultActionGroup popupActions() {
        DefaultActionGroup g = new DefaultActionGroup();
        g.add(new DumbAwareAction(MapperCheckerBundle.message("action.goto.java")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                navigateToJava(selectedIssue(), selectedUnresolved());
            }
        });
        g.add(new DumbAwareAction(MapperCheckerBundle.message("action.goto.mapper")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                navigateToMapper(selectedIssue());
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                ReportedIssue ri = selectedIssue();
                // 只判断指针是否存在（纯数据），不 resolve——update() 在 EDT 上被频繁调用，
                // resolve 要 read action，这里不该为了一个"能不能点"就去碰 PSI；
                // 真解析不出来，navigateToMapper 里再安全地判一次，无非是点了没反应
                e.getPresentation().setEnabled(ri != null && ri.mapperAnchor() != null);
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        g.add(new DumbAwareAction(MapperCheckerBundle.message("action.view.commit")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ReportedIssue ri = selectedReportedIssue();
                if (ri != null) {
                    viewCommit(ri);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedReportedIssue() != null);
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        g.add(new DumbAwareAction(MapperCheckerBundle.message("action.view.call.chain")) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ReportedIssue ri = selectedReportedIssue();
                if (ri != null) {
                    viewCallChain(ri);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedReportedIssue() != null);
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        g.addSeparator();
        g.add(new IssueAction("action.exempt.here") {
            @Override
            void apply(ReportedIssue ri) {
                exempt(ri);
            }
        });
        g.add(new IssueAction("action.ignore.here") {
            @Override
            void apply(ReportedIssue ri) {
                MapperCheckerSettings.getInstance(project).addSuppressedPair(ri.issue().suppressionKey());
                remove(ri);
            }
        });
        g.add(new IssueAction("action.ignore.parameter") {
            @Override
            void apply(ReportedIssue ri) {
                MapperCheckerSettings.getInstance(project).addIgnoredParameter(ri.issue().parameterName());
                removeIf(x -> x.issue().parameterName().equals(ri.issue().parameterName()));
            }

            @Override
            boolean enabled(ReportedIssue ri) {
                return ri.issue().ruleId() == RuleId.DAL_001;
            }
        });
        g.add(new IssueAction("action.ignore.statement") {
            @Override
            void apply(ReportedIssue ri) {
                MapperCheckerSettings.getInstance(project).addIgnoredStatement(ri.issue().statementId());
                removeIf(x -> x.issue().statementId().equals(ri.issue().statementId()));
            }
        });
        g.add(new IssueAction("action.mark.confirmed") {
            @Override
            void apply(ReportedIssue ri) {
                confirmed.add(ri);
                tree.repaint();
            }
        });
        return g;
    }

    /** 针对选中问题的右键动作。 */
    private abstract class IssueAction extends DumbAwareAction {
        IssueAction(String key) {
            super(MapperCheckerBundle.message(key, "…"));
        }

        abstract void apply(ReportedIssue ri);

        boolean enabled(ReportedIssue ri) {
            return true;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ReportedIssue ri = selectedIssue();
            if (ri != null) {
                apply(ri);
            }
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            ReportedIssue ri = selectedIssue();
            e.getPresentation().setEnabled(ri != null && enabled(ri));
        }

        @Override
        public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
            return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
        }
    }

    private void remove(ReportedIssue ri) {
        removeIf(x -> x == ri);
    }

    /** 团队豁免：问一句理由，写进问题所在 Module 的 .binding-scan-ignore.yml，并挪到"已豁免"分组。 */
    private void exempt(ReportedIssue ri) {
        String reason = Messages.showInputDialog(project,
                MapperCheckerBundle.message("exempt.dialog.message", ri.issue().ruleId().code(), ri.issue().suppressionKey()),
                MapperCheckerBundle.message("exempt.dialog.title"), null);
        if (reason == null || reason.isBlank()) {
            return;
        }
        String by = System.getProperty("user.name", "");
        String at = java.time.LocalDate.now().toString();
        var exemption = new com.mapperchecker.core.contract.Exemption(ri.issue().ruleId(), ri.issue().suppressionKey(),
                reason.trim(), by, at, "", 0);
        // 拿文件走 primaryLocation（纯数据），不摸 PSI——这里在 EDT 上，之前直接用
        // ri.javaElement().getContainingFile() 崩过（read-action 断言），fileOf 已经是修过的写法
        VirtualFile issueFile = fileOf(ri);
        VirtualFile written = com.mapperchecker.idea.suppress.ExemptionService.getInstance(project).append(exemption, issueFile);
        if (written == null) {
            Messages.showErrorDialog(project, MapperCheckerBundle.message("exempt.failed"), MapperCheckerBundle.message("exempt.dialog.title"));
            return;
        }
        if (outcome == null) {
            return;
        }
        List<ReportedIssue> kept = new ArrayList<>();
        List<ContractIssue> keptPlain = new ArrayList<>();
        for (ReportedIssue x : outcome.reported()) {
            if (x != ri) {
                kept.add(x);
                keptPlain.add(x.issue());
            }
        }
        List<ReportedExemption> ex = new ArrayList<>(outcome.exempted());
        var recorded = new com.mapperchecker.core.contract.Exemption(exemption.rule(), exemption.target(), exemption.reason(),
                exemption.by(), exemption.at(), written.getPath(), 0);
        ex.add(new ReportedExemption(ri, recorded));
        List<com.mapperchecker.core.model.ExemptedIssue> plainEx = new ArrayList<>(outcome.result().exempted());
        plainEx.add(new com.mapperchecker.core.model.ExemptedIssue(ri.issue(), recorded));
        CheckResult old = outcome.result();
        old.statistics().incExemptedIssues();
        outcome = new CheckRunner.Outcome(new CheckResult(old.scopeName(), keptPlain, old.unresolved(), plainEx, old.statistics()), kept, ex);
        rebuildTree();
    }

    private void removeIf(java.util.function.Predicate<ReportedIssue> pred) {
        if (outcome == null) {
            return;
        }
        List<ReportedIssue> kept = new ArrayList<>();
        List<ContractIssue> keptPlain = new ArrayList<>();
        for (ReportedIssue x : outcome.reported()) {
            if (!pred.test(x)) {
                kept.add(x);
                keptPlain.add(x.issue());
            }
        }
        CheckResult old = outcome.result();
        outcome = new CheckRunner.Outcome(new CheckResult(old.scopeName(), keptPlain, old.unresolved(), old.exempted(), old.statistics()),
                kept, outcome.exempted());
        rebuildTree();
    }

    // ---------------------------------------------------------------- 导出

    private void export() {
        if (outcome == null) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("export.nothing"), MapperCheckerBundle.message("export.title"));
            return;
        }
        // 2025.2（build 252）起 (title, description, String...) 构造方法被标为弃用，官方替代是继承自
        // FileChooserDescriptor 的 withExtensionFilter(String, String...)，但那个方法是后来才加的 API，
        // 在插件最低支持的 2024.2.6 编译平台上还不存在，换了会导致编译失败（已实测）。
        // 弃用构造方法本身在方案支持的全部版本（2023.3–2026.2，Plugin Verifier 已逐一验证）里都能正常用，
        // 保留旧写法，只压掉这一处告警。
        @SuppressWarnings("deprecation")
        FileSaverDescriptor descriptor = new FileSaverDescriptor(
                MapperCheckerBundle.message("export.title"), MapperCheckerBundle.message("export.description"), "md", "csv");
        VirtualFile base = com.intellij.openapi.project.ProjectUtil.guessProjectDir(project);
        VirtualFileWrapper wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
                .save(base, "mybatis-mapper-checker-report.md");
        if (wrapper == null) {
            return;
        }
        String name = wrapper.getFile().getName().toLowerCase(Locale.ROOT);
        String content = name.endsWith(".csv") ? ReportExporter.toCsv(outcome.result()) : ReportExporter.toMarkdown(outcome.result());
        try {
            Files.writeString(wrapper.getFile().toPath(), content, StandardCharsets.UTF_8);
            Messages.showInfoMessage(project, MapperCheckerBundle.message("export.done", wrapper.getFile().getPath()),
                    MapperCheckerBundle.message("export.title"));
        } catch (IOException ex) {
            Messages.showErrorDialog(project, MapperCheckerBundle.message("export.failed", ex.getMessage()),
                    MapperCheckerBundle.message("export.title"));
        }
    }

    // ---------------------------------------------------------------- 渲染器

    private final class Renderer extends ColoredTreeCellRenderer {
        @Override
        public void customizeCellRenderer(@NotNull JTree t, Object value, boolean selected, boolean expanded,
                                          boolean leaf, int row, boolean hasFocus) {
            Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (o instanceof ReportedIssue ri) {
                ContractIssue i = ri.issue();
                boolean done = confirmed.contains(ri);
                SimpleTextAttributes main = done ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES;
                setIcon(i.ruleId() == RuleId.DAL_005 ? AllIcons.General.Error
                        : i.confidence() == Confidence.LOW ? AllIcons.General.Information : AllIcons.General.Warning);
                append(i.ruleId().code() + "  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
                if (!i.parameterName().isEmpty()) {
                    append(i.parameterName() + "  ", main.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null));
                }
                append(shortStatement(i.statementId()) + "  ", main);
                append(i.primaryLocation().display() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("[" + ReportExporter.confidence(i) + "]", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (!i.remark().isEmpty()) {
                    append("  " + i.remark(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
                appendBlame(ri);
                if (done) {
                    append(MapperCheckerBundle.message("report.confirmed.suffix"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            } else if (o instanceof ReportedExemption re) {
                ContractIssue i = re.reported().issue();
                setIcon(AllIcons.Actions.Checked);
                append(i.ruleId().code() + "  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
                if (!i.parameterName().isEmpty()) {
                    append(i.parameterName() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                append(shortStatement(i.statementId()) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(i.primaryLocation().display() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(re.exemption().signature() + "：" + re.exemption().reason(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                appendBlame(re.reported());
            } else if (o instanceof UnresolvedInvocation u) {
                setIcon(AllIcons.General.Note);
                append(MapperCheckerBundle.message("unresolved." + u.reason().name()) + "  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(shortStatement(u.statementId()) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(u.location().display(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (o != null) {
                append(o.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
            }
        }

        /**
         * 只读缓存，不在渲染时触发新的 annotate；没预热 / 没 VCS / 未提交都静默跳过，不占位置。
         * 拿文件走 primaryLocation（纯数据），不摸 PSI——渲染发生在 Swing 布局 / 绘制过程中，
         * 不在 read action 里，直接碰 PSI（哪怕只是 getContainingFile）会被线程模型断言拦下来。
         */
        private void appendBlame(ReportedIssue ri) {
            if (!showBlame) {
                return;
            }
            SourceLocation loc = ri.issue().primaryLocation();
            if (!loc.isKnown() || loc.line() <= 0) {
                return;
            }
            VirtualFile vf = CheckRunContext.findFileForNavigation(loc.filePath());
            if (vf == null) {
                return;
            }
            GitBlameService.BlameInfo info = GitBlameService.getInstance(project).blameLineCached(vf, loc.line());
            if (info != null) {
                append("  ·  " + info.display(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private String shortStatement(String fullId) {
            int dot = fullId.lastIndexOf('.');
            if (dot <= 0) {
                return fullId;
            }
            int prev = fullId.lastIndexOf('.', dot - 1);
            return prev < 0 ? fullId : fullId.substring(prev + 1);
        }
    }
}
