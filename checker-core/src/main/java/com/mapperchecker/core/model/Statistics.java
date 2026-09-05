package com.mapperchecker.core.model;

/**
 * 一次检查的统计。可变，随任务推进累加。
 */
public final class Statistics {

    private int scannedFiles;
    private int mapperInterfaces;
    private int daoInvocations;
    private int resolvedInvocations;
    private int unresolvedInvocations;
    private int suppressedIssues;
    private int highIssues;
    private int mediumIssues;
    private int lowIssues;

    public void incScannedFiles() { scannedFiles++; }
    public void incMapperInterfaces() { mapperInterfaces++; }
    public void incDaoInvocations() { daoInvocations++; }
    public void incResolvedInvocations() { resolvedInvocations++; }
    public void incUnresolvedInvocations() { unresolvedInvocations++; }
    public void incSuppressedIssues() { suppressedIssues++; }

    public void countIssue(ContractIssue issue) {
        switch (issue.confidence()) {
            case HIGH -> highIssues++;
            case MEDIUM -> mediumIssues++;
            case LOW -> lowIssues++;
        }
    }

    public int scannedFiles() { return scannedFiles; }
    public int mapperInterfaces() { return mapperInterfaces; }
    public int daoInvocations() { return daoInvocations; }
    public int resolvedInvocations() { return resolvedInvocations; }
    public int unresolvedInvocations() { return unresolvedInvocations; }
    public int suppressedIssues() { return suppressedIssues; }
    public int highIssues() { return highIssues; }
    public int mediumIssues() { return mediumIssues; }
    public int lowIssues() { return lowIssues; }
    public int totalIssues() { return highIssues + mediumIssues + lowIssues; }
}
