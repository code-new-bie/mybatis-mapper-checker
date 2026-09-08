package com.mapperchecker.idea.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import com.mapperchecker.core.contract.CheckSettings;
import com.mapperchecker.core.model.RuleId;
import com.mapperchecker.core.model.Severity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Project 级设置，持久化到 .idea/mybatis-mapper-checker.xml，可提交版本库。方案 15.2。
 */
@Service(Service.Level.PROJECT)
@State(name = "MyBatisMapperCheckerSettings", storages = @Storage("mybatis-mapper-checker.xml"))
public final class MapperCheckerSettings implements PersistentStateComponent<MapperCheckerSettings.State> {

    /** 可序列化状态。字段必须 public 且有默认值。 */
    public static final class State {
        public List<String> ignoredParameters = new ArrayList<>();
        public List<String> ignoredStatements = new ArrayList<>();
        public List<String> suppressedPairs = new ArrayList<>();
        public List<String> ignoredPaths = new ArrayList<>();
        public int traceDepth = CheckSettings.DEFAULT_TRACE_DEPTH;
        public boolean strictVisibility = true;
        public List<String> disabledRules = new ArrayList<>();
        /** 规则 id → 级别名。 */
        public Map<String, String> severityOverrides = new HashMap<>();
        public boolean showGutterIcon = false;
        public boolean ignoreBuiltinPagination = true;
        public boolean checkBeanProperties = true;
        /** 团队规范规则选项。 */
        public List<String> queryClassSuffixes = new ArrayList<>();
        /** 每行 声明类#方法=源参数下标。 */
        public List<String> copyMethods = new ArrayList<>();
        public List<String> templateStatementIds = new ArrayList<>();
        /** 纯 Java 规则（DAL-004 / 020 / 022 / 030）是否在编辑器里实时提示，默认关。 */
        public boolean realtimeJavaRules = false;
        /** 是否分析"上游有没有真的赋值"来调整 DAL-001 / DAL-010 的置信度与备注，默认开。 */
        public boolean upstreamAssignmentAnalysis = true;
        public String lastScope = "PROJECT";
        public String exportPath = "";
    }

    private State state = new State();

    public static MapperCheckerSettings getInstance(@NotNull Project project) {
        return project.getService(MapperCheckerSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
    }

    public State state() {
        return state;
    }

    /** 转成 core 只读快照。 */
    public CheckSettings toCheckSettings() {
        Set<RuleId> disabled = new HashSet<>();
        for (String r : state.disabledRules) {
            RuleId id = RuleId.fromCode(r);
            if (id != null) {
                disabled.add(id);
            }
        }
        Map<RuleId, Severity> severities = new EnumMap<>(RuleId.class);
        for (Map.Entry<String, String> e : state.severityOverrides.entrySet()) {
            RuleId id = RuleId.fromCode(e.getKey());
            if (id == null) {
                continue;
            }
            try {
                severities.put(id, Severity.valueOf(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                // 忽略
            }
        }
        return new CheckSettings(
                new ArrayList<>(state.ignoredParameters),
                new HashSet<>(state.ignoredStatements),
                new HashSet<>(state.suppressedPairs),
                new ArrayList<>(state.ignoredPaths),
                state.traceDepth,
                state.strictVisibility,
                disabled,
                severities,
                state.ignoreBuiltinPagination,
                state.checkBeanProperties,
                new com.mapperchecker.core.contract.RuleOptions(
                        state.queryClassSuffixes,
                        com.mapperchecker.core.contract.RuleOptions.parseCopyMethodLines(state.copyMethods),
                        new HashSet<>(state.templateStatementIds),
                        null),
                state.upstreamAssignmentAnalysis);
    }

    // ---- 报告右键写入 ----

    public void addIgnoredParameter(String name) {
        if (!state.ignoredParameters.contains(name)) {
            state.ignoredParameters.add(name);
        }
    }

    public void addIgnoredStatement(String fullId) {
        if (!state.ignoredStatements.contains(fullId)) {
            state.ignoredStatements.add(fullId);
        }
    }

    public void addSuppressedPair(String key) {
        if (!state.suppressedPairs.contains(key)) {
            state.suppressedPairs.add(key);
        }
    }
}
