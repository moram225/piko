package app.morphe.extension.twitter.safex.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class SafeXLearner {
    public static final double WEIGHT_X_SENSITIVE = 1.0;
    public static final double WEIGHT_MANUAL_NSFW = 2.0;
    public static final double WEIGHT_X_NOT_SENSITIVE = 0.10;

    private static final double BLOCK_THRESHOLD = 5.0;
    private static final int MIN_LEARNED_POSITIVE_DOCS = 3;
    private static final double FAMILY_SIMILARITY_THRESHOLD = 0.84;

    private final FeatureRepository repository;
    private final SeedLexicon seeds;

    public SafeXLearner(FeatureRepository repository) {
        this(repository, new SeedLexicon());
    }

    public SafeXLearner(FeatureRepository repository, SeedLexicon seeds) {
        this.repository = repository;
        this.seeds = seeds;
    }

    public synchronized void observePositive(PostFeatures features, double weight) {
        observe(features, true, weight);
    }

    public synchronized void observeWeakSafe(PostFeatures features) {
        observe(features, false, WEIGHT_X_NOT_SENSITIVE);
    }

    private void observe(PostFeatures features, boolean positive, double weight) {
        repository.setDocumentCount(repository.getDocumentCount() + 1);
        long now = System.currentTimeMillis();
        for (FeatureKey key : features.all()) {
            FeatureStats stats = repository.get(key);
            if (stats == null) stats = new FeatureStats();
            if (positive) {
                stats.positiveWeight += weight;
                stats.positiveDocs++;
            } else {
                stats.negativeWeight += weight;
                stats.negativeDocs++;
            }
            stats.documentFrequency++;
            stats.lastSeenMs = now;
            repository.put(key, stats);
        }
    }

    public Decision score(PostFeatures features) {
        List<ScoredReason> reasons = new ArrayList<>();
        boolean hard = false;

        for (String word : features.values(FeatureType.WORD)) {
            if (seeds.hardWord(word)) {
                hard = true;
                reasons.add(new ScoredReason(100.0, "hard-word:" + word));
            } else {
                double w = seeds.wordWeight(word);
                if (w > 0) reasons.add(new ScoredReason(w, "seed-word:" + word));
            }
        }

        for (String phrase : features.values(FeatureType.BIGRAM)) {
            double w = seeds.phraseWeight(phrase);
            if (w > 0) reasons.add(new ScoredReason(w, "seed-phrase:" + phrase));
        }
        for (String phrase : features.values(FeatureType.TRIGRAM)) {
            double w = seeds.phraseWeight(phrase);
            if (w > 0) reasons.add(new ScoredReason(w, "seed-phrase:" + phrase));
        }

        for (String domain : features.values(FeatureType.DOMAIN)) {
            double w = seeds.domainWeight(domain);
            if (w > 0) reasons.add(new ScoredReason(w, "seed-domain:" + domain));
        }

        for (String tag : features.values(FeatureType.HASHTAG)) {
            addSeedHashtagFamilyReason(tag, reasons);
            addLearnedHashtagFamilyReason(tag, reasons);
        }

        for (FeatureKey key : features.all()) {
            FeatureStats stats = repository.get(key);
            if (stats == null || stats.positiveDocs < MIN_LEARNED_POSITIVE_DOCS) continue;
            double contribution = learnedContribution(key, stats);
            if (contribution > 0.15) reasons.add(new ScoredReason(contribution, "learned:" + key));
        }

        if (hard) {
            List<String> out = new ArrayList<>();
            for (ScoredReason r : reasons) out.add(r.reason);
            return new Decision(true, 100.0, out);
        }

        reasons.sort(Comparator.comparingDouble((ScoredReason r) -> r.score).reversed());
        double total = 0.0;
        List<String> out = new ArrayList<>();
        int used = 0;
        for (ScoredReason r : reasons) {
            if (used++ >= 5) break;
            total += r.score;
            out.add(String.format(java.util.Locale.ROOT, "%.2f %s", r.score, r.reason));
        }
        return new Decision(total >= BLOCK_THRESHOLD, total, out);
    }

    private void addSeedHashtagFamilyReason(String tag, List<ScoredReason> reasons) {
        for (Map.Entry<String, Double> family : seeds.hashtagFamilies().entrySet()) {
            double sim = StringSimilarity.hashtagSimilarity(tag, family.getKey());
            if (sim >= FAMILY_SIMILARITY_THRESHOLD) {
                reasons.add(new ScoredReason(family.getValue() * sim,
                        "seed-hashtag-family:" + family.getKey() + "~" + tag));
            }
        }
    }

    private void addLearnedHashtagFamilyReason(String tag, List<ScoredReason> reasons) {
        for (Map.Entry<FeatureKey, FeatureStats> entry : repository.listByType(FeatureType.HASHTAG).entrySet()) {
            FeatureStats stats = entry.getValue();
            if (stats.positiveDocs < MIN_LEARNED_POSITIVE_DOCS) continue;

            double posterior = posterior(stats);
            if (posterior < 0.72) continue;

            double sim = StringSimilarity.hashtagSimilarity(tag, entry.getKey().value);
            if (sim < FAMILY_SIMILARITY_THRESHOLD) continue;

            double evidence = Math.min(1.0, stats.totalWeight() / 6.0);
            double score = 6.5 * sim * posterior * evidence;
            if (score > 0.25) {
                reasons.add(new ScoredReason(score,
                        "learned-hashtag-family:" + entry.getKey().value + "~" + tag));
            }
        }
    }

    private double learnedContribution(FeatureKey key, FeatureStats stats) {
        double posterior = posterior(stats);
        if (posterior <= 0.5) return 0.0;

        double evidence = Math.min(1.0, stats.totalWeight() / 6.0);
        double directional = (posterior - 0.5) * 2.0;
        double typeWeight;
        switch (key.type) {
            case HASHTAG: typeWeight = 3.0; break;
            case DOMAIN: typeWeight = 4.0; break;
            case TRIGRAM: typeWeight = 2.0; break;
            case BIGRAM: typeWeight = 1.5; break;
            default: typeWeight = 1.0;
        }

        double n = Math.max(1.0, repository.getDocumentCount());
        double idf = Math.log((n + 1.0) / (stats.documentFrequency + 1.0)) + 1.0;
        idf = Math.min(idf, 3.0);
        return directional * evidence * typeWeight * idf;
    }

    private double posterior(FeatureStats stats) {
        double alpha = 1.0;
        double beta = 3.0;
        return (stats.positiveWeight + alpha)
                / (stats.positiveWeight + stats.negativeWeight + alpha + beta);
    }

    private static final class ScoredReason {
        final double score;
        final String reason;
        ScoredReason(double score, String reason) {
            this.score = score;
            this.reason = reason;
        }
    }
}
