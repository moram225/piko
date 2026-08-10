package app.morphe.extension.twitter.safex;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.twitter.entity.Tweet;
import app.morphe.extension.twitter.safex.core.Decision;
import app.morphe.extension.twitter.safex.core.FeatureExtractor;
import app.morphe.extension.twitter.safex.core.PostFeatures;
import app.morphe.extension.twitter.safex.core.SafeXLearner;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SafeX runtime state and learning policy.
 *
 * v0.6 keeps automatic classification post-local, but adds a separate search
 * query risk check. A high-risk search query never becomes training evidence;
 * it is only contextual information used to stop visual NSFW search results
 * that X itself incorrectly labels as non-sensitive.
 */
@SuppressWarnings("unused")
public final class SafeXRuntime {
    private static final Pattern QUERY_TOKEN =
            Pattern.compile("(?iu)[\\p{L}\\p{N}_]{2,80}");

    private static volatile boolean enabled;
    private static volatile AndroidFeatureRepository repository;
    private static volatile SafeXLearner learner;
    private static final FeatureExtractor extractor = new FeatureExtractor();

    public static void enable() {
        enabled = true;
        ensureInit();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static void ensureInit() {
        if (!enabled || learner != null) return;
        synchronized (SafeXRuntime.class) {
            if (learner == null) {
                repository = new AndroidFeatureRepository(Utils.getContext());
                learner = new SafeXLearner(repository);
            }
        }
    }

    /**
     * Evaluate one concrete post object after X has produced its modern model.
     *
     * Rules:
     * - X's own sensitive signal is a strong positive example (+1.0).
     * - a previously manually/server-blocked post remains blocked everywhere.
     * - a learned prediction may block, but never trains itself.
     * - if an X-sensitive object has no usable ID, block it anyway but skip
     *   persistence/training for that object.
     */
    public static boolean classifyPost(long postId, String classifierText, boolean xSensitive) {
        if (!enabled) enable();

        try {
            ensureInit();
            String text = classifierText == null ? "" : classifierText;
            PostFeatures features = extractor.extract(text);

            if (xSensitive) {
                if (postId != 0L) {
                    if (repository.markObserved(postId, "X_SENSITIVE")) {
                        learner.observePositive(features, SafeXLearner.WEIGHT_X_SENSITIVE);
                    }
                    repository.blockTweet(postId, "X_SENSITIVE");
                }
                return true;
            }

            if (postId != 0L && repository.isTweetBlocked(postId)) return true;

            Decision decision = learner.score(features);
            // IMPORTANT: automatic SafeX predictions are NEVER positive labels.
            return decision.block;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return xSensitive;
        }
    }

    /**
     * Returns true only for clearly high-risk search queries.
     *
     * In addition to scoring the raw query, every token is also evaluated as a
     * hashtag family. This means searches such as `momson` and `chudai` inherit
     * the same mutation-aware protection as `#momson` / `#chudai`, while weak
     * contextual families such as `teen` remain below the block threshold.
     *
     * This method never updates the learner.
     */
    public static boolean isHighRiskSearchQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) return false;
        if (!enabled) enable();

        try {
            ensureInit();
            Decision raw = learner.score(extractor.extract(rawQuery));
            if (raw.block) return true;

            Matcher matcher = QUERY_TOKEN.matcher(FeatureExtractor.normalize(rawQuery));
            while (matcher.find()) {
                String token = matcher.group();
                // Search operators and generic glue words should not become
                // synthetic hashtags.
                if ("from".equals(token) || "to".equals(token)
                        || "filter".equals(token) || "since".equals(token)
                        || "until".equals(token) || "lang".equals(token)) {
                    continue;
                }
                Decision asHashtag = learner.score(extractor.extract("#" + token));
                if (asHashtag.block) return true;
            }
            return false;
        } catch (Throwable t) {
            PikoUtils.logger(t);
            return false;
        }
    }

    /**
     * Commit weak negative evidence only after the complete post, including any
     * quoted/reposted child, has survived SafeX classification.
     */
    public static void observeSafePost(long postId, String classifierText) {
        if (!enabled || postId == 0L) return;

        try {
            ensureInit();
            if (repository.markObserved(postId, "X_NOT_SENSITIVE")) {
                PostFeatures features = extractor.extract(classifierText == null ? "" : classifierText);
                learner.observeWeakSafe(features);
            }
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    // Compatibility wrappers for the retired response-filter experiment.
    public static boolean classifyNetworkPost(long postId, String classifierText, boolean xSensitive) {
        return classifyPost(postId, classifierText, xSensitive);
    }

    public static void observeNetworkSafe(long postId, String classifierText) {
        observeSafePost(postId, classifierText);
    }

    public static boolean isManualAction(Object buttonPressed) {
        return enabled
                && buttonPressed != null
                && "MarkTweetPossiblySensitive".equals(String.valueOf(buttonPressed));
    }

    /** Called from X's existing three-dot post menu hook. */
    public static void markManualNsfw(Object tweetObj) {
        if (!enabled) enable();
        if (tweetObj == null) return;

        try {
            ensureInit();
            Tweet tweet = new Tweet(tweetObj);
            long tweetId = tweet.getTweetId();

            String text = tweet.getLongText();
            if (text == null) text = tweet.getShortText();
            if (text == null) text = "";

            PostFeatures features = extractor.extract(text);
            if (repository.markObserved(tweetId, "MANUAL_NSFW")) {
                learner.observePositive(features, SafeXLearner.WEIGHT_MANUAL_NSFW);
            }
            repository.blockTweet(tweetId, "MANUAL_NSFW");
        } catch (Throwable t) {
            PikoUtils.logger(t);
        }
    }

    private SafeXRuntime() {}
}
