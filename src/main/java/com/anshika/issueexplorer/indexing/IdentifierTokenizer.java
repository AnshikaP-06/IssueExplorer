package com.anshika.issueexplorer.indexing;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Produces distinct split words followed by the complete lowercase identifier. */
public final class IdentifierTokenizer {
    private static final Pattern BOUNDARIES = Pattern.compile(
            "(?<=[\\p{Ll}\\p{Lo}\\p{M}])(?=\\p{Lu})"
                    + "|(?<=\\p{Lu})(?=\\p{Lu}[\\p{Ll}\\p{Lo}])"
                    + "|(?<=[\\p{L}\\p{M}])(?=\\p{Nd})"
                    + "|(?<=\\p{Nd})(?=\\p{L})");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{M}\\p{Nd}]+");

    public List<String> tokenize(String identifier) {
        if (identifier == null) {
            throw new IllegalArgumentException("Identifier must not be null.");
        }
        if (identifier.isBlank()) {
            return List.of();
        }
        // Split before lowercasing so uppercase acronym boundaries remain visible.
        String separated = BOUNDARIES.matcher(identifier).replaceAll(" ");
        var tokens = new LinkedHashSet<String>();
        for (String word : SEPARATORS.split(separated)) {
            if (!word.isEmpty()) {
                tokens.add(word.toLowerCase(Locale.ROOT));
            }
        }
        tokens.add(identifier.toLowerCase(Locale.ROOT));
        return List.copyOf(tokens);
    }
}
