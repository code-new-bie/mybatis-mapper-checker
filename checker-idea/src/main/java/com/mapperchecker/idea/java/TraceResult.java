package com.mapperchecker.idea.java;

import com.mapperchecker.core.model.ParameterReference;
import com.mapperchecker.core.model.UnresolvedReason;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 参数对象数据流追踪结果。
 *
 * @param status     结果类型
 * @param parameters RESOLVED 时的参数集合
 * @param reason     UNRESOLVED 时的原因
 * @param detail     补充说明
 * @param callPath   跨方法路径（Class.method()），无则为空
 */
public record TraceResult(Status status, List<ParameterReference> parameters, @Nullable UnresolvedReason reason,
                          String detail, List<String> callPath) {

    public enum Status {
        /** 追踪成功，parameters 有效。 */
        RESOLVED,
        /** 无法证明，不报。 */
        UNRESOLVED,
        /** 不可比较（单标量等），不报也不算无法解析。 */
        NOT_COMPARABLE
    }

    public TraceResult {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        detail = detail == null ? "" : detail;
        callPath = callPath == null ? List.of() : List.copyOf(callPath);
    }

    public static TraceResult resolved(List<ParameterReference> params, List<String> callPath) {
        return new TraceResult(Status.RESOLVED, params, null, "", callPath);
    }

    public static TraceResult unresolved(UnresolvedReason reason, String detail) {
        return new TraceResult(Status.UNRESOLVED, List.of(), reason, detail, List.of());
    }

    public static TraceResult notComparable() {
        return new TraceResult(Status.NOT_COMPARABLE, List.of(), null, "", List.of());
    }

    public boolean isResolved() {
        return status == Status.RESOLVED;
    }
}
