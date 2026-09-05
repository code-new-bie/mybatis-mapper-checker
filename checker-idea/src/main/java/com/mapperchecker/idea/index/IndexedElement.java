package com.mapperchecker.idea.index;

import com.intellij.util.io.DataExternalizer;
import com.intellij.util.io.DataInputOutputUtil;
import com.intellij.util.io.IOUtil;
import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementSource;
import com.mapperchecker.core.model.StatementType;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Index 中存放的"单文件事实"。只含本标签内的直接引用，include 未展开。
 * 位置只存偏移，行号在展示时再算。
 */
public final class IndexedElement {

    public enum Kind { STATEMENT, FRAGMENT, PARAMETER_MAP }

    /**
     * 一处参数引用：根名、完整路径（如 query.poiId）、来源、在文件中的偏移范围。
     */
    public record Param(String name, String path, ParameterSourceType sourceType, int startOffset, int endOffset) {
        public Param {
            path = path == null || path.isEmpty() ? name : path;
        }
    }

    public final Kind kind;
    public final StatementSource source;
    public final String namespace;
    public final String id;
    public final StatementType type;
    public final String databaseId;
    public final String parameterMapRef;
    public final List<String> includeRefs;
    public final List<Param> params;
    /** parameterMap 的 property 列表；其他 kind 为空。 */
    public final List<String> properties;
    public final int startOffset;
    public final int endOffset;

    public IndexedElement(Kind kind, StatementSource source, String namespace, String id, StatementType type,
                          String databaseId, String parameterMapRef, List<String> includeRefs, List<Param> params,
                          List<String> properties, int startOffset, int endOffset) {
        this.kind = kind;
        this.source = source;
        this.namespace = namespace == null ? "" : namespace;
        this.id = id;
        this.type = type;
        this.databaseId = databaseId == null ? "" : databaseId;
        this.parameterMapRef = parameterMapRef == null ? "" : parameterMapRef;
        this.includeRefs = List.copyOf(includeRefs);
        this.params = List.copyOf(params);
        this.properties = List.copyOf(properties);
        this.startOffset = startOffset;
        this.endOffset = endOffset;
    }

    public String fullId() {
        return MapperStatement.fullId(namespace, id);
    }

    /** 转成 core 模型。 */
    public MapperStatement toMapperStatement(String filePath, String moduleName) {
        List<ParameterReference> refs = new ArrayList<>(params.size());
        for (Param p : params) {
            refs.add(ParameterReference.of(p.name(), p.path(), p.sourceType(),
                    SourceLocation.of(filePath, p.startOffset(), p.endOffset(), -1)));
        }
        return new MapperStatement(source, namespace, id, type, databaseId, refs, includeRefs, parameterMapRef,
                false, SourceLocation.of(filePath, startOffset, endOffset, -1), moduleName);
    }

    // FileBasedIndex 会校验序列化往返后的 equals / hashCode，必须按值比较。

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IndexedElement e)) {
            return false;
        }
        return kind == e.kind && source == e.source && type == e.type
                && startOffset == e.startOffset && endOffset == e.endOffset
                && namespace.equals(e.namespace) && id.equals(e.id)
                && databaseId.equals(e.databaseId) && parameterMapRef.equals(e.parameterMapRef)
                && includeRefs.equals(e.includeRefs) && params.equals(e.params) && properties.equals(e.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, source, namespace, id, type, databaseId, parameterMapRef,
                includeRefs, params, properties, startOffset, endOffset);
    }

    @Override
    public String toString() {
        return kind + " " + fullId() + (databaseId.isEmpty() ? "" : "#" + databaseId);
    }

    /** 序列化。字段增减必须同步提高索引 VERSION。 */
    public static final DataExternalizer<IndexedElement> EXTERNALIZER = new DataExternalizer<>() {
        @Override
        public void save(@NotNull DataOutput out, IndexedElement e) throws IOException {
            DataInputOutputUtil.writeINT(out, e.kind.ordinal());
            DataInputOutputUtil.writeINT(out, e.source.ordinal());
            IOUtil.writeUTF(out, e.namespace);
            IOUtil.writeUTF(out, e.id);
            DataInputOutputUtil.writeINT(out, e.type.ordinal());
            IOUtil.writeUTF(out, e.databaseId);
            IOUtil.writeUTF(out, e.parameterMapRef);
            DataInputOutputUtil.writeINT(out, e.includeRefs.size());
            for (String s : e.includeRefs) {
                IOUtil.writeUTF(out, s);
            }
            DataInputOutputUtil.writeINT(out, e.params.size());
            for (Param p : e.params) {
                IOUtil.writeUTF(out, p.name());
                IOUtil.writeUTF(out, p.path());
                DataInputOutputUtil.writeINT(out, p.sourceType().ordinal());
                DataInputOutputUtil.writeINT(out, p.startOffset());
                DataInputOutputUtil.writeINT(out, p.endOffset());
            }
            DataInputOutputUtil.writeINT(out, e.properties.size());
            for (String s : e.properties) {
                IOUtil.writeUTF(out, s);
            }
            DataInputOutputUtil.writeINT(out, e.startOffset);
            DataInputOutputUtil.writeINT(out, e.endOffset);
        }

        @Override
        public IndexedElement read(@NotNull DataInput in) throws IOException {
            Kind kind = Kind.values()[DataInputOutputUtil.readINT(in)];
            StatementSource source = StatementSource.values()[DataInputOutputUtil.readINT(in)];
            String namespace = IOUtil.readUTF(in);
            String id = IOUtil.readUTF(in);
            StatementType type = StatementType.values()[DataInputOutputUtil.readINT(in)];
            String databaseId = IOUtil.readUTF(in);
            String parameterMapRef = IOUtil.readUTF(in);
            int n = DataInputOutputUtil.readINT(in);
            List<String> includes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                includes.add(IOUtil.readUTF(in));
            }
            n = DataInputOutputUtil.readINT(in);
            List<Param> params = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String name = IOUtil.readUTF(in);
                String path = IOUtil.readUTF(in);
                ParameterSourceType st = ParameterSourceType.values()[DataInputOutputUtil.readINT(in)];
                int so = DataInputOutputUtil.readINT(in);
                int eo = DataInputOutputUtil.readINT(in);
                params.add(new Param(name, path, st, so, eo));
            }
            n = DataInputOutputUtil.readINT(in);
            List<String> props = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                props.add(IOUtil.readUTF(in));
            }
            int start = DataInputOutputUtil.readINT(in);
            int end = DataInputOutputUtil.readINT(in);
            return new IndexedElement(kind, source, namespace, id, type, databaseId, parameterMapRef,
                    includes, params, props, start, end);
        }
    };
}
