package com.anshika.issueexplorer.ranking;

/** Field/query boosts and the bounded bonus added during method-to-file aggregation. */
public record RankingWeights(float identifier, float path, float comments, float body,
                             float classHint, float fileHint, float stackHint, float methodBonus) {
    public static RankingWeights defaults() {
        return new RankingWeights(4, 3, 2, 1, 8, 10, 12, 0.1f);
    }

    public RankingWeights {
        for (float value : new float[]{identifier, path, comments, body, classHint, fileHint, stackHint, methodBonus}) {
            if (!Float.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Ranking weights must be finite and non-negative.");
            }
        }
        if (identifier + path + comments + body == 0) {
            throw new IllegalArgumentException("At least one text field weight must be positive.");
        }
        if (methodBonus > 1) {
            throw new IllegalArgumentException("Method bonus must be between 0 and 1.");
        }
    }
}
