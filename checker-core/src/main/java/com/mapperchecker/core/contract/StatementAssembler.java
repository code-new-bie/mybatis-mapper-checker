package com.mapperchecker.core.contract;

import com.mapperchecker.core.model.MapperStatement;
import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.ParameterSourceType;
import com.mapperchecker.core.model.ResolvedStatement;
import com.mapperchecker.core.model.SourceLocation;
import com.mapperchecker.core.model.StatementType;

import java.util.ArrayList;
import com.mapperchecker.core.model.IncludeRef;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolver 阶段：把 Index 里的"单文件事实"组装成可比较的 {@link ResolvedStatement}。方案 8.4 / 8.7。
 * <ul>
 *   <li>展开 include（链式，循环检测）</li>
 *   <li>合并同 fullId 的多个定义（databaseId 变体、多文件）</li>
 *   <li>补入 iBatis 2 parameterMap 的 property</li>
 * </ul>
 * 跨文件查找通过 {@link Repository} 回调完成，core 不关心数据从哪来。
 */
public final class StatementAssembler {

    /** 跨文件查找回调，由 idea 层用 Index 实现。 */
    public interface Repository {

        /** 按 refid 查 SQL 片段。refid 可能是短 id，实现方需先用 currentNamespace 补全再回退到原样。 */
        List<MapperStatement> findFragments(String refid, String currentNamespace);

        /** iBatis 2 parameterMap 的 property 列表；找不到返回空。 */
        List<String> findParameterMapProperties(String ref, String currentNamespace);

        /** 该位置是否位于依赖 jar 中。 */
        boolean isLibraryLocation(SourceLocation location);
    }

    private final Repository repository;

    public StatementAssembler(Repository repository) {
        this.repository = repository;
    }

    /**
     * @param fullId      完整 id
     * @param definitions 同 fullId 的全部定义（至少一个）
     */
    public ResolvedStatement assemble(String fullId, List<MapperStatement> definitions) {
        Set<String> names = new LinkedHashSet<>();
        List<ParameterReference> refs = new ArrayList<>();
        List<SourceLocation> locations = new ArrayList<>();
        boolean partial = false;
        boolean library = false;
        StatementType type = StatementType.UNKNOWN;

        for (MapperStatement def : definitions) {
            if (type == StatementType.UNKNOWN) {
                type = def.type();
            }
            locations.add(def.location());
            library |= repository.isLibraryLocation(def.location());
            partial |= def.partiallyParsed();
            partial |= collect(def, def.namespace(), Map.of(), new HashSet<>(), names, refs, 0);
        }
        Set<String> paths = new LinkedHashSet<>();
        for (ParameterReference r : refs) {
            paths.add(r.propertyPath());
        }
        return new ResolvedStatement(fullId, type, names, paths, refs, locations, partial, library);
    }

    /**
     * 递归收集参数。
     *
     * @return 是否遇到循环或不可解析的 include（部分解析）
     */
    private boolean collect(MapperStatement st, String namespace, Map<String, String> properties, Set<String> visiting,
                            Set<String> names, List<ParameterReference> refs, int depth) {
        String key = st.fullId();
        if (!visiting.add(key)) {
            return true; // 循环
        }
        boolean partial = false;
        for (ParameterReference ref : st.directParameters()) {
            names.add(ref.rootName());
            refs.add(ref);
        }
        // 参数名里嵌了 ${} 的：用 include 传进来的 property 替换后再提取；给不全就只能算部分解析
        for (String template : st.templates()) {
            List<ParameterTemplate.Extracted> resolved = ParameterTemplate.resolve(template, properties);
            if (resolved == null) {
                partial = true;
                continue;
            }
            for (ParameterTemplate.Extracted e : resolved) {
                ParameterReference ref = ParameterReference.of(
                        com.mapperchecker.core.naming.ParameterNameNormalizer.rootName(e.path()),
                        e.path(), e.sourceType(), st.location());
                names.add(ref.rootName());
                refs.add(ref);
            }
        }
        if (!st.parameterMapRef().isEmpty()) {
            List<String> props = repository.findParameterMapProperties(st.parameterMapRef(), namespace);
            if (props.isEmpty()) {
                partial = true;
            }
            for (String p : props) {
                names.add(p);
                refs.add(ParameterReference.of(p, ParameterSourceType.XML_PARAMETER_MAP, st.location()));
            }
        }
        for (String encoded : st.includeRefs()) {
            IncludeRef include = IncludeRef.parse(encoded);
            String refid = include.refid();
            if (refid.contains("${") || refid.contains("$") && refid.indexOf('$') != refid.lastIndexOf('$')) {
                partial = true; // 动态 refid
                continue;
            }
            List<MapperStatement> fragments = repository.findFragments(refid, namespace);
            if (fragments.isEmpty()) {
                partial = true; // include 目标不存在，Mapper 自身有问题，不在此报
                continue;
            }
            if (depth > 32) {
                partial = true;
                continue;
            }
            // 外层传进来的 property 继续可见，本次 include 的同名值覆盖它（片段套片段时按 MyBatis 的直觉来）
            Map<String, String> childProps = properties.isEmpty() ? include.properties() : new LinkedHashMap<>(properties);
            if (!properties.isEmpty()) {
                childProps.putAll(include.properties());
            }
            for (MapperStatement frag : fragments) {
                partial |= collect(frag, frag.namespace(), childProps, visiting, names, refs, depth + 1);
            }
        }
        visiting.remove(key);
        return partial;
    }
}
