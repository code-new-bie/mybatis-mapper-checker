package com.mapperchecker.idea.index;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileBasedIndexExtension;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.mapperchecker.idea.mapper.MapperXmlParser;
import com.mapperchecker.idea.mapper.ParsedMapperFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * fullId → 单文件事实。statement、SQL 片段、parameterMap 都在这里，用 {@link IndexedElement#kind} 区分。
 * <p>
 * 输入过滤：所有 XML 文件；非 mapper / sqlMap 根标签的文件产出空 map，代价可忽略。
 * 覆盖项目内容与 library roots，因此依赖 jar 中的 Mapper XML 也能定位。
 */
public final class MapperStatementIndex extends FileBasedIndexExtension<String, IndexedElement> {

    public static final ID<String, IndexedElement> NAME = ID.create("com.mapperchecker.MapperStatementIndex");
    private static final int VERSION = 2;

    @Override
    public @NotNull ID<String, IndexedElement> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, IndexedElement, FileContent> getIndexer() {
        return inputData -> {
            ParsedMapperFile parsed = MapperXmlParser.parse(inputData.getPsiFile());
            if (parsed == null || parsed.elements().isEmpty()) {
                return Map.of();
            }
            Map<String, IndexedElement> result = new HashMap<>();
            for (IndexedElement e : parsed.elements()) {
                // 同文件内 databaseId 变体：同 fullId 多个元素，Index 一个 key 只能存一个值，
                // 用 fullId + "#" + databaseId 区分，查询时按前缀合并（见 keysFor）。
                String key = e.databaseId.isEmpty() ? e.fullId() : e.fullId() + "#" + e.databaseId;
                result.put(key, e);
            }
            return result;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull DataExternalizer<IndexedElement> getValueExternalizer() {
        return IndexedElement.EXTERNALIZER;
    }

    @Override
    public int getVersion() {
        return VERSION;
    }

    @Override
    public FileBasedIndex.@NotNull InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(XmlFileType.INSTANCE);
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    /** 同 fullId 可能有多个 key（databaseId 变体）。 */
    public static boolean keyMatches(String key, String fullId) {
        return key.equals(fullId) || key.startsWith(fullId + "#");
    }

    /** 取该 fullId 的全部 key（含 databaseId 变体）。 */
    public static List<String> keysFor(Project project, String fullId) {
        Collection<String> all = FileBasedIndex.getInstance().getAllKeys(NAME, project);
        return all.stream().filter(k -> keyMatches(k, fullId)).toList();
    }

    /** 在范围内查询某 key 的全部值与文件。 */
    public static void process(Project project, String key, GlobalSearchScope scope,
                               FileBasedIndex.ValueProcessor<IndexedElement> processor) {
        FileBasedIndex.getInstance().processValues(NAME, key, null, processor, scope);
    }
}
