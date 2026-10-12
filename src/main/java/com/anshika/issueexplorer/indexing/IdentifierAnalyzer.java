package com.anshika.issueexplorer.indexing;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Uses identical identifier splitting when indexing names and analyzing queries. */
public final class IdentifierAnalyzer extends Analyzer {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}\\p{M}\\p{Nd}_$]+");

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        return new TokenStreamComponents(new Tokenizer() {
            private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
            private List<String> words = List.of();
            private int position;

            @Override
            public void reset() throws IOException {
                super.reset();
                var text = new StringBuilder();
                char[] buffer = new char[1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                }
                var tokens = new ArrayList<String>();
                var matcher = IDENTIFIER.matcher(text);
                var tokenizer = new IdentifierTokenizer();
                while (matcher.find()) {
                    tokens.addAll(tokenizer.tokenize(matcher.group()));
                }
                words = tokens;
                position = 0;
            }

            @Override
            public boolean incrementToken() {
                if (position == words.size()) {
                    return false;
                }
                clearAttributes();
                term.append(words.get(position++));
                return true;
            }
        });
    }
}
