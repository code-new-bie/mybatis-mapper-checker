package com.mapperchecker.core.model;

import java.util.List;
import java.util.Objects;

/**
 * 一次 DAO 调用（或一个 Mapper 接口方法声明）。
 *
 * @param kind        来源形态
 * @param operation   操作类型
 * @param statementId 已解析的完整 id；未解析时为 null
 * @param parameters  Java 侧参数集合；无法确定时为 null（与空集合区分：空集合表示确定没有参数）
 * @param callPath    跨方法追踪路径，如 [OrderDao.query, OrderDao.buildParams]；无则为空列表
 * @param location    位置
 * @param moduleName  所属模块名
 */
public record DaoInvocation(
        InvocationKind kind,
        Operation operation,
        String statementId,
        List<ParameterReference> parameters,
        List<String> callPath,
        SourceLocation location,
        String moduleName) {

    public DaoInvocation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(location, "location");
        parameters = parameters == null ? null : List.copyOf(parameters);
        callPath = callPath == null ? List.of() : List.copyOf(callPath);
        moduleName = moduleName == null ? "" : moduleName;
    }

    public boolean hasStatementId() {
        return statementId != null && !statementId.isBlank();
    }

    /** Java 侧参数是否可比较。null 表示无法确定（单标量 / 单 Bean 无 @Param 等）。 */
    public boolean hasComparableParameters() {
        return parameters != null;
    }

    /** 展示用短名：OrderMapper.queryOrder。 */
    public String shortStatementId() {
        if (statementId == null) {
            return "";
        }
        int dot = statementId.lastIndexOf('.');
        if (dot <= 0) {
            return statementId;
        }
        int prevDot = statementId.lastIndexOf('.', dot - 1);
        return prevDot < 0 ? statementId : statementId.substring(prevDot + 1);
    }
}
