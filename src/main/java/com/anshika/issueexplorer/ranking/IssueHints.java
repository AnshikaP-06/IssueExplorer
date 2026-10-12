package com.anshika.issueexplorer.ranking;

import java.util.List;

/** Evidence hints, preserving case and first-seen order within each category. */
public record IssueHints(List<String> classNames, List<String> javaFiles, List<StackFrame> stackFrames) {
    public IssueHints {
        classNames = List.copyOf(classNames);
        javaFiles = List.copyOf(javaFiles);
        stackFrames = List.copyOf(stackFrames);
    }

    public record StackFrame(String className, String methodName, String fileName, Integer lineNumber) { }
}
