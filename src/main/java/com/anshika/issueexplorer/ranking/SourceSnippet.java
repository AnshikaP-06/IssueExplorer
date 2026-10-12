package com.anshika.issueexplorer.ranking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SourceSnippet {
    public record Line(int number, String text) { }
    public record Snippet(int startLine, int endLine, List<Line> lines) {
        public Snippet {
            lines = List.copyOf(lines);
        }
    }

    public Path sourceFile(Path root, String relativePath) throws IOException {
        GitHubPermalink.validatePath(relativePath);
        Path realRoot = root.toRealPath();
        Path candidate = realRoot.resolve(relativePath).normalize();
        for (Path part = candidate; part != null && !part.equals(realRoot); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("Source snippets cannot follow symbolic links.");
            }
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot) || !Files.isRegularFile(real)) {
            throw new IOException("Source file escapes the source directory or is not a regular file.");
        }
        return real;
    }

    public Snippet read(Path root, String relativePath, int startLine, int endLine, int maxLines) throws IOException {
        if (startLine <= 0 || endLine < startLine || maxLines <= 0) {
            throw new IllegalArgumentException("Snippet range and maximum line count must be positive and ordered.");
        }
        int last = (int) Math.min((long) endLine, (long) startLine + maxLines - 1);
        var lines = new ArrayList<Line>();
        int number = 0;
        try (var reader = Files.newBufferedReader(sourceFile(root, relativePath))) {
            String text;
            while (number < endLine && (text = reader.readLine()) != null) {
                number++;
                if (number >= startLine && number <= last) {
                    lines.add(new Line(number, text));
                }
            }
        }
        if (number < endLine) {
            throw new IOException("Stored method line range exceeds the source file; rebuild the index.");
        }
        return new Snippet(startLine, last, lines);
    }
}
