package com.mapperchecker.core.model;

import java.util.Objects;

/**
 * 源码位置。core 层不认识 PsiElement，只用路径 + 偏移表示。
 *
 * @param filePath    文件路径（展示用，可以是相对项目根的路径）
 * @param startOffset 起始偏移（字符），未知时为 -1
 * @param endOffset   结束偏移（字符），未知时为 -1
 * @param line        1 起始的行号，未知时为 -1
 */
public record SourceLocation(String filePath, int startOffset, int endOffset, int line) {

    public static final SourceLocation UNKNOWN = new SourceLocation("", -1, -1, -1);

    public SourceLocation {
        Objects.requireNonNull(filePath, "filePath");
    }

    public static SourceLocation of(String filePath, int startOffset, int endOffset, int line) {
        return new SourceLocation(filePath, startOffset, endOffset, line);
    }

    public boolean isKnown() {
        return !filePath.isEmpty() && startOffset >= 0;
    }

    /** 文件名部分，用于报告展示。 */
    public String fileName() {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash < 0 ? filePath : filePath.substring(slash + 1);
    }

    /** 形如 OrderMapper.java:18 的展示串。 */
    public String display() {
        return line > 0 ? fileName() + ":" + line : fileName();
    }
}
