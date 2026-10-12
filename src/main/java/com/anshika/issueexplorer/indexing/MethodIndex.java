package com.anshika.issueexplorer.indexing;

import com.anshika.issueexplorer.github.GitHubIssueUrlParser;
import com.anshika.issueexplorer.parsing.JavaDirectoryParser;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class MethodIndex {
    public static final int INDEX_VERSION = 3;
    private static final String PARSER_VERSION = "javaparser-3.28.2";
    public record IndexResult(Path directory, boolean reused, int methods, long parsedFiles, long failedFiles) { }
    private final int version;

    public MethodIndex() {
        this(INDEX_VERSION);
    }

    public MethodIndex(int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("Index version must be positive.");
        }
        this.version = version;
    }

    public IndexResult build(Path source, Path cache, String repo, String sha) throws IOException {
        return build(source, cache, repo, sha, null);
    }

    public IndexResult build(Path source, Path cache, String repo, String sha,
                             JavaDirectoryParser.DirectoryResult parsed) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Index source must be an existing directory, not a symbolic link.");
        }
        if ((repo == null) != (sha == null)) {
            throw new IllegalArgumentException("Provide both --repo and --sha, or neither.");
        }
        boolean githubSnapshot = repo != null;

        if (repo == null) {
            Path parent = root.getParent();
            if (root.getFileName() != null && root.getFileName().toString().equals("src") && parent != null
                    && parent.getFileName().toString().matches("[a-fA-F0-9]{40}")
                    && parent.getParent() != null && parent.getParent().getParent() != null) {
                githubSnapshot = true;
                sha = parent.getFileName().toString();
                repo = parent.getParent().getParent().getFileName() + "/" + parent.getParent().getFileName();
            } else {
                if (parsed == null) {
                    parsed = new JavaDirectoryParser().parse(root);
                }
                String name = root.getFileName() == null ? "root" : root.getFileName().toString();
                name = name.replaceAll("[^A-Za-z0-9_.-]", "_");
                if (name.equals(".") || name.equals("..") || name.isEmpty()) {
                    name = "folder";
                }
                repo = "local/" + name;
                sha = localSourceKey(root, parsed);
            }
        }
        var reference = new GitHubIssueUrlParser().parse("https://github.com/" + repo + "/issues/1");
        if (!sha.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalArgumentException("Index cache SHA must contain 40 hexadecimal characters.");
        }
        String repository = (reference.owner() + "/" + reference.repo()).toLowerCase(Locale.ROOT);
        String commit = sha.toLowerCase(Locale.ROOT);
        Path target = cache.toAbsolutePath().normalize().resolve(repository).resolve(commit).resolve("index-v" + version);
        Map<String, String> identity = Map.of("repo", repository, "sha", commit,
                "indexVersion", Integer.toString(version), "parserVersion", PARSER_VERSION,
                "sourceRoot", root.toString(), "githubSnapshot", Boolean.toString(githubSnapshot));
        IndexResult existing = reusable(target, identity);
        if (existing != null) {
            return existing;
        }
        if (parsed == null) {
            parsed = new JavaDirectoryParser().parse(root);
        }
        Files.createDirectories(target.getParent());
        Path staging = Files.createTempDirectory(target.getParent(), ".index-");
        Path previous = null;
        boolean published = false;
        int count = 0;
        try {
            try (var directory = FSDirectory.open(staging);
                 var analyzer = new PerFieldAnalyzerWrapper(new StandardAnalyzer(), Map.of("identifier", new IdentifierAnalyzer()));
                 var writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                for (var file : parsed.files()) {
                    if (!file.successful()) {
                        continue;
                    }
                    String sourceHash = sourceFingerprint(root.resolve(file.path()));
                    for (var method : file.declarations().methods()) {
                        var document = new Document();
                        document.add(new TextField("identifier", method.name(), Field.Store.YES));
                        document.add(new TextField("path", file.path(), Field.Store.YES));
                        document.add(new TextField("comments", method.javadoc() + "\n" + method.comments(), Field.Store.YES));
                        document.add(new TextField("body", method.body(), Field.Store.YES));
                        document.add(new StringField("kind", isTest(root.resolve(file.path()).toString().replace((char) 92, '/')) ? "test" : "source", Field.Store.YES));
                        document.add(new StringField("fileExact", Path.of(file.path()).getFileName().toString().toLowerCase(Locale.ROOT), Field.Store.NO));
                        document.add(new StringField("methodExact", method.name().toLowerCase(Locale.ROOT), Field.Store.NO));
                        for (var type : file.declarations().types()) {
                            document.add(new StringField("typeExact", type.name().toLowerCase(Locale.ROOT), Field.Store.NO));
                        }
                        document.add(new StoredField("method", method.name()));
                        document.add(new StoredField("signature", method.signature()));
                        document.add(new StoredField("sourceSha256", sourceHash));
                        document.add(new StoredField("startLine", method.startLine()));
                        document.add(new StoredField("endLine", method.endLine()));
                        writer.addDocument(document);
                        count++;
                    }
                }
                var metadata = new java.util.HashMap<>(identity);
                metadata.put("sourceRoot", root.toString());
                metadata.put("githubSnapshot", Boolean.toString(githubSnapshot));
                metadata.put("methods", Integer.toString(count));
                metadata.put("parsedFiles", Long.toString(parsed.parsed()));
                metadata.put("failedFiles", Long.toString(parsed.failed()));
                writer.setLiveCommitData(metadata.entrySet());
                writer.commit();
            }
            if (Files.exists(target)) {
                previous = Files.createTempDirectory(target.getParent(), ".index-old-");
                Files.delete(previous);
                Files.move(target, previous);
            }
            try {
                Files.move(staging, target);
                published = true;
            } catch (IOException exception) {
                if (previous != null) {
                    Files.move(previous, target);
                    previous = null;
                }
                throw exception;
            }
            return new IndexResult(target, false, count, parsed.parsed(), parsed.failed());
        } finally {
            deleteTemporary(staging, target.getParent());
            if (published) {
                deleteTemporary(previous, target.getParent());
            }
        }
    }

    private IndexResult reusable(Path target, Map<String, String> identity) {
        if (!Files.isDirectory(target) || Files.isSymbolicLink(target)) {
            return null;
        }
        try (var directory = FSDirectory.open(target)) {
            if (!DirectoryReader.indexExists(directory)) {
                return null;
            }
            try (var reader = DirectoryReader.open(directory)) {
                var data = reader.getIndexCommit().getUserData();
                if (!identity.entrySet().stream().allMatch(entry -> entry.getValue().equals(data.get(entry.getKey())))) {
                    return null;
                }
                int methods = Integer.parseInt(data.get("methods"));
                if (methods != reader.numDocs()) {
                    return null;
                }
                return new IndexResult(target, true, methods,
                        Long.parseLong(data.get("parsedFiles")), Long.parseLong(data.get("failedFiles")));
            }
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private String localSourceKey(Path root, JavaDirectoryParser.DirectoryResult parsed) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(root.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (var file : parsed.files()) {
                digest.update(file.path().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (file.successful()) {
                    try (var input = Files.newInputStream(root.resolve(file.path()))) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = input.read(buffer)) != -1) {
                            digest.update(buffer, 0, read);
                        }
                    }
                } else {
                    digest.update(file.error().getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 is unavailable.", impossible);
        }
    }

    public static String sourceFingerprint(Path source) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(source)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private boolean isTest(String path) {
        return path.startsWith("src/test/") || path.contains("/src/test/")
                || path.startsWith("test/") || path.startsWith("tests/")
                || path.contains("/test/") || path.contains("/tests/");
    }

    private void deleteTemporary(Path temporary, Path parent) {
        if (temporary == null || !temporary.getParent().equals(parent)
                || !temporary.getFileName().toString().startsWith(".index-")) {
            return;
        }
        try (var files = Files.walk(temporary)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temporary trees cannot be mistaken for a committed index.
        }
    }
}






