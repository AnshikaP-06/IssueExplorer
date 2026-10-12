package com.anshika.issueexplorer.ranking;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Extracts lexical Java hints; it never executes snippets or interprets them as instructions. */
public final class IssueAnalyzer {
    private static final String IDENTIFIER = "[\\p{L}_$][\\p{L}\\p{N}_$]*";
    private static final Pattern WORD = Pattern.compile("(?<![\\p{L}\\p{N}_$])" + IDENTIFIER);
    private static final Pattern JAVA_FILE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$-])([\\p{L}_$][\\p{L}\\p{N}_$-]*\\.java)(?![\\p{L}\\p{N}_$.])");
    private static final Pattern FRAME = Pattern.compile(
            "(?<![\\p{L}\\p{N}_$])at[ \\t]+([^\\s()]+)\\(([^()\\r\\n]+)\\)");
    private static final Pattern JAVA_SYNTAX = Pattern.compile(
            "\\b(?:class|interface|enum|record|import|package|extends|implements|throws)\\s+" + IDENTIFIER
                    + "|\\bnew\\s+" + IDENTIFIER + "\\s*\\("
                    + "|\\b[A-Z][\\w$]*\\.[\\w$]+\\s*\\(");
    private static final Pattern FENCE = Pattern.compile("^[ \\t]{0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern INDENTED = Pattern.compile("^(?: {4}|\\t).*$");

    public IssueHints analyze(String title, String body) {
        String text = relevantText((title == null ? "" : title) + "\n" + (body == null ? "" : body));
        var classes = new LinkedHashSet<String>();
        var files = new LinkedHashSet<String>();
        var frames = new LinkedHashSet<IssueHints.StackFrame>();
        var words = WORD.matcher(text);
        while (words.find()) {
            String word = words.group();
            if (camelClass(word)) {
                classes.add(word);
            }
        }
        var filenames = JAVA_FILE.matcher(text);
        while (filenames.find()) {
            String file = filenames.group(1);
            files.add(file);
            String name = file.substring(0, file.length() - 5);
            if (name.matches(IDENTIFIER) && Character.isUpperCase(name.codePointAt(0))) {
                classes.add(name);
            }
        }
        var matches = FRAME.matcher(text);
        while (matches.find()) {
            var frame = parseFrame(matches.group(1), matches.group(2));
            if (frame != null) {
                frames.add(frame);
                String name = frame.className();
                classes.add(name.substring(name.lastIndexOf('.') + 1));
            }
        }
        return new IssueHints(List.copyOf(classes), List.copyOf(files), List.copyOf(frames));
    }

    private boolean camelClass(String word) {
        if (!Character.isUpperCase(word.codePointAt(0))) {
            return false;
        }
        return word.codePoints().filter(Character::isUpperCase).count() >= 2
                && word.codePoints().anyMatch(Character::isLowerCase);
    }

    private IssueHints.StackFrame parseFrame(String callable, String location) {
        // Java 9+ module/classloader prefixes end in a slash, before the qualified class name.
        String qualified = callable.substring(callable.lastIndexOf('/') + 1);
        int separator = qualified.lastIndexOf('.');
        if (separator <= 0) {
            return null;
        }
        String type = qualified.substring(0, separator);
        String method = qualified.substring(separator + 1);
        if (!type.matches(IDENTIFIER + "(?:\\." + IDENTIFIER + ")*")
                || !(method.matches(IDENTIFIER) || method.equals("<init>") || method.equals("<clinit>"))) {
            return null;
        }
        if (location.equals("Unknown Source") || location.equals("Native Method")) {
            return new IssueHints.StackFrame(type, method, null, null);
        }
        var source = Pattern.compile("(" + IDENTIFIER + "\\.java):(\\d+)").matcher(location);
        if (!source.matches()) {
            return null;
        }
        try {
            int line = Integer.parseInt(source.group(2));
            return line > 0 ? new IssueHints.StackFrame(type, method, source.group(1), line) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String relevantText(String text) {
        var kept = new StringBuilder();
        var block = new StringBuilder();
        char fence = 0;
        int fenceLength = 0;
        String language = "";
        for (String line : text.split("\\R", -1)) {
            var marker = FENCE.matcher(line);
            if (fence != 0) {
                if (marker.matches() && marker.group(1).charAt(0) == fence
                        && marker.group(1).length() >= fenceLength && marker.group(2).isBlank()) {
                    appendRelevantBlock(kept, block, language);
                    fence = 0;
                } else {
                    block.append(line).append('\n');
                }
            } else if (marker.matches()) {
                appendRelevantBlock(kept, block, "");
                fence = marker.group(1).charAt(0);
                fenceLength = marker.group(1).length();
                language = marker.group(2).strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            } else if (INDENTED.matcher(line).matches()) {
                block.append(line).append('\n');
            } else {
                appendRelevantBlock(kept, block, "");
                kept.append(line).append('\n');
            }
        }
        appendRelevantBlock(kept, block, fence == 0 ? "" : language);
        return kept.toString();
    }

    private void appendRelevantBlock(StringBuilder kept, StringBuilder block, String language) {
        if (block.isEmpty()) {
            return;
        }
        String content = block.toString();
        boolean javaLanguage = Set.of("", "java", "jdk").contains(language);
        if (JAVA_FILE.matcher(content).find() || hasStackFrame(content)
                || (javaLanguage && JAVA_SYNTAX.matcher(content).find())) {
            kept.append(content);
        }
        kept.append('\n');
        block.setLength(0);
    }

    private boolean hasStackFrame(String text) {
        var matches = FRAME.matcher(text);
        while (matches.find()) {
            if (parseFrame(matches.group(1), matches.group(2)) != null) {
                return true;
            }
        }
        return false;
    }
}
