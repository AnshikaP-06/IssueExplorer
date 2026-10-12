package com.anshika.issueexplorer.parsing;

import com.anshika.issueexplorer.config.AppConfig;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class JavaDirectoryParser {
    private static final Set<String> EXCLUDED = Set.of("target", "build", ".git", "node_modules",
            "generated", "generated-sources", "generated-test-sources");
    private final int threads;
    private final int maxFiles;
    private final long maxBytes;

    public JavaDirectoryParser() {
        this(AppConfig.defaults().parserThreads());
    }

    public JavaDirectoryParser(int threads) {
        this(threads, AppConfig.defaults().maxFiles(), AppConfig.defaults().maxExtractedBytes());
    }

    public JavaDirectoryParser(int threads, int maxFiles, long maxBytes) {
        if (threads <= 0 || maxFiles <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Parser threads and source limits must be positive.");
        }
        this.threads = threads;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    public record DirectoryResult(List<JavaFileParser.FileResult> files) {
        public DirectoryResult {
            files = List.copyOf(files);
        }
        public long parsed() {
            return files.stream().filter(JavaFileParser.FileResult::successful).count();
        }
        public long failed() {
            return files.size() - parsed();
        }
    }

    public DirectoryResult parse(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Parse path must be an existing directory, not a symbolic link.");
        }
        var paths = new ArrayList<Path>();
        var results = new ArrayList<JavaFileParser.FileResult>();
        long[] bytes = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                return !path.equals(root) && EXCLUDED.contains(path.getFileName().toString().toLowerCase(Locale.ROOT))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile() && path.getFileName().toString().endsWith(".java")) {
                    checkCount();
                    if (attributes.size() > maxBytes - bytes[0]) {
                        throw new ParsingLimitException("Java source size exceeds the configured maximum of " + maxBytes + " bytes.");
                    }
                    bytes[0] += attributes.size();
                    paths.add(path);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (path.getFileName().toString().endsWith(".java")) {
                    checkCount();
                    results.add(new JavaFileParser.FileResult(relative(root, path), null, "Could not read Java file."));
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
            private void checkCount() throws ParsingLimitException {
                if (paths.size() + results.size() >= maxFiles) {
                    throw new ParsingLimitException("Java source file count exceeds the configured maximum of " + maxFiles + ".");
                }
            }
        });
        paths.sort(Comparator.comparing(path -> relative(root, path)));
        if (threads == 1) {
            for (Path path : paths) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Java parsing was interrupted.");
                }
                results.add(new JavaFileParser().parse(path, relative(root, path)));
            }
        } else if (!paths.isEmpty()) {
            parseParallel(root, paths, results);
        }
        results.sort(Comparator.comparing(JavaFileParser.FileResult::path));
        return new DirectoryResult(results);
    }

    private void parseParallel(Path root, List<Path> paths, List<JavaFileParser.FileResult> results) throws IOException {
        int workers = Math.min(threads, paths.size());
        var executor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), Thread.ofPlatform().daemon(true).name("issue-parser-", 0).factory());
        var completion = new ExecutorCompletionService<JavaFileParser.FileResult>(executor);
        try {
            int submitted = 0;
            for (; submitted < workers; submitted++) {
                Path path = paths.get(submitted);
                completion.submit(() -> new JavaFileParser().parse(path, relative(root, path)));
            }
            // At most 'workers' tasks are in flight; the queue cannot grow with repository size.
            for (int finished = 0; finished < paths.size(); finished++) {
                results.add(completion.take().get());
                if (submitted < paths.size()) {
                    Path path = paths.get(submitted++);
                    completion.submit(() -> new JavaFileParser().parse(path, relative(root, path)));
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Java parsing was interrupted.", exception);
        } catch (ExecutionException exception) {
            throw new IOException("A Java parser task failed.", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace((char) 92, '/');
    }
}
