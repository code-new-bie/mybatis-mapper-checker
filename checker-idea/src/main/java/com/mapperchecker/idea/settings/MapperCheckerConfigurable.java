package com.mapperchecker.idea.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;
import com.mapperchecker.idea.MapperCheckerBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings → Tools → MyBatis Mapper Checker。方案 15.2。
 */
public final class MapperCheckerConfigurable implements Configurable {

    private final Project project;

    private JPanel root;
    private JBTextArea ignoredParameters;
    private JBTextArea ignoredStatements;
    private JBTextArea suppressedPairs;
    private JBTextArea ignoredPaths;
    private JSpinner traceDepth;
    private JBRadioButton strict;
    private JBRadioButton lenient;
    private JBCheckBox gutterIcon;
    private JBCheckBox ignorePagination;
    private JBCheckBox checkBeanProperties;
    private JBTextArea querySuffixes;
    private JBTextArea copyMethods;
    private JBTextArea templateIds;
    private JBCheckBox realtimeJavaRules;
    private final Map<RuleId, JBCheckBox> ruleEnabled = new EnumMap<>(RuleId.class);
    private final Map<RuleId, JComboBox<Severity>> ruleSeverity = new EnumMap<>(RuleId.class);

    public MapperCheckerConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return MapperCheckerBundle.message("settings.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        ignoredParameters = area();
        ignoredStatements = area();
        suppressedPairs = area();
        ignoredPaths = area();
        traceDepth = new JSpinner(new SpinnerNumberModel(3, 1, 10, 1));
        strict = new JBRadioButton(MapperCheckerBundle.message("settings.visibility.strict"));
        lenient = new JBRadioButton(MapperCheckerBundle.message("settings.visibility.lenient"));
        ButtonGroup g = new ButtonGroup();
        g.add(strict);
        g.add(lenient);
        JPanel visibility = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        visibility.add(strict);
        visibility.add(lenient);
        gutterIcon = new JBCheckBox(MapperCheckerBundle.message("settings.gutter.icon"));
        ignorePagination = new JBCheckBox(MapperCheckerBundle.message("settings.ignore.pagination"));
        checkBeanProperties = new JBCheckBox(MapperCheckerBundle.message("settings.check.bean.properties"));
        querySuffixes = area();
        copyMethods = area();
        templateIds = area();
        realtimeJavaRules = new JBCheckBox(MapperCheckerBundle.message("settings.realtime.java.rules"));

        JPanel rules = new JPanel(new GridLayout(RuleId.values().length + 1, 3, 8, 2));
        rules.add(new JBLabel(MapperCheckerBundle.message("settings.rule.column.rule")));
        rules.add(new JBLabel(MapperCheckerBundle.message("settings.rule.enabled")));
        rules.add(new JBLabel(MapperCheckerBundle.message("settings.rule.severity")));
        for (RuleId r : RuleId.values()) {
            rules.add(new JBLabel(r.code() + "  " + MapperCheckerBundle.message("rule." + r.code() + ".name")));
            JBCheckBox cb = new JBCheckBox();
            ruleEnabled.put(r, cb);
            rules.add(cb);
            JComboBox<Severity> combo = new JComboBox<>(Severity.values());
            ruleSeverity.put(r, combo);
            rules.add(combo);
        }

        root = FormBuilder.createFormBuilder()
                .addComponent(new JBLabel("<html><b>" + MapperCheckerBundle.message("settings.general") + "</b></html>"))
                .addLabeledComponent(MapperCheckerBundle.message("settings.visibility.mode"), visibility)
                .addLabeledComponent(MapperCheckerBundle.message("settings.trace.depth"), traceDepth)
                .addComponent(gutterIcon)
                .addComponent(ignorePagination)
                .addComponent(checkBeanProperties)
                .addSeparator()
                .addComponent(new JBLabel("<html><b>" + MapperCheckerBundle.message("settings.rules") + "</b></html>"))
                .addComponent(rules)
                .addSeparator()
                .addComponent(new JBLabel("<html><b>" + MapperCheckerBundle.message("settings.rules.section") + "</b></html>"))
                .addLabeledComponent(MapperCheckerBundle.message("settings.query.suffixes"), scroll(querySuffixes), true)
                .addLabeledComponent(MapperCheckerBundle.message("settings.copy.methods"), scroll(copyMethods), true)
                .addLabeledComponent(MapperCheckerBundle.message("settings.template.ids"), scroll(templateIds), true)
                .addComponent(realtimeJavaRules)
                .addSeparator()
                .addComponent(new JBLabel("<html><b>" + MapperCheckerBundle.message("settings.suppress") + "</b></html>"))
                .addLabeledComponent(MapperCheckerBundle.message("settings.ignored.parameters"), scroll(ignoredParameters), true)
                .addLabeledComponent(MapperCheckerBundle.message("settings.ignored.statements"), scroll(ignoredStatements), true)
                .addLabeledComponent(MapperCheckerBundle.message("settings.suppressed.pairs"), scroll(suppressedPairs), true)
                .addLabeledComponent(MapperCheckerBundle.message("settings.ignored.paths"), scroll(ignoredPaths), true)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        root.setBorder(JBUI.Borders.empty(8));
        reset();
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(new JBScrollPane(root), BorderLayout.CENTER);
        return wrapper;
    }

    private static JBTextArea area() {
        JBTextArea a = new JBTextArea(4, 40);
        return a;
    }

    private static JComponent scroll(JBTextArea a) {
        JBScrollPane sp = new JBScrollPane(a);
        sp.setPreferredSize(new Dimension(400, 80));
        return sp;
    }

    @Override
    public boolean isModified() {
        MapperCheckerSettings.State s = MapperCheckerSettings.getInstance(project).state();
        if (!lines(ignoredParameters).equals(s.ignoredParameters)) return true;
        if (!lines(ignoredStatements).equals(s.ignoredStatements)) return true;
        if (!lines(suppressedPairs).equals(s.suppressedPairs)) return true;
        if (!lines(ignoredPaths).equals(s.ignoredPaths)) return true;
        if ((int) traceDepth.getValue() != s.traceDepth) return true;
        if (strict.isSelected() != s.strictVisibility) return true;
        if (gutterIcon.isSelected() != s.showGutterIcon) return true;
        if (ignorePagination.isSelected() != s.ignoreBuiltinPagination) return true;
        if (checkBeanProperties.isSelected() != s.checkBeanProperties) return true;
        if (!lines(querySuffixes).equals(s.queryClassSuffixes)) return true;
        if (!lines(copyMethods).equals(s.copyMethods)) return true;
        if (!lines(templateIds).equals(s.templateStatementIds)) return true;
        if (realtimeJavaRules.isSelected() != s.realtimeJavaRules) return true;
        for (RuleId r : RuleId.values()) {
            boolean enabled = !s.disabledRules.contains(r.name());
            if (ruleEnabled.get(r).isSelected() != enabled) return true;
            Severity sev = severityOf(s, r);
            if (ruleSeverity.get(r).getSelectedItem() != sev) return true;
        }
        return false;
    }

    @Override
    public void apply() {
        MapperCheckerSettings.State s = MapperCheckerSettings.getInstance(project).state();
        s.ignoredParameters = new ArrayList<>(lines(ignoredParameters));
        s.ignoredStatements = new ArrayList<>(lines(ignoredStatements));
        s.suppressedPairs = new ArrayList<>(lines(suppressedPairs));
        s.ignoredPaths = new ArrayList<>(lines(ignoredPaths));
        s.traceDepth = (int) traceDepth.getValue();
        s.strictVisibility = strict.isSelected();
        s.showGutterIcon = gutterIcon.isSelected();
        s.ignoreBuiltinPagination = ignorePagination.isSelected();
        s.checkBeanProperties = checkBeanProperties.isSelected();
        s.queryClassSuffixes = new ArrayList<>(lines(querySuffixes));
        s.copyMethods = new ArrayList<>(lines(copyMethods));
        s.templateStatementIds = new ArrayList<>(lines(templateIds));
        s.realtimeJavaRules = realtimeJavaRules.isSelected();
        List<String> disabled = new ArrayList<>();
        Map<String, String> severities = new HashMap<>();
        for (RuleId r : RuleId.values()) {
            if (!ruleEnabled.get(r).isSelected()) {
                disabled.add(r.name());
            }
            Severity sev = (Severity) ruleSeverity.get(r).getSelectedItem();
            if (sev != null && sev != r.defaultSeverity()) {
                severities.put(r.name(), sev.name());
            }
        }
        s.disabledRules = disabled;
        s.severityOverrides = severities;
    }

    @Override
    public void reset() {
        MapperCheckerSettings.State s = MapperCheckerSettings.getInstance(project).state();
        ignoredParameters.setText(String.join("\n", s.ignoredParameters));
        ignoredStatements.setText(String.join("\n", s.ignoredStatements));
        suppressedPairs.setText(String.join("\n", s.suppressedPairs));
        ignoredPaths.setText(String.join("\n", s.ignoredPaths));
        traceDepth.setValue(Math.max(1, s.traceDepth));
        strict.setSelected(s.strictVisibility);
        lenient.setSelected(!s.strictVisibility);
        gutterIcon.setSelected(s.showGutterIcon);
        ignorePagination.setSelected(s.ignoreBuiltinPagination);
        checkBeanProperties.setSelected(s.checkBeanProperties);
        querySuffixes.setText(String.join("\n", s.queryClassSuffixes));
        copyMethods.setText(String.join("\n", s.copyMethods));
        templateIds.setText(String.join("\n", s.templateStatementIds));
        realtimeJavaRules.setSelected(s.realtimeJavaRules);
        for (RuleId r : RuleId.values()) {
            ruleEnabled.get(r).setSelected(!s.disabledRules.contains(r.name()));
            ruleSeverity.get(r).setSelectedItem(severityOf(s, r));
        }
    }

    private static Severity severityOf(MapperCheckerSettings.State s, RuleId r) {
        String v = s.severityOverrides.get(r.name());
        if (v != null) {
            try {
                return Severity.valueOf(v);
            } catch (IllegalArgumentException ignored) {
                // 忽略
            }
        }
        return r.defaultSeverity();
    }

    private static List<String> lines(JBTextArea a) {
        List<String> out = new ArrayList<>();
        for (String line : a.getText().split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
