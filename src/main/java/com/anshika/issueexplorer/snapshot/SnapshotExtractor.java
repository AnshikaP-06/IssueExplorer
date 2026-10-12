package com.anshika.issueexplorer.snapshot;

import com.anshika.issueexplorer.github.GitHubClient.GitHubException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SnapshotExtractor {
    public static final int DEFAULT_MAX_FILES = com.anshika.issueexplorer.config.AppConfig.defaults().maxFiles();
    public static final long DEFAULT_MAX_EXTRACTED_BYTES = com.anshika.issueexplorer.config.AppConfig.defaults().maxExtractedBytes();
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target", "build", ".git", "node_modules", "generated", "generated-sources", "generated-test-sources");

    public record ExtractionResult(Path sourceDirectory, int kept, long extractedBytes,
                                   Map<String, Integer> skippedByReason) {
        public int skipped() {
            return skippedByReason.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public ExtractionResult extract(Path archive, Path target, int maxFiles, long maxBytes) throws GitHubException {
        if (maxFiles <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Extraction file-count and size limits must be greater than zero.");
        }
        Path destination = target.toAbsolutePath().normalize();
        if (destination.getParent() == null || destination.equals(archive.toAbsolutePath().normalize())
                || archive.toAbsolutePath().normalize().startsWith(destination)) {
            throw new IllegalArgumentException("Extraction target must be separate from the archive.");
        }
        Path staging = null;
        Path previous = null;
        boolean published = false;
        try {
            rejectFilesystemSymlinks(destination);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(destination)) {
                throw new IOException("Extraction target is not a directory.");
            }
            List<Boolean> symlinks = ZipEntryModes.symlinks(archive);
            try (var zip = new ZipFile(archive.toFile())) {
                List<? extends ZipEntry> entries = Collections.list(zip.entries());
                if (entries.size() != symlinks.size()) {
                    throw new IOException("ZIP entry metadata is inconsistent.");
                }
                Set<String> links = new HashSet<>();
                // Validate every path, even entries that filtering would otherwise skip.
                for (int i = 0; i < entries.size(); i++) {
                    String name = entries.get(i).getName();
                    validatePath(name, destination);
                    if (symlinks.get(i)) {
                        links.add(name.endsWith("/") ? name.substring(0, name.length() - 1) : name);
                    }
                }
                String root = archiveRoot(entries);
                Files.createDirectories(destination.getParent());
                staging = Files.createTempDirectory(destination.getParent(), ".extract-");
                var skipped = new LinkedHashMap<String, Integer>();
                skipped.put("symlinks", 0);
                skipped.put("excluded directories", 0);
                skipped.put("non-Java files", 0);
                int kept = 0;
                long total = 0;
                for (int i = 0; i < entries.size(); i++) {
                    var entry = entries.get(i);
                    String name = entry.getName();
                    boolean beneathLink = links.stream().anyMatch(link -> name.equals(link) || name.startsWith(link + "/"));
                    if (symlinks.get(i) || beneathLink) {
                        increment(skipped, "symlinks");
                        continue;
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String relative = name.substring(root.length());
                    Path output = validatePath(relative, staging);
                    if (excludedDirectory(relative)) {
                        increment(skipped, "excluded directories");
                        continue;
                    }
                    if (!relative.endsWith(".java")) {
                        increment(skipped, "non-Java files");
                        continue;
                    }
                    if (kept >= maxFiles) {
                        throw new GitHubException("Extraction exceeds the maximum Java file count of " + maxFiles + ".", 5);
                    }
                    if (entry.getSize() > maxBytes - total) {
                        throw sizeExceeded(maxBytes);
                    }
                    Files.createDirectories(output.getParent());
                    CRC32 checksum = new CRC32();
                    long fileBytes = 0;
                    try (var input = zip.getInputStream(entry);
                         var stream = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted()) {
                                throw new GitHubException("Archive extraction was interrupted.", 1);
                            }
                            if (read > maxBytes - total) {
                                throw sizeExceeded(maxBytes);
                            }
                            stream.write(buffer, 0, read);
                            checksum.update(buffer, 0, read);
                            fileBytes += read;
                            total += read;
                        }
                    }
                    if (fileBytes != entry.getSize() || checksum.getValue() != entry.getCrc()) {
                        throw new IOException("ZIP entry length or checksum mismatch.");
                    }
                    kept++;
                }
                // Build in a fresh directory. Failed extraction never changes an existing source tree.
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    previous = Files.createTempDirectory(destination.getParent(), ".previous-");
                    Files.delete(previous);
                    Files.move(destination, previous);
                }
                try {
                    Files.move(staging, destination);
                    staging = null;
                    published = true;
                } catch (IOException exception) {
                    if (previous != null) {
                        Files.move(previous, destination);
                        previous = null;
                    }
                    throw exception;
                }
                return new ExtractionResult(destination, kept, total, Collections.unmodifiableMap(skipped));
            }
        } catch (IOException exception) {
            throw new GitHubException("Could not safely extract archive: " + exception.getMessage(), 1);
        } finally {
            deleteTemporaryTree(staging, destination.getParent());
            if (published) {
                deleteTemporaryTree(previous, destination.getParent());
            }
        }
    }

    private Path validatePath(String name, Path target) throws GitHubException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw unsafePath();
        }
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        for (String part : trimmed.split("/", -1)) {
            String base = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")
                    || part.chars().anyMatch(character -> character < 32)
                    || base.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
                throw unsafePath();
            }
        }
        try {
            Path result = target.resolve(trimmed).normalize();
            if (!result.startsWith(target) || result.equals(target)) {
                throw unsafePath();
            }
            return result;
        } catch (java.nio.file.InvalidPathException exception) {
            throw unsafePath();
        }
    }

    private GitHubException unsafePath() {
        return new GitHubException("Unsafe ZIP entry rejected (zip-slip or invalid archive path).", 2);
    }

    private String archiveRoot(List<? extends ZipEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        String first = entries.getFirst().getName();
        int slash = first.indexOf('/');
        if (slash < 0) {
            return "";
        }
        if (EXCLUDED_DIRECTORIES.contains(first.substring(0, slash).toLowerCase(Locale.ROOT))) {
            return "";
        }
        String prefix = first.substring(0, slash + 1);
        return entries.stream().allMatch(entry -> entry.getName().startsWith(prefix)) ? prefix : "";
    }

    private boolean excludedDirectory(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (EXCLUDED_DIRECTORIES.contains(parts[i].toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void increment(Map<String, Integer> counts, String reason) {
        counts.compute(reason, (key, value) -> value + 1);
    }

    private GitHubException sizeExceeded(long bytes) {
        return new GitHubException("Extraction exceeds the maximum total extracted size of " + bytes + " bytes.", 5);
    }

    private void rejectFilesystemSymlinks(Path target) throws IOException {
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) {
                throw new IOException("Extraction target or parent is a symbolic link.");
            }
        }
    }

    private void deleteTemporaryTree(Path temporary, Path parent) {
        if (temporary == null || !temporary.getParent().equals(parent)
                || !(temporary.getFileName().toString().startsWith(".extract-")
                || temporary.getFileName().toString().startsWith(".previous-"))) {
            return;
        }
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temporary directories never count as published source trees.
        }
    }
}


