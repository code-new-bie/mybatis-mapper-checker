package com.mapperchecker.idea.report;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.psi.PsiElement;
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
import com.mapperchecker.core.model.Statistics;
import com.mapperchecker.core.model.UnresolvedInvocation;
import com.mapperchecker.idea.MapperCheckerBundle;
import com.mapperchecker.idea.run.CheckRunner;
import com.mapperchecker.idea.run.CheckScope;
import com.mapperchecker.idea.run.ContractCheckTask;
import com.mapperchecker.idea.run.ReportedIssue;
import com.mapperchecker.idea.settings.MapperCheckerConfigurable;
import com.mapperchecker.idea.settings.MapperCheckerSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
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

    private @Nullable CheckRunner.Outcome outcome;
    private @Nullable CheckScope scope;

    public CheckReportPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        setBorder(JBUI.Borders.empty());

        ruleFilter.addItem(MapperCheckerBundle.message("report.filter.rule.all"));
        for (RuleId r : RuleId.values()) {
            ruleFilter.addItem(r.name());
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
        tree.addTreeSelectionListener(e -> showDetails(selectedIssue(), selectedUnresolved()));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    navigateToJava(selectedIssue(), selectedUnresolved());
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
                s.highIssues(), s.mediumIssues(), s.lowIssues()));
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
        PsiElement e = ri.javaElement();
        return com.mapperchecker.idea.util.Locations.moduleNameOf(e);
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

    private @Nullable UnresolvedInvocation selectedUnresolved() {
        TreePath p = tree.getSelectionPath();
        if (p == null) {
            return null;
        }
        Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof UnresolvedInvocation u ? u : null;
    }

    private void showDetails(@Nullable ReportedIssue ri, @Nullable UnresolvedInvocation u) {
        if (ri != null) {
            ContractIssue i = ri.issue();
            StringBuilder sb = new StringBuilder(i.message()).append("\n\n");
            sb.append(MapperCheckerBundle.message("report.detail.rule")).append("：").append(i.ruleId()).append('\n');
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
            PsiElement e = ri.javaElement();
            if (e != null && e.isValid()) {
                navigate(e);
            }
        } else if (u != null && u.location().isKnown()) {
            VirtualFile vf = com.mapperchecker.idea.run.CheckRunContext.findFileForNavigation(u.location().filePath());
            if (vf != null) {
                new OpenFileDescriptor(project, vf, Math.max(0, u.location().startOffset())).navigate(true);
            }
        }
    }

    private void navigateToMapper(@Nullable ReportedIssue ri) {
        if (ri == null) {
            return;
        }
        PsiElement e = ri.mapperElement();
        if (e != null && e.isValid()) {
            navigate(e);
        }
    }

    private void navigate(PsiElement e) {
        VirtualFile vf = e.getContainingFile() == null ? null : e.getContainingFile().getVirtualFile();
        if (vf != null) {
            new OpenFileDescriptor(project, vf, e.getTextRange().getStartOffset()).navigate(true);
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
                e.getPresentation().setEnabled(ri != null && ri.mapperElement() != null);
            }

            @Override
            public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
            }
        });
        g.addSeparator();
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
                return ri.issue().ruleId() == RuleId.MMC001;
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
        outcome = new CheckRunner.Outcome(new CheckResult(old.scopeName(), keptPlain, old.unresolved(), old.statistics()), kept);
        rebuildTree();
    }

    // ---------------------------------------------------------------- 导出

    private void export() {
        if (outcome == null) {
            Messages.showInfoMessage(project, MapperCheckerBundle.message("export.nothing"), MapperCheckerBundle.message("export.title"));
            return;
        }
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
                setIcon(i.ruleId() == RuleId.MMC002 ? AllIcons.General.Error
                        : i.confidence() == Confidence.LOW ? AllIcons.General.Information : AllIcons.General.Warning);
                append(i.ruleId().name() + "  ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
                if (!i.parameterName().isEmpty()) {
                    append(i.parameterName() + "  ", main.derive(SimpleTextAttributes.STYLE_BOLD, null, null, null));
                }
                append(shortStatement(i.statementId()) + "  ", main);
                append(i.primaryLocation().display() + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append("[" + ReportExporter.confidence(i) + "]", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                if (!i.remark().isEmpty()) {
                    append("  " + i.remark(), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
                }
                if (done) {
                    append(MapperCheckerBundle.message("report.confirmed.suffix"), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            } else if (o instanceof UnresolvedInvocation u) {
                setIcon(AllIcons.General.Note);
                append(MapperCheckerBundle.message("unresolved." + u.reason().name()) + "  ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                append(shortStatement(u.statementId()) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                append(u.location().display(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
            } else if (o != null) {
                append(o.toString(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
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
