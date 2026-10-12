package com.anshika.issueexplorer.ranking;

import com.anshika.issueexplorer.indexing.MethodIndex;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RankingEvidence {
    public record Result(String path, float score, String method, String signature, int startLine, int endLine,
                         List<String> matchedTerms, SourceSnippet.Snippet snippet, String permalink) {
        public Result {
            matchedTerms = List.copyOf(matchedTerms);
        }
    }
    public record Report(int schemaVersion, String repository, String commitSha, List<Result> results) {
        public Report {
            results = List.copyOf(results);
        }
    }

    public Report build(Path index, List<FileScoreAggregator.FileScore> files) throws IOException {
        try (var directory = FSDirectory.open(index); var reader = DirectoryReader.open(directory)) {
            var metadata = reader.getIndexCommit().getUserData();
            String root = metadata.get("sourceRoot");
            if (root == null) {
                throw new IllegalArgumentException("Index lacks source evidence metadata; rebuild it.");
            }
            boolean snapshot = Boolean.parseBoolean(metadata.get("githubSnapshot"));
            String repository = snapshot ? metadata.get("repo") : null;
            String sha = snapshot ? metadata.get("sha") : null;
            var snippets = new SourceSnippet();
            var results = new ArrayList<Result>();
            for (var file : files) {
                var method = file.methods().getFirst();
                String expectedHash = null;
                for (int i = 0; i < reader.maxDoc(); i++) {
                    var doc = reader.storedFields().document(i);
                    if (method.path().equals(doc.get("path")) && method.signature().equals(doc.get("signature"))
                            && method.startLine() == doc.getField("startLine").numericValue().intValue()) {
                        expectedHash = doc.get("sourceSha256");
                        break;
                    }
                }
                Path sourceRoot = Path.of(root);
                Path source = snippets.sourceFile(sourceRoot, method.path());
                if (expectedHash == null || !expectedHash.equals(MethodIndex.sourceFingerprint(source))) {
                    throw new IOException("Source changed since indexing; rebuild the index before reading snippets.");
                }
                var snippet = snippets.read(sourceRoot, method.path(), method.startLine(), method.endLine(), 8);
                String permalink = snapshot ? new GitHubPermalink().build(repository, sha, method.path(), method.startLine(), method.endLine()) : null;
                results.add(new Result(file.path(), file.score(), method.method(), method.signature(), method.startLine(),
                        method.endLine(), method.matchedTerms(), snippet, permalink));
            }
            return new Report(1, repository, sha, results);
        }
    }
}
