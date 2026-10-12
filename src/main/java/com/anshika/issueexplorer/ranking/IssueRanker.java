package com.anshika.issueexplorer.ranking;

import com.anshika.issueexplorer.indexing.IdentifierAnalyzer;
import com.anshika.issueexplorer.indexing.MethodIndex;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class IssueRanker {
    private final RankingWeights weights;

    public IssueRanker(RankingWeights weights) {
        this.weights = weights;
    }

    public List<FileScoreAggregator.FileScore> rank(Path index, String issueText, int top) throws IOException {
        if (top <= 0) {
            throw new IllegalArgumentException("Top result count must be positive.");
        }
        if (!Files.isDirectory(index)) {
            throw new IllegalArgumentException("Index path must be an existing Lucene index directory.");
        }
        var hints = new IssueAnalyzer().analyze("", issueText);
        try (var directory = FSDirectory.open(index)) {
            if (!DirectoryReader.indexExists(directory)) {
                throw new IllegalArgumentException("Index path does not contain a Lucene index.");
            }
            try (var reader = DirectoryReader.open(directory)) {
                String version = reader.getIndexCommit().getUserData().get("indexVersion");
                if (!Integer.toString(MethodIndex.INDEX_VERSION).equals(version)) {
                    throw new IllegalArgumentException("Index version is outdated; rebuild with the index command.");
                }
                var searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                Query query = query(issueText == null ? "" : issueText, hints);
                if (query == null || reader.numDocs() == 0) {
                    return List.of();
                }
                // Gather all matching methods before aggregation so top files aren't lost to a method cutoff.
                var hits = searcher.search(query, reader.numDocs());
                var methods = new ArrayList<FileScoreAggregator.MethodScore>();
                var documentIds = new java.util.HashMap<FileScoreAggregator.MethodScore, Integer>();
                for (var hit : hits.scoreDocs) {
                    var document = reader.storedFields().document(hit.doc);
                    var methodScore = new FileScoreAggregator.MethodScore(document.get("path"), document.get("method"),
                            document.get("signature"), document.getField("startLine").numericValue().intValue(),
                            document.getField("endLine").numericValue().intValue(), hit.score);
                    methods.add(methodScore);
                    documentIds.put(methodScore, hit.doc);
                }
                var files = new FileScoreAggregator().aggregate(methods, weights.methodBonus()).stream().limit(top).toList();
                var queryTerms = new java.util.HashSet<Term>();
                query.visit(org.apache.lucene.search.QueryVisitor.termCollector(queryTerms));
                var evidence = new ArrayList<FileScoreAggregator.FileScore>();
                for (var file : files) {
                    var matches = new ArrayList<FileScoreAggregator.MethodScore>();
                    for (var method : file.methods()) {
                        var terms = new java.util.TreeSet<String>();
                        for (var term : queryTerms) {
                            var postings = org.apache.lucene.index.MultiTerms.getTermPostingsEnum(reader, term.field(), term.bytes());
                            if (postings != null && postings.advance(documentIds.get(method)) == documentIds.get(method)) {
                                terms.add(term.text());
                            }
                        }
                        matches.add(new FileScoreAggregator.MethodScore(method.path(), method.method(), method.signature(),
                                method.startLine(), method.endLine(), method.score(), List.copyOf(terms)));
                    }
                    evidence.add(new FileScoreAggregator.FileScore(file.path(), file.score(), matches));
                }
                return List.copyOf(evidence);
            }
        }
    }

    private Query query(String issue, IssueHints hints) throws IOException {
        var result = new BooleanQuery.Builder();
        var terms = new LinkedHashSet<String>();
        try (var analyzer = new StandardAnalyzer(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET);
             var tokens = analyzer.tokenStream("issue", issue)) {
            var term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken() && terms.size() < 128) {
                terms.add(term.toString());
            }
            tokens.end();
        }
        try (var analyzer = new IdentifierAnalyzer(); var tokens = analyzer.tokenStream("identifier", issue)) {
            var term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (tokens.incrementToken() && terms.size() < 128) {
                String word = term.toString();
                if (!EnglishAnalyzer.ENGLISH_STOP_WORDS_SET.contains(word)) {
                    terms.add(word);
                }
            }
            tokens.end();
        }
        // These labels don't provide useful location evidence on their own.
        terms.removeAll(List.of("title", "body", "expected", "actual", "behavior", "describe", "bug", "issue",
                "after", "before", "while", "during", "when", "should", "could", "would", "can", "please", "does", "do"));
        var lexical = new BooleanQuery.Builder();
        for (String term : terms) {
            var perTerm = new BooleanQuery.Builder();
            addAnalyzed(perTerm, "identifier", term, weights.identifier(), true);
            addAnalyzed(perTerm, "path", term, weights.path(), false);
            addAnalyzed(perTerm, "comments", term, weights.comments(), false);
            addAnalyzed(perTerm, "body", term, weights.body(), false);
            perTerm.setMinimumNumberShouldMatch(1);
            lexical.add(perTerm.build(), BooleanClause.Occur.SHOULD);
        }
        if (!terms.isEmpty()) {
            // Require two distinct terms for longer issues to avoid a single incidental code token.
            lexical.setMinimumNumberShouldMatch(Math.min(2, terms.size()));
            result.add(lexical.build(), BooleanClause.Occur.SHOULD);
        }
        for (String name : hints.classNames()) {
            addBoost(result, new TermQuery(new Term("typeExact", name.toLowerCase(Locale.ROOT))), weights.classHint());
        }
        for (String file : hints.javaFiles()) {
            addBoost(result, new TermQuery(new Term("fileExact", file.toLowerCase(Locale.ROOT))), weights.fileHint());
        }
        for (var frame : hints.stackFrames()) {
            if (frame.fileName() == null) {
                continue;
            }
            var exact = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term("fileExact", frame.fileName().toLowerCase(Locale.ROOT))), BooleanClause.Occur.MUST)
                    .add(new TermQuery(new Term("methodExact", frame.methodName().toLowerCase(Locale.ROOT))), BooleanClause.Occur.MUST)
                    .build();
            addBoost(result, exact, weights.stackHint());
        }
        var built = result.setMinimumNumberShouldMatch(1).build();
        return built.clauses().isEmpty() ? null : built;
    }

    private void addAnalyzed(BooleanQuery.Builder query, String field, String text, float weight, boolean identifier) throws IOException {
        if (weight == 0) {
            return;
        }
        try (var analyzer = identifier ? new IdentifierAnalyzer() : new StandardAnalyzer();
             var tokens = analyzer.tokenStream(field, text)) {
            var term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            var unique = new LinkedHashSet<String>();
            while (tokens.incrementToken()) {
                unique.add(term.toString());
            }
            tokens.end();
            for (String token : unique) {
                addBoost(query, new TermQuery(new Term(field, token)), weight);
            }
        }
    }

    private void addBoost(BooleanQuery.Builder query, Query clause, float weight) {
        if (weight > 0) {
            query.add(new BoostQuery(clause, weight), BooleanClause.Occur.SHOULD);
        }
    }
}


