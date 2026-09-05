package com.mapperchecker.idea.index;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.mapperchecker.idea.mapper.MapperXmlParser;
import com.mapperchecker.idea.mapper.ParsedMapperFile;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

/**
 * namespace → 文件。用于判定"某接口是否有对应 XML"，以及枚举全部 Mapper namespace。
 */
public final class MapperNamespaceIndex extends ScalarIndexExtension<String> {

    public static final ID<String, Void> NAME = ID.create("com.mapperchecker.MapperNamespaceIndex");
    private static final int VERSION = 2;

    @Override
    public @NotNull ID<String, Void> getName() {
        return NAME;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            ParsedMapperFile parsed = MapperXmlParser.parse(inputData.getPsiFile());
            if (parsed == null || parsed.namespace().isEmpty()) {
                return Map.of();
            }
            // Map.of 不允许 null 值，ScalarIndex 的值固定为 null
            return java.util.Collections.singletonMap(parsed.namespace(), null);
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
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

    public static Collection<VirtualFile> filesFor(Project project, String namespace, GlobalSearchScope scope) {
        return FileBasedIndex.getInstance().getContainingFiles(NAME, namespace, scope);
    }

    public static boolean hasNamespace(Project project, String namespace, GlobalSearchScope scope) {
        return !filesFor(project, namespace, scope).isEmpty();
    }

    public static Collection<String> allNamespaces(Project project) {
        return FileBasedIndex.getInstance().getAllKeys(NAME, project);
    }
}
