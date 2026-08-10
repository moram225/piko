package app.morphe.extension.twitter.safex.core;

public final class FeatureStats {
    public double positiveWeight;
    public double negativeWeight;
    public int positiveDocs;
    public int negativeDocs;
    public int documentFrequency;
    public long lastSeenMs;

    public FeatureStats copy() {
        FeatureStats out = new FeatureStats();
        out.positiveWeight = positiveWeight;
        out.negativeWeight = negativeWeight;
        out.positiveDocs = positiveDocs;
        out.negativeDocs = negativeDocs;
        out.documentFrequency = documentFrequency;
        out.lastSeenMs = lastSeenMs;
        return out;
    }

    public double totalWeight() {
        return positiveWeight + negativeWeight;
    }
}
